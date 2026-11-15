package com.dosium.culling;

import com.dosium.core.ComputeQueueManager;
import com.dosium.core.DosiumConfig;
import com.dosium.core.StorageBuffer;
import net.minecraft.client.MinecraftClient;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.buffer.DrawParamHelper;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.vulkan.VK12;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/**
 * Gestor de GPU Indirect Culling REAL (cull.comp + VkDrawIndexedIndirectCommand).
 * Empaqueta SectionData (48 bytes) para cull.comp en un SSBO Vulkan por frame,
 * despacha cull.comp en paralelo y enlaza los comandos indirectos generados
 * con vkCmdDrawIndexedIndirect / vkCmdDrawIndexedIndirectCount.
 */
public final class IndirectDrawManager {
    public static final int MAX_TRACKED_SECTIONS = 16384;
    public static final int SECTION_STRUCT_BYTES = 48; // vec4 minBounds (16) + vec4 maxBounds (16) + ivec4 offsets (16)
    public static final int INDIRECT_CMD_BYTES = 20;   // VkDrawIndexedIndirectCommand (5 * 4 bytes)
    public static final int DRAW_COUNT_BYTES = 16;     // uint drawCount (aligned to 16)

    private static ByteBuffer sectionSsboStaging;
    private static int packedSectionsCount = 0;
    private static long cullPassStartNs = 0L;

    private static StorageBuffer[] sectionBuffers;
    private static StorageBuffer[] indirectCmdBuffers;
    private static StorageBuffer[] drawCountBuffers;
    private static VulkanImage dummyHiZTexture;

    private static double currentCamX;
    private static double currentCamY;
    private static double currentCamZ;

    private IndirectDrawManager() {
    }

    private static synchronized void ensureBuffersAllocated() {
        if (sectionSsboStaging == null) {
            sectionSsboStaging = MemoryUtil.memAlloc(MAX_TRACKED_SECTIONS * SECTION_STRUCT_BYTES);
        }

        int frames = Math.max(1, Renderer.getFramesNum());
        if (sectionBuffers == null || sectionBuffers.length != frames) {
            cleanUpBuffers();
            sectionBuffers = new StorageBuffer[frames];
            indirectCmdBuffers = new StorageBuffer[frames];
            drawCountBuffers = new StorageBuffer[frames];

            for (int i = 0; i < frames; i++) {
                sectionBuffers[i] = new StorageBuffer(MAX_TRACKED_SECTIONS * SECTION_STRUCT_BYTES);
                indirectCmdBuffers[i] = new StorageBuffer(MAX_TRACKED_SECTIONS * INDIRECT_CMD_BYTES);
                drawCountBuffers[i] = new StorageBuffer(DRAW_COUNT_BYTES);
            }
        }

        if (dummyHiZTexture == null) {
            try {
                dummyHiZTexture = VulkanImage.createWhiteTexture();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void beginCullPass(double camX, double camY, double camZ) {
        cullPassStartNs = System.nanoTime();
        ensureBuffersAllocated();
        packedSectionsCount = 0;
        currentCamX = camX;
        currentCamY = camY;
        currentCamZ = camZ;

        DosiumConfig.CullingMode mode = DosiumConfig.get().gpuIndirectCulling;
        try {
            if (Initializer.CONFIG != null) {
                Initializer.CONFIG.indirectDraw = (mode != DosiumConfig.CullingMode.CPU_SODIUM);
                if (mode == DosiumConfig.CullingMode.FRUSTUM_HIZ) {
                    Initializer.CONFIG.advCulling = 2;
                } else if (mode == DosiumConfig.CullingMode.FRUSTUM_ONLY) {
                    Initializer.CONFIG.advCulling = 3;
                }
            }
        } catch (Throwable ignored) {
        }

        HiZPyramidBuilder.beginFrame(camX, camY, camZ);
    }

    public static void recordVisibleSection(RenderSection section) {
        if (section == null || sectionSsboStaging == null || packedSectionsCount >= MAX_TRACKED_SECTIONS) {
            return;
        }

        boolean recordedAny = false;
        for (TerrainRenderType type : TerrainRenderType.VALUES) {
            DrawBuffers.DrawParameters drawParams = section.getDrawParameters(type);
            if (drawParams != null && DrawParamHelper.getIndexCount(drawParams) > 0) {
                recordSectionDraw(section, drawParams, type.ordinal());
                recordedAny = true;
            }
        }

        if (!recordedAny) {
            DrawBuffers.DrawParameters drawParams = section.getDrawParameters(TerrainRenderType.SOLID);
            recordSectionDraw(section, drawParams, 0);
        }
    }

    public static void recordVisibleSection(RenderSection section, TerrainRenderType renderType, int chunkAreaIndex, int sectionIndex) {
        if (section == null || sectionSsboStaging == null || packedSectionsCount >= MAX_TRACKED_SECTIONS) {
            return;
        }
        DrawBuffers.DrawParameters drawParams = renderType != null ? section.getDrawParameters(renderType) : null;
        recordSectionDraw(section, drawParams, chunkAreaIndex, sectionIndex);
    }

    private static void recordSectionDraw(RenderSection section, DrawBuffers.DrawParameters drawParams, int renderTypeOrdinal) {
        int chunkAreaIndex = section.getChunkArea() != null ? section.getChunkArea().index : 0;
        recordSectionDraw(section, drawParams, chunkAreaIndex, packedSectionsCount);
    }

    private static void recordSectionDraw(RenderSection section, DrawBuffers.DrawParameters drawParams, int chunkAreaIndex, int sectionIndex) {
        if (packedSectionsCount >= MAX_TRACKED_SECTIONS) {
            return;
        }
        int baseOffset = packedSectionsCount * SECTION_STRUCT_BYTES;
        float minX = section.xOffset;
        float minY = section.yOffset;
        float minZ = section.zOffset;

        int indexCount = DrawParamHelper.getIndexCount(drawParams);
        int rawFirst = DrawParamHelper.getFirstIndex(drawParams);
        int firstIndex = rawFirst == -1 ? 0 : rawFirst;
        int vertexOffset = DrawParamHelper.getVertexOffset(drawParams);
        int baseInstance = DrawParamHelper.getBaseInstance(drawParams);

        // Struct SectionData de cull.comp (48 bytes std430):
        // vec4 minBounds: xyz = minPos, w = indexCount (as float bits)
        sectionSsboStaging.putFloat(baseOffset + 0, minX);
        sectionSsboStaging.putFloat(baseOffset + 4, minY);
        sectionSsboStaging.putFloat(baseOffset + 8, minZ);
        sectionSsboStaging.putFloat(baseOffset + 12, Float.intBitsToFloat(indexCount));

        // vec4 maxBounds: xyz = maxPos, w = firstIndex (as float bits)
        sectionSsboStaging.putFloat(baseOffset + 16, minX + 16.0f);
        sectionSsboStaging.putFloat(baseOffset + 20, minY + 16.0f);
        sectionSsboStaging.putFloat(baseOffset + 24, minZ + 16.0f);
        sectionSsboStaging.putFloat(baseOffset + 28, Float.intBitsToFloat(firstIndex));

        // ivec4 offsets: x = vertexOffset, y = baseInstance, z = chunkAreaIndex, w = sectionIndex
        sectionSsboStaging.putInt(baseOffset + 32, vertexOffset);
        sectionSsboStaging.putInt(baseOffset + 36, baseInstance);
        sectionSsboStaging.putInt(baseOffset + 40, chunkAreaIndex);
        sectionSsboStaging.putInt(baseOffset + 44, sectionIndex);

        packedSectionsCount++;
    }

    public static void endCullPass() {
        HiZPyramidBuilder.buildMipChain();

        DosiumConfig.CullingMode mode = DosiumConfig.get().gpuIndirectCulling;
        if (mode == DosiumConfig.CullingMode.CPU_SODIUM || !ComputeQueueManager.isCullPipelineReady() || packedSectionsCount == 0) {
            long elapsedNs = System.nanoTime() - cullPassStartNs;
            ComputeQueueManager.recordCullDurationNs(elapsedNs);
            return;
        }

        int frame = Renderer.getCurrentFrame();
        if (sectionBuffers != null && frame >= 0 && frame < sectionBuffers.length) {
            StorageBuffer secBuf = sectionBuffers[frame];
            StorageBuffer indBuf = indirectCmdBuffers[frame];
            StorageBuffer cntBuf = drawCountBuffers[frame];

            // 1. Subir SectionData al SSBO Vulkan
            secBuf.uploadData(sectionSsboStaging, 0, packedSectionsCount * SECTION_STRUCT_BYTES);

            // 2. Resetear drawCount a 0
            cntBuf.uploadInt(0, 0);

            // 3. Preparar PushConstants (192 bytes) y despachar compute si hay command buffer activo
            VkCommandBuffer cmd = Renderer.getCommandBuffer();
            if (cmd != null) {
                dispatchCullCompute(cmd, frame);
            }
        }

        long elapsedNs = System.nanoTime() - cullPassStartNs;
        ComputeQueueManager.recordCullDurationNs(elapsedNs);
    }

    public static void dispatchCullCompute(VkCommandBuffer cmd, int frameIndex) {
        if (!ComputeQueueManager.isCullPipelineReady() || packedSectionsCount == 0) {
            return;
        }
        if (sectionBuffers == null || frameIndex < 0 || frameIndex >= sectionBuffers.length) {
            return;
        }

        StorageBuffer secBuf = sectionBuffers[frameIndex];
        StorageBuffer indBuf = indirectCmdBuffers[frameIndex];
        StorageBuffer cntBuf = drawCountBuffers[frameIndex];

        long hizView = HiZPyramidBuilder.getHiZImageView();
        long hizSampler = HiZPyramidBuilder.getHiZSampler();
        if (hizView == 0L || hizSampler == 0L) {
            if (dummyHiZTexture != null) {
                hizView = dummyHiZTexture.getImageView();
                hizSampler = dummyHiZTexture.getSampler();
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pc = stack.malloc(192);

            // 1. mat4 viewProj (64 bytes)
            MappedBuffer mvpMapped = VRenderSystem.getMVP();
            Matrix4f mvp = new Matrix4f();
            if (mvpMapped != null && mvpMapped.buffer != null) {
                ByteBuffer mvpBuf = mvpMapped.buffer;
                mvpBuf.rewind();
                mvp.set(mvpBuf);
                mvpBuf.rewind();
            }
            mvp.get(0, pc);

            // 2. vec4 frustumPlanes[6] (96 bytes) extraídos con Gribb-Hartmann
            Vector4f[] planes = new Vector4f[6];
            extractFrustumPlanes(mvp, planes);
            for (int i = 0; i < 6; i++) {
                int planeOff = 64 + i * 16;
                pc.putFloat(planeOff + 0, planes[i].x);
                pc.putFloat(planeOff + 4, planes[i].y);
                pc.putFloat(planeOff + 8, planes[i].z);
                pc.putFloat(planeOff + 12, planes[i].w);
            }

            // 3. vec4 cameraPosAndMode (16 bytes): xyz = cameraPos, w = mode
            float modeFlag = (DosiumConfig.get().gpuIndirectCulling == DosiumConfig.CullingMode.FRUSTUM_HIZ) ? 2.0f : 1.0f;
            pc.putFloat(160, (float) currentCamX);
            pc.putFloat(164, (float) currentCamY);
            pc.putFloat(168, (float) currentCamZ);
            pc.putFloat(172, modeFlag);

            // 4. uvec4 countsAndScreen (16 bytes): x = totalSections, y = width, z = height, w = maxMip
            MinecraftClient mc = MinecraftClient.getInstance();
            int winW = mc.getWindow() != null ? mc.getWindow().getFramebufferWidth() : 1920;
            int winH = mc.getWindow() != null ? mc.getWindow().getFramebufferHeight() : 1080;
            int maxMip = HiZPyramidBuilder.getMaxMipLevel();
            pc.putInt(176, packedSectionsCount);
            pc.putInt(180, winW);
            pc.putInt(184, winH);
            pc.putInt(188, maxMip);

            ComputeQueueManager.dispatchCull(
                    cmd, frameIndex,
                    secBuf, indBuf, cntBuf,
                    hizView, hizSampler,
                    packedSectionsCount, pc
            );
        }
    }

    private static void extractFrustumPlanes(Matrix4f m, Vector4f[] outPlanes) {
        // Left
        outPlanes[0] = makePlane(m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20(), m.m33() + m.m30());
        // Right
        outPlanes[1] = makePlane(m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20(), m.m33() - m.m30());
        // Bottom
        outPlanes[2] = makePlane(m.m03() + m.m01(), m.m13() + m.m01(), m.m23() + m.m21(), m.m33() + m.m31());
        // Top
        outPlanes[3] = makePlane(m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21(), m.m33() - m.m31());
        // Near (Vulkan clip space [0, 1])
        outPlanes[4] = makePlane(m.m02(), m.m12(), m.m22(), m.m32());
        // Far
        outPlanes[5] = makePlane(m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22(), m.m33() - m.m32());
    }

    private static Vector4f makePlane(float a, float b, float c, float d) {
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        if (len > 1e-6f) {
            float inv = 1.0f / len;
            return new Vector4f(a * inv, b * inv, c * inv, d * inv);
        }
        return new Vector4f(a, b, c, d);
    }

    public static void executeIndirectDraw(VkCommandBuffer cmd, int frameIndex, int maxDrawCount) {
        if (indirectCmdBuffers == null || drawCountBuffers == null || frameIndex < 0 || frameIndex >= indirectCmdBuffers.length) {
            return;
        }
        StorageBuffer indBuf = indirectCmdBuffers[frameIndex];
        StorageBuffer cntBuf = drawCountBuffers[frameIndex];

        int count = Math.min(packedSectionsCount, maxDrawCount);
        if (count <= 0) {
            return;
        }

        try {
            // Intentar vkCmdDrawIndexedIndirectCount (Vulkan 1.2+ / KHR_draw_indirect_count)
            VK12.vkCmdDrawIndexedIndirectCount(
                    cmd,
                    indBuf.getId(), 0L,
                    cntBuf.getId(), 0L,
                    count, INDIRECT_CMD_BYTES
            );
        } catch (Throwable fallback) {
            // Fallback a vkCmdDrawIndexedIndirect estándar
            VK10.vkCmdDrawIndexedIndirect(
                    cmd,
                    indBuf.getId(), 0L,
                    count, INDIRECT_CMD_BYTES
            );
        }
    }

    public static StorageBuffer getCurrentIndirectBuffer(int frameIndex) {
        if (indirectCmdBuffers != null && frameIndex >= 0 && frameIndex < indirectCmdBuffers.length) {
            return indirectCmdBuffers[frameIndex];
        }
        return null;
    }

    public static StorageBuffer getCurrentDrawCountBuffer(int frameIndex) {
        if (drawCountBuffers != null && frameIndex >= 0 && frameIndex < drawCountBuffers.length) {
            return drawCountBuffers[frameIndex];
        }
        return null;
    }

    public static int getPackedSectionsCount() {
        return packedSectionsCount;
    }

    private static synchronized void cleanUpBuffers() {
        if (sectionBuffers != null) {
            for (StorageBuffer b : sectionBuffers) {
                if (b != null) b.free();
            }
            sectionBuffers = null;
        }
        if (indirectCmdBuffers != null) {
            for (StorageBuffer b : indirectCmdBuffers) {
                if (b != null) b.free();
            }
            indirectCmdBuffers = null;
        }
        if (drawCountBuffers != null) {
            for (StorageBuffer b : drawCountBuffers) {
                if (b != null) b.free();
            }
            drawCountBuffers = null;
        }
    }
}
