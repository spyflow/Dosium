package com.dosium.culling;

import com.dosium.core.ComputeQueueManager;
import com.dosium.core.DosiumConfig;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Gestor de GPU Indirect Culling (cull.comp + VkDrawIndexedIndirectCommand).
 * Empaqueta los AABB de las secciones de 16x16x16 en memoria off-heap para el SSBO de secciones,
 * coordina el Frustum + Hi-Z Culling y activa el dibujado indirecto en el backend de VulkanMod.
 */
public final class IndirectDrawManager {
    private static final int MAX_TRACKED_SECTIONS = 16384;
    private static final int SECTION_STRUCT_BYTES = 32; // minBounds(16) + maxBounds(16)
    private static ByteBuffer sectionSsboStaging;
    private static int packedSectionsCount = 0;
    private static long cullPassStartNs = 0L;

    private IndirectDrawManager() {
    }

    private static synchronized void ensureBufferAllocated() {
        if (sectionSsboStaging == null) {
            sectionSsboStaging = MemoryUtil.memAlloc(MAX_TRACKED_SECTIONS * SECTION_STRUCT_BYTES);
        }
    }

    public static void beginCullPass(double camX, double camY, double camZ) {
        cullPassStartNs = System.nanoTime();
        ensureBufferAllocated();
        packedSectionsCount = 0;

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
        int baseOffset = packedSectionsCount * SECTION_STRUCT_BYTES;
        float minX = section.xOffset;
        float minY = section.yOffset;
        float minZ = section.zOffset;

        sectionSsboStaging.putFloat(baseOffset, minX);
        sectionSsboStaging.putFloat(baseOffset + 4, minY);
        sectionSsboStaging.putFloat(baseOffset + 8, minZ);
        sectionSsboStaging.putFloat(baseOffset + 12, 1.0f);

        sectionSsboStaging.putFloat(baseOffset + 16, minX + 16.0f);
        sectionSsboStaging.putFloat(baseOffset + 20, minY + 16.0f);
        sectionSsboStaging.putFloat(baseOffset + 24, minZ + 16.0f);
        sectionSsboStaging.putFloat(baseOffset + 28, 1.0f);

        packedSectionsCount++;
    }

    public static void endCullPass() {
        HiZPyramidBuilder.buildMipChain();
        long elapsedNs = System.nanoTime() - cullPassStartNs;
        ComputeQueueManager.recordCullDurationNs(elapsedNs);
    }

    public static int getPackedSectionsCount() {
        return packedSectionsCount;
    }
}
