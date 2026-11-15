package com.dosium.culling;

import com.dosium.core.ComputeQueueManager;
import com.dosium.core.DosiumConfig;

import java.util.Arrays;

/**
 * Generador y evaluador de la Pirámide de Profundidad Jerárquica (Hi-Z Pyramid).
 * Mantiene una cuadrícula conservadora de oclusión de profundidad en espacio de cámara
 * para descartar cuevas y secciones distantes ocultas detrás de terreno sólido cercano.
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

    private HiZPyramidBuilder() {
    }

    public static void beginFrame(double cameraX, double cameraY, double cameraZ) {
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        hiZOccludedCount = 0;
        Arrays.fill(depthGridMip0, Float.POSITIVE_INFINITY);
        Arrays.fill(depthGridMip1, Float.POSITIVE_INFINITY);
    }

    /**
     * Registra un bloque/sección opaco cercano como oclusor conservador en el mip 0 de la pirámide.
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
     * Construye el mip 1 reduciendo 2x2 celdas del mip 0 (equivalente a hiz_downsample.comp).
     */
    public static void buildMipChain() {
        ComputeQueueManager.getHiZShaderModule();
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
     * Evalúa si una sección AABB está completamente oculta detrás de la pirámide Hi-Z.
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

    public static int getHiZOccludedCount() {
        return hiZOccludedCount;
    }

    private static int toAzimuthBin(double dx, double dz) {
        double angle = Math.atan2(dz, dx); // [-pi, pi]
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
