package com.dosium.core;

import net.minecraft.client.MinecraftClient;

/**
 * Regulador de Presupuesto para APUs con Memoria Unificada (APU Guardrail).
 * Monitorea el tiempo real por fotograma (Render + GPU Cull + GPU Mesh) frente al
 * presupuesto objetivo según el límite de FPS o el perfil seleccionado,
 * ajustando dinámicamente la cuota de secciones enviadas a meshing y subida por frame.
 */
public final class ApuBudgetController {
    private static final float THERMAL_THROTTLE_THRESHOLD = 0.80f; // 80% del tiempo objetivo del frame
    private static volatile float targetFrameMs = 16.67f;
    private static volatile float budgetUsageRatio = 0.35f;
    private static volatile int maxRebuildsPerFrame = 6;
    private static volatile int maxUploadsPerFrame = 6;
    private static volatile long scheduleBudgetNs = 1_500_000L;
    private static volatile long uploadBudgetNs = 1_750_000L;

    private ApuBudgetController() {
    }

    public static void updateFrameBudget() {
        ComputeQueueManager.beginFrame();
        UmaBufferBridge.endFrame();

        DosiumConfig config = DosiumConfig.get();
        DosiumConfig.ApuThermalMode thermalMode = config.apuThermalBudget;

        int maxFps = 120;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.options != null) {
                int configuredFps = client.options.getMaxFps().getValue();
                if (configuredFps > 0 && configuredFps < 260) {
                    maxFps = configuredFps;
                }
            }
        } catch (Throwable ignored) {
        }

        if (thermalMode == DosiumConfig.ApuThermalMode.CONSERVATIVE_15W) {
            targetFrameMs = Math.max(1000.0f / Math.min(maxFps, 90), 11.11f);
        } else if (thermalMode == DosiumConfig.ApuThermalMode.MAX_PERFORMANCE) {
            targetFrameMs = 1000.0f / Math.max(maxFps, 144);
        } else {
            targetFrameMs = 1000.0f / Math.max(30, maxFps);
        }

        float computeOverheadMs = ComputeQueueManager.getGpuCullMs() + ComputeQueueManager.getGpuMeshMs();
        // Estimación del trabajo activo de GPU/APU en el fotograma (sin contar la espera del limitador de FPS)
        float baseRenderWorkMs = 2.2f;
        float effectiveMs = baseRenderWorkMs + (computeOverheadMs * 2.0f);

        if (targetFrameMs > 0.1f) {
            float rawRatio = effectiveMs / targetFrameMs;
            budgetUsageRatio = budgetUsageRatio * 0.85f + Math.min(1.00f, Math.max(0.08f, rawRatio)) * 0.15f;
        }

        // Balanceo dinámico si el uso supera el 80% del presupuesto del frame
        if (thermalMode == DosiumConfig.ApuThermalMode.MAX_PERFORMANCE) {
            if (budgetUsageRatio > 0.95f) {
                maxRebuildsPerFrame = 4;
                maxUploadsPerFrame = 5;
                scheduleBudgetNs = 1_250_000L;
                uploadBudgetNs = 1_500_000L;
            } else {
                maxRebuildsPerFrame = 10;
                maxUploadsPerFrame = 10;
                scheduleBudgetNs = 2_250_000L;
                uploadBudgetNs = 2_500_000L;
            }
        } else if (thermalMode == DosiumConfig.ApuThermalMode.CONSERVATIVE_15W) {
            if (budgetUsageRatio > THERMAL_THROTTLE_THRESHOLD) {
                maxRebuildsPerFrame = 2;
                maxUploadsPerFrame = 3;
                scheduleBudgetNs = 750_000L;
                uploadBudgetNs = 900_000L;
            } else {
                maxRebuildsPerFrame = 4;
                maxUploadsPerFrame = 4;
                scheduleBudgetNs = 1_100_000L;
                uploadBudgetNs = 1_250_000L;
            }
        } else {
            // AUTO_FPS: Recorta progresivamente cuando supera el 80% del presupuesto del frame
            if (budgetUsageRatio > 1.00f) {
                maxRebuildsPerFrame = 2;
                maxUploadsPerFrame = 3;
                scheduleBudgetNs = 800_000L;
                uploadBudgetNs = 1_000_000L;
            } else if (budgetUsageRatio > THERMAL_THROTTLE_THRESHOLD) {
                maxRebuildsPerFrame = 3;
                maxUploadsPerFrame = 4;
                scheduleBudgetNs = 1_100_000L;
                uploadBudgetNs = 1_250_000L;
            } else {
                maxRebuildsPerFrame = 6;
                maxUploadsPerFrame = 6;
                scheduleBudgetNs = 1_500_000L;
                uploadBudgetNs = 1_750_000L;
            }
        }
    }

    public static int getMaxRebuildsPerFrame() {
        return maxRebuildsPerFrame;
    }

    public static int getMaxUploadsPerFrame() {
        return maxUploadsPerFrame;
    }

    public static long getScheduleBudgetNs() {
        return scheduleBudgetNs;
    }

    public static long getUploadBudgetNs() {
        return uploadBudgetNs;
    }

    public static int getBudgetUsagePercent() {
        return Math.min(100, Math.max(1, Math.round(budgetUsageRatio * 100.0f)));
    }

    public static boolean isThrottlingActive() {
        return budgetUsageRatio > THERMAL_THROTTLE_THRESHOLD;
    }

    public static float getTargetFrameMs() {
        return targetFrameMs;
    }

    public static int getOptimalWorkerThreads(int configuredThreads) {
        DosiumConfig.MeshingMode mode = DosiumConfig.get().meshingMode;
        if (mode == DosiumConfig.MeshingMode.GPU_COMPUTE || mode == DosiumConfig.MeshingMode.HYBRID) {
            // En APUs de 2 núcleos (ej. Athlon Silver 3050U), 1 hilo auxiliar evita estrangular el hilo de render
            int cores = Runtime.getRuntime().availableProcessors();
            if (cores <= 2) {
                return 1;
            }
            return configuredThreads > 0 ? Math.min(configuredThreads, Math.max(1, cores / 2)) : 1;
        }
        if (configuredThreads > 0) {
            return configuredThreads;
        }
        return Math.max((Runtime.getRuntime().availableProcessors() - 1) / 2, 1);
    }
}
