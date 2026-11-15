package com.dosium.core;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.Buffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Puente Zero-Copy UMA hacia los buffers nativos (AreaBuffer / IndirectBuffer) de VulkanMod.
 * En iGPUs/APUs con memoria unificada (Radeon Vega/RDNA, Intel UHD/Iris), escribe directamente
 * sobre memoria HOST_VISIBLE | HOST_COHERENT sin pasar por StagingBuffer ni TransferQueue.
 */
public final class UmaBufferBridge {
    private static volatile boolean detectedApuUma = false;
    private static volatile long umaHeapSizeMB = 0L;
    private static volatile long carveoutHeapSizeMB = 0L;
    private static final AtomicLong zeroCopyBytesThisFrame = new AtomicLong(0L);
    private static volatile long lastFrameZeroCopyKB = 0L;

    private UmaBufferBridge() {
    }

    public static void onMemoryTypesInitialized(boolean isApuUma, long umaSizeMB, long carveoutSizeMB) {
        detectedApuUma = isApuUma;
        umaHeapSizeMB = umaSizeMB;
        carveoutHeapSizeMB = carveoutSizeMB;
    }

    public static boolean isHardwareUmaOrApu() {
        if (detectedApuUma) {
            return true;
        }
        try {
            if (DeviceManager.deviceProperties != null) {
                int devType = DeviceManager.deviceProperties.deviceType();
                if (devType == VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isZeroCopyEnabled() {
        DosiumConfig.UmaMemoryMode mode = DosiumConfig.get().umaZeroCopy;
        if (mode == DosiumConfig.UmaMemoryMode.DISABLED) {
            return false;
        }
        if (mode == DosiumConfig.UmaMemoryMode.ENABLED) {
            return true;
        }
        return isHardwareUmaOrApu();
    }

    public static boolean tryZeroCopyUpload(Buffer dstBuffer, long dstOffset, long size, ByteBuffer src) {
        if (!isZeroCopyEnabled() || dstBuffer == null || src == null || size <= 0L) {
            return false;
        }
        long dstBasePtr = MemoryTypes.getBufferDataAddress(dstBuffer);
        if (dstBasePtr == 0L) {
            return false;
        }
        if (dstOffset < 0L || (dstOffset + size) > dstBuffer.getBufferSize()) {
            return false;
        }
        long srcAddress = MemoryUtil.memAddress(src);
        if (srcAddress == 0L) {
            return false;
        }

        MemoryUtil.memCopy(srcAddress, dstBasePtr + dstOffset, size);
        zeroCopyBytesThisFrame.addAndGet(size);
        return true;
    }

    public static boolean tryZeroCopyBufferToBuffer(Buffer srcBuffer, int srcOffset, Buffer dstBuffer, int dstOffset, int size) {
        if (!isZeroCopyEnabled() || srcBuffer == null || dstBuffer == null || size <= 0) {
            return false;
        }
        long srcBasePtr = MemoryTypes.getBufferDataAddress(srcBuffer);
        long dstBasePtr = MemoryTypes.getBufferDataAddress(dstBuffer);
        if (srcBasePtr == 0L || dstBasePtr == 0L) {
            return false;
        }
        if (srcOffset < 0 || (srcOffset + size) > srcBuffer.getBufferSize()) {
            return false;
        }
        if (dstOffset < 0 || (dstOffset + size) > dstBuffer.getBufferSize()) {
            return false;
        }

        MemoryUtil.memCopy(srcBasePtr + srcOffset, dstBasePtr + dstOffset, size);
        zeroCopyBytesThisFrame.addAndGet(size);
        return true;
    }

    public static void endFrame() {
        long bytes = zeroCopyBytesThisFrame.getAndSet(0L);
        lastFrameZeroCopyKB = bytes / 1024L;
    }

    public static long getLastFrameZeroCopyKB() {
        return lastFrameZeroCopyKB;
    }

    public static long getUmaHeapSizeMB() {
        return umaHeapSizeMB;
    }

    public static long getCarveoutHeapSizeMB() {
        return carveoutHeapSizeMB;
    }

    public static String getStatusText() {
        boolean active = isZeroCopyEnabled() && MemoryTypes.isGpuMemMappable();
        if (active) {
            return "UMA Zero-Copy (" + (umaHeapSizeMB > 0 ? umaHeapSizeMB + " MB" : "Activo") + ")";
        }
        return "Staging Legacy";
    }
}
