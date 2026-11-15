package com.dosium.meshing;

import com.dosium.core.ApuBudgetController;
import com.dosium.core.ComputeQueueManager;
import com.dosium.core.DosiumConfig;
import net.minecraft.world.BlockRenderView;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Despachador de Compute Chunk Meshing (mesh.comp + Modo Híbrido GPU + 1 Hilo CPU).
 * Coordina el empaquetado 18x18x18 en memoria off-heap, registra el tiempo de mallado
 * en el regulador térmico de APU y ajusta dinámicamente los hilos constructores.
 */
public final class ComputeMeshDispatcher {
    private static final AtomicInteger sectionsMeshedThisFrame = new AtomicInteger(0);
    private static volatile int lastFrameSectionsMeshed = 0;

    private ComputeMeshDispatcher() {
    }

    public static void beginFrame() {
        lastFrameSectionsMeshed = sectionsMeshedThisFrame.getAndSet(0);
    }

    /**
     * Ejecuta la fase de pre-empaquetado 18x18x18 y despacho de cómputo/híbrido para una sección.
     * Retorna false si la sección de 16x16x16 está completamente vacía (permitiendo omitir el mallado).
     */
    public static boolean preDispatchSection(BlockRenderView region, int originX, int originY, int originZ) {
        DosiumConfig.MeshingMode mode = DosiumConfig.get().meshingMode;
        if (mode == DosiumConfig.MeshingMode.CPU_LEGACY) {
            return true;
        }

        long startNs = System.nanoTime();
        ComputeQueueManager.getMeshShaderModule();
        int nonAir = GpuSectionPacker.packSection18x18x18(region, originX, originY, originZ);
        long elapsedNs = System.nanoTime() - startNs;
        ComputeQueueManager.recordMeshDurationNs(elapsedNs);
        sectionsMeshedThisFrame.incrementAndGet();

        return nonAir > 0;
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
}
