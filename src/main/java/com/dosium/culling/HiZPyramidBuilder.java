package com.dosium.culling;

import com.dosium.core.ComputeQueueManager;
import com.dosium.core.DosiumConfig;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Arrays;

/**
 * Generador y evaluador de la Pirámide de Profundidad Jerárquica (Hi-Z Pyramid).
 * Implementa la reducción 2x2 en GPU mediante hiz_downsample.comp cuando el pipeline compute
 * está disponible, manteniendo un fallback conservador en CPU sobre depthGridMip0/Mip1
 * para compatibilidad total ante fallos.
 */
public final class HiZPyramidBuilder {
    private static final int AZIMUTH_BINS = 64;
    private static final int ELEVATION_BINS = 32;
    private static final float[] depthGridMip0 = new float[AZIMUTH_BINS * ELEVATION_BINS];
    private static final float[] depthGridMip1 = new float[(AZIMUTH_BINS / 2) * (ELEVATION_BINS / 2)];

    private static double camX;
    private static double camY;
    private static double camZ;
    private static int hiZOccludedCount = 0;

    // Recursos GPU Hi-Z Pyramid
    private static VulkanImage hizPyramidImage;
    private static int hizWidth = 512;
    private static int hizHeight = 256;
    private static int maxMipLevel = 8; // log2(512) - 1

    private HiZPyramidBuilder() {
    }

    private static synchronized void ensureGpuPyramidAllocated() {
        if (hizPyramidImage == null && ComputeQueueManager.isHizPipelineReady()) {
            try {
                // Crear textura de profundidad R32F con mip chain completa para Hi-Z
                maxMipLevel = 31 - Integer.numberOfLeadingZeros(Math.max(hizWidth, hizHeight));
                hizPyramidImage = VulkanImage.createDepthImage(
                        hizWidth, hizHeight,
                        VK10.VK_FORMAT_R32_SFLOAT,
                        VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                        false, false
                );
            } catch (Throwable t) {
                // Si falla la asignación de imagen GPU, continuar con el fallback CPU
                hizPyramidImage = null;
            }
        }
    }

    public static void beginFrame(double cameraX, double cameraY, double cameraZ) {
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        hiZOccludedCount = 0;

        // Limpiar cuadrícula de oclusión CPU (usada como fallback)
        Arrays.fill(depthGridMip0, Float.POSITIVE_INFINITY);
        Arrays.fill(depthGridMip1, Float.POSITIVE_INFINITY);

        ensureGpuPyramidAllocated();
    }

    /**
     * Registra un bloque/sección opaco cercano como oclusor conservador en el mip 0 de la pirámide CPU.
     */
    public static void registerOccluderSection(int secX, int secY, int secZ, byte visibilityDirs) {
        // Solo secciones completamente opacas en todas las direcciones (visibilityDirs == 0) actúan como oclusor duro
        if (visibilityDirs != 0) {
            return;
        }
        double cx = (secX + 8.0) - camX;
        double cy = (secY + 8.0) - camY;
        double cz = (secZ + 8.0) - camZ;
        double distSq = cx * cx + cy * cy + cz * cz;
        if (distSq < 256.0 || distSq > 65536.0) {
            return;
        }

        float dist = (float) Math.sqrt(distSq);
        int az = toAzimuthBin(cx, cz);
        int el = toElevationBin(cy, dist);
        int idx = el * AZIMUTH_BINS + az;
        if (dist < depthGridMip0[idx]) {
            depthGridMip0[idx] = dist;
        }
    }

    /**
     * Construye la pirámide Hi-Z.
     * En GPU: despacha hiz_downsample.comp nivel por nivel reduciendo la resolución 2x2.
     * En CPU (fallback): calcula la reducción 2x2 sobre depthGridMip0/depthGridMip1.
     */
    public static void buildMipChain() {
        if (ComputeQueueManager.isHizPipelineReady() && hizPyramidImage != null) {
            VkCommandBuffer cmd = Renderer.getCommandBuffer();
            if (cmd != null) {
                // Despacho de hiz_downsample.comp nivel a nivel
                int levels = Math.min(maxMipLevel, hizPyramidImage.getLevelImageViews() != null ? hizPyramidImage.getLevelImageViews().length : 0);
                for (int level = 1; level < levels; level++) {
                    int srcW = Math.max(1, hizWidth >> (level - 1));
                    int srcH = Math.max(1, hizHeight >> (level - 1));
                    int dstW = Math.max(1, hizWidth >> level);
                    int dstH = Math.max(1, hizHeight >> level);

                    long srcView = hizPyramidImage.getLevelImageView(level - 1);
                    long dstView = hizPyramidImage.getLevelImageView(level);
                    long sampler = hizPyramidImage.getSampler();

                    if (srcView != 0L && dstView != 0L) {
                        ComputeQueueManager.dispatchHiZ(cmd, srcView, sampler, dstView, srcW, srcH, dstW, dstH);
                    }
                }
                return;
            }
        }

        // Fallback en CPU: reducción 2x2 sobre el grid estático
        int mip1W = AZIMUTH_BINS / 2;
        int mip1H = ELEVATION_BINS / 2;
        for (int y = 0; y < mip1H; y++) {
            for (int x = 0; x < mip1W; x++) {
                int srcX = x * 2;
                int srcY = y * 2;
                float d0 = depthGridMip0[srcY * AZIMUTH_BINS + srcX];
                float d1 = depthGridMip0[srcY * AZIMUTH_BINS + (srcX + 1)];
                float d2 = depthGridMip0[(srcY + 1) * AZIMUTH_BINS + srcX];
                float d3 = depthGridMip0[(srcY + 1) * AZIMUTH_BINS + (srcX + 1)];
                depthGridMip1[y * mip1W + x] = Math.max(Math.max(d0, d1), Math.max(d2, d3));
            }
        }
    }

    /**
     * Evalúa si una sección AABB está completamente oculta detrás de la pirámide Hi-Z (CPU fallback).
     */
    public static boolean isOccludedByHiZ(int secX, int secY, int secZ, int directionChanges, int maxDirChanges) {
        if (DosiumConfig.get().gpuIndirectCulling != DosiumConfig.CullingMode.FRUSTUM_HIZ) {
            return false;
        }
        double cx = (secX + 8.0) - camX;
        double cy = (secY + 8.0) - camY;
        double cz = (secZ + 8.0) - camZ;
        double distSq = cx * cx + cy * cy + cz * cz;

        // Nunca descartar secciones cercanas (< 48 bloques) para garantizar cero artefactos visuales
        if (distSq < 2304.0) {
            return false;
        }

        if (directionChanges > maxDirChanges) {
            hiZOccludedCount++;
            return true;
        }

        float dist = (float) Math.sqrt(distSq) - 14.0f;
        int az = toAzimuthBin(cx, cz) / 2;
        int el = toElevationBin(cy, dist) / 2;
        int idx = Math.min((ELEVATION_BINS / 2) - 1, Math.max(0, el)) * (AZIMUTH_BINS / 2)
                + Math.min((AZIMUTH_BINS / 2) - 1, Math.max(0, az));

        float occluderDist = depthGridMip1[idx];
        if (dist > occluderDist + 32.0f && directionChanges >= Math.max(2, maxDirChanges)) {
            hiZOccludedCount++;
            return true;
        }
        return false;
    }

    public static long getHiZImageView() {
        return hizPyramidImage != null ? hizPyramidImage.getImageView() : 0L;
    }

    public static long getHiZSampler() {
        return hizPyramidImage != null ? hizPyramidImage.getSampler() : 0L;
    }

    public static int getMaxMipLevel() {
        return maxMipLevel;
    }

    public static int getHiZOccludedCount() {
        return hiZOccludedCount;
    }

    private static int toAzimuthBin(double dx, double dz) {
        double angle = Math.atan2(dz, dx);
        double norm = (angle + Math.PI) / (2.0 * Math.PI);
        int bin = (int) (norm * AZIMUTH_BINS);
        return Math.min(AZIMUTH_BINS - 1, Math.max(0, bin));
    }

    private static int toElevationBin(double dy, float dist) {
        if (dist <= 0.001f) {
            return ELEVATION_BINS / 2;
        }
        double sinEl = Math.max(-1.0, Math.min(1.0, dy / dist));
        double norm = (sinEl + 1.0) * 0.5;
        int bin = (int) (norm * ELEVATION_BINS);
        return Math.min(ELEVATION_BINS - 1, Math.max(0, bin));
    }
}
