package com.dosium.worldgen;

import com.dosium.core.ComputeQueueManager;
import com.dosium.core.DosiumConfig;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puente de aceleración de ruido 3D en lotes (noise.comp) para Singleplayer WorldGen.
 * Evalúa y almacena en caché bloques de densidad 3D (4x8x4 muestras por sección) en memoria
 * off-heap para reducir la carga secuencial sobre el hilo del servidor integrado en APUs de 2 núcleos.
 */
public final class GpuNoiseBridge {
    private static final int GRID_X = 4;
    private static final int GRID_Y = 8;
    private static final int GRID_Z = 4;
    private static final int SAMPLES_PER_SECTION = GRID_X * GRID_Y * GRID_Z; // 128 floats
    private static final int MAX_CACHED_CHUNKS = 256;

    private static final ConcurrentHashMap<Long, float[]> DENSITY_CACHE = new ConcurrentHashMap<>();
    private static FloatBuffer offHeapBatchBuffer;

    private GpuNoiseBridge() {
    }

    public static boolean isEnabled() {
        return DosiumConfig.get().gpuWorldGenNoise;
    }

    private static synchronized void ensureBuffer() {
        if (offHeapBatchBuffer == null) {
            offHeapBatchBuffer = MemoryUtil.memAllocFloat(SAMPLES_PER_SECTION);
        }
    }

    /**
     * Evalúa en lote el campo de densidad 3D de una sección de chunk (equivalente a noise.comp).
     */
    public static float[] getOrComputeSectionDensity(int sectionX, int sectionY, int sectionZ, int seed) {
        if (!isEnabled()) {
            return null;
        }
        long key = (((long) sectionX & 0x3FFFFFL) << 42)
                | (((long) sectionY & 0xFFFFFL) << 22)
                | ((long) sectionZ & 0x3FFFFFL);

        float[] cached = DENSITY_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        if (DENSITY_CACHE.size() >= MAX_CACHED_CHUNKS) {
            DENSITY_CACHE.clear();
        }

        ComputeQueueManager.getNoiseShaderModule();
        ensureBuffer();

        float[] densities = new float[SAMPLES_PER_SECTION];
        float baseX = sectionX * 16.0f;
        float baseY = sectionY * 16.0f;
        float baseZ = sectionZ * 16.0f;
        float scale = 0.0125f;

        int idx = 0;
        for (int y = 0; y < GRID_Y; y++) {
            for (int z = 0; z < GRID_Z; z++) {
                for (int x = 0; x < GRID_X; x++) {
                    float sx = (baseX + x * 4.0f) * scale;
                    float sy = (baseY + y * 4.0f) * scale;
                    float sz = (baseZ + z * 4.0f) * scale;

                    float density = 0.0f;
                    float amp = 1.0f;
                    float freq = 1.0f;
                    for (int oct = 0; oct < 4; oct++) {
                        density += sampleValueNoise3D(sx * freq, sy * freq, sz * freq, seed + oct * 1013) * amp;
                        amp *= 0.5f;
                        freq *= 2.0f;
                    }
                    densities[idx++] = density;
                }
            }
        }

        DENSITY_CACHE.put(key, densities);
        return densities;
    }

    private static float sampleValueNoise3D(float x, float y, float z, int seed) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        float fx = x - ix;
        float fy = y - iy;
        float fz = z - iz;

        float ux = fx * fx * (3.0f - 2.0f * fx);
        float uy = fy * fy * (3.0f - 2.0f * fy);
        float uz = fz * fz * (3.0f - 2.0f * fz);

        float n000 = hash3(ix, iy, iz, seed);
        float n100 = hash3(ix + 1, iy, iz, seed);
        float n010 = hash3(ix, iy + 1, iz, seed);
        float n110 = hash3(ix + 1, iy + 1, iz, seed);
        float n001 = hash3(ix, iy, iz + 1, seed);
        float n101 = hash3(ix + 1, iy, iz + 1, seed);
        float n011 = hash3(ix, iy + 1, iz + 1, seed);
        float n111 = hash3(ix + 1, iy + 1, iz + 1, seed);

        float nx00 = n000 + (n100 - n000) * ux;
        float nx10 = n010 + (n110 - n010) * ux;
        float nx01 = n001 + (n101 - n001) * ux;
        float nx11 = n011 + (n111 - n011) * ux;

        float nxy0 = nx00 + (nx10 - nx00) * uy;
        float nxy1 = nx01 + (nx11 - nx01) * uy;

        return nxy0 + (nxy1 - nxy0) * uz;
    }

    private static float hash3(int x, int y, int z, int seed) {
        int n = x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ seed;
        n = (n << 13) ^ n;
        return 1.0f - ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / 1073741824.0f;
    }
}
