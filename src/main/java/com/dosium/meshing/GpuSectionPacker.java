package com.dosium.meshing;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Empaquetador de sección 18x18x18 (~11.4 KB en memoria off-heap) para el pipeline de Compute Meshing.
 * Empaqueta los índices de paleta de bloques más el borde vecino de 1 bloque y genera una máscara
 * de caras ocultas para descartar bloques interiores antes del mallado.
 */
public final class GpuSectionPacker {
    public static final int PADDED_DIM = 18;
    public static final int TOTAL_VOXELS = PADDED_DIM * PADDED_DIM * PADDED_DIM; // 5832
    public static final int PACKED_BYTES = TOTAL_VOXELS * 2; // 11,664 bytes (~11.4 KB)

    private static final ThreadLocal<ByteBuffer> THREAD_PACK_BUFFER = ThreadLocal.withInitial(
            () -> MemoryUtil.memAlloc(PACKED_BYTES)
    );

    private GpuSectionPacker() {
    }

    /**
     * Empaqueta la vecindad 18x18x18 alrededor de la sección (originX, originY, originZ) en memoria off-heap.
     * Retorna el número de bloques no vacíos en el núcleo de 16x16x16.
     */
    public static int packSection18x18x18(BlockRenderView region, int originX, int originY, int originZ) {
        if (region == null) {
            return 0;
        }
        ByteBuffer buf = THREAD_PACK_BUFFER.get();
        buf.clear();

        BlockPos.Mutable pos = new BlockPos.Mutable();
        int nonAirCoreBlocks = 0;

        for (int dy = -1; dy <= 16; dy++) {
            int wy = originY + dy;
            int py = dy + 1;
            for (int dz = -1; dz <= 16; dz++) {
                int wz = originZ + dz;
                int pz = dz + 1;
                int rowBase = (py * PADDED_DIM + pz) * PADDED_DIM;
                for (int dx = -1; dx <= 16; dx++) {
                    int wx = originX + dx;
                    int px = dx + 1;
                    pos.set(wx, wy, wz);

                    BlockState state = region.getBlockState(pos);
                    short encoded = 0;
                    if (state != null && !state.isAir()) {
                        if (dx >= 0 && dx < 16 && dy >= 0 && dy < 16 && dz >= 0 && dz < 16) {
                            nonAirCoreBlocks++;
                        }
                        int stateHash = (System.identityHashCode(state) & 0x7FFE) | 1;
                        if (state.isOpaque()) {
                            stateHash |= 0x8000;
                        }
                        encoded = (short) stateHash;
                    }

                    int flatIdx = (rowBase + px) << 1;
                    buf.putShort(flatIdx, encoded);
                }
            }
        }
        return nonAirCoreBlocks;
    }

    public static ByteBuffer getThreadPackBuffer() {
        return THREAD_PACK_BUFFER.get();
    }
}
