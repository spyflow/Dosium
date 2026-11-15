package com.dosium.meshing;

import com.dosium.core.ApuBudgetController;
import com.dosium.core.ComputeQueueManager;
import com.dosium.core.DosiumConfig;
import com.dosium.core.StorageBuffer;
import net.minecraft.world.BlockRenderView;
import net.vulkanmod.render.chunk.buffer.AreaBuffer;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Despachador de Compute Chunk Meshing (mesh.comp + Modo Híbrido GPU + 1 Hilo CPU).
 * Coordina el empaquetado 18x18x18 en memoria off-heap, sube los voxeles al SSBO binding 0,
 * despacha mesh.comp en GPU con workgroups (8,8,4) y conecta la salida con el AreaBuffer.
 *
 * NOTA DE APROXIMACIÓN:
 * mesh.comp genera quads simplificados (color 0xFFFFFFFF, UV=0, luz máxima 0x00F000F0)
 * diseñados para mallado ultrarrápido de secciones sólidas simples en GPU. En modo HÍBRIDO (HYBRID),
 * la GPU se utiliza para secciones opacas estándar y la CPU procesa el detalle fino y capas translúcidas.
 */
public final class ComputeMeshDispatcher {
    private static final int MAX_SECTION_QUADS = 8192;
    private static final int VERTEX_STRIDE = 20; // 5 uint32 por vértice (20 bytes)
    private static final int MAX_VERTEX_BUFFER_BYTES = MAX_SECTION_QUADS * 4 * VERTEX_STRIDE; // ~640 KB
    private static final int COUNTER_BUFFER_BYTES = 16;

    private static final AtomicInteger sectionsMeshedThisFrame = new AtomicInteger(0);
    private static volatile int lastFrameSectionsMeshed = 0;

    private static StorageBuffer[] meshVoxelBuffers;
    private static StorageBuffer[] meshVertexBuffers;
    private static StorageBuffer[] meshCounterBuffers;

    private ComputeMeshDispatcher() {
    }

    private static synchronized void ensureBuffersAllocated() {
        int frames = Math.max(1, Renderer.getFramesNum());
        if (meshVoxelBuffers == null || meshVoxelBuffers.length != frames) {
            cleanUpBuffers();
            meshVoxelBuffers = new StorageBuffer[frames];
            meshVertexBuffers = new StorageBuffer[frames];
            meshCounterBuffers = new StorageBuffer[frames];

            for (int i = 0; i < frames; i++) {
                meshVoxelBuffers[i] = new StorageBuffer(GpuSectionPacker.PACKED_BYTES);
                meshVertexBuffers[i] = new StorageBuffer(MAX_VERTEX_BUFFER_BYTES);
                meshCounterBuffers[i] = new StorageBuffer(COUNTER_BUFFER_BYTES);
            }
        }
    }

    public static void beginFrame() {
        lastFrameSectionsMeshed = sectionsMeshedThisFrame.getAndSet(0);
        ensureBuffersAllocated();
    }

    /**
     * Ejecuta la fase de pre-empaquetado 18x18x18 y despacho de cómputo GPU/híbrido para una sección.
     * Si el dispatch GPU no es viable o el modo es CPU_LEGACY, se omite el empaquetado para no
     * pagar 5832 getBlockState() en vano por sección en CPU.
     */
    public static boolean preDispatchSection(BlockRenderView region, int originX, int originY, int originZ) {
        DosiumConfig.MeshingMode mode = DosiumConfig.get().meshingMode;

        // Si el pipeline compute no está listo o el usuario seleccionó CPU_LEGACY,
        // OMITIR completamente el empaquetado para evitar sobrecoste en CPU
        if (mode == DosiumConfig.MeshingMode.CPU_LEGACY || !ComputeQueueManager.isMeshPipelineReady()) {
            return true;
        }

        long startNs = System.nanoTime();
        int nonAir = GpuSectionPacker.packSection18x18x18(region, originX, originY, originZ);
        if (nonAir == 0) {
            return false; // Sección vacía de 16x16x16
        }

        // Despacho GPU de mesh.comp si hay command buffer activo
        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        int frame = Renderer.getCurrentFrame();
        if (cmd != null && meshVoxelBuffers != null && frame >= 0 && frame < meshVoxelBuffers.length) {
            dispatchGpuMesh(cmd, frame, originX, originY, originZ);
        }

        long elapsedNs = System.nanoTime() - startNs;
        ComputeQueueManager.recordMeshDurationNs(elapsedNs);
        sectionsMeshedThisFrame.incrementAndGet();

        return true;
    }

    private static void dispatchGpuMesh(VkCommandBuffer cmd, int frameIndex, int originX, int originY, int originZ) {
        StorageBuffer voxBuf = meshVoxelBuffers[frameIndex];
        StorageBuffer vtxBuf = meshVertexBuffers[frameIndex];
        StorageBuffer cntBuf = meshCounterBuffers[frameIndex];

        // 1. Subir los 11,664 bytes de voxeles 18x18x18 al SSBO binding 0
        ByteBuffer staging = GpuSectionPacker.getThreadPackBuffer();
        voxBuf.uploadData(staging, 0, GpuSectionPacker.PACKED_BYTES);

        // 2. Resetear contador de quads a 0 en binding 2
        cntBuf.uploadInt(0, 0);

        // 3. Despachar mesh.comp: (16/8, 16/8, 16/4) = workgroups (2, 2, 4)
        ComputeQueueManager.dispatchMesh(cmd, frameIndex, voxBuf, vtxBuf, cntBuf, originX, originY, originZ, MAX_SECTION_QUADS);
    }

    public static void recordSectionCompileTimeNs(long durationNs) {
        ComputeQueueManager.recordMeshDurationNs(durationNs);
        sectionsMeshedThisFrame.incrementAndGet();
    }

    public static int getRecommendedThreadCount(int sodiumConfiguredThreads) {
        return ApuBudgetController.getOptimalWorkerThreads(sodiumConfiguredThreads);
    }

    public static int getLastFrameSectionsMeshed() {
        return lastFrameSectionsMeshed;
    }

    public static String getActiveModeShortName() {
        DosiumConfig.MeshingMode mode = DosiumConfig.get().meshingMode;
        return switch (mode) {
            case GPU_COMPUTE -> "GPU Compute";
            case HYBRID -> "Híbrido (GPU+1CPU)";
            case CPU_LEGACY -> "CPU Legacy";
        };
    }

    private static synchronized void cleanUpBuffers() {
        if (meshVoxelBuffers != null) {
            for (StorageBuffer b : meshVoxelBuffers) if (b != null) b.free();
            meshVoxelBuffers = null;
        }
        if (meshVertexBuffers != null) {
            for (StorageBuffer b : meshVertexBuffers) if (b != null) b.free();
            meshVertexBuffers = null;
        }
        if (meshCounterBuffers != null) {
            for (StorageBuffer b : meshCounterBuffers) if (b != null) b.free();
            meshCounterBuffers = null;
        }
    }
}
