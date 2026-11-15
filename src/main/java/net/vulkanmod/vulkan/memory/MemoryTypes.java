package net.vulkanmod.vulkan.memory;

import com.dosium.core.UmaBufferBridge;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkMemoryType;

import java.nio.ByteBuffer;

public class MemoryTypes {
    public static MemoryType GPU_MEM;
    public static MemoryType HOST_MEM;

    public static void createMemoryTypes() {
        VkMemoryType pureDeviceType = null;
        VkMemoryHeap pureDeviceHeap = null;

        VkMemoryType mappableDeviceType = null;
        VkMemoryHeap mappableDeviceHeap = null;

        VkMemoryType cachedHostType = null;
        VkMemoryHeap cachedHostHeap = null;

        VkMemoryType fallbackHostType = null;
        VkMemoryHeap fallbackHostHeap = null;

        int typeCount = DeviceManager.memoryProperties.memoryTypeCount();
        for (int i = 0; i < typeCount; i++) {
            VkMemoryType memoryType = DeviceManager.memoryProperties.memoryTypes(i);
            VkMemoryHeap memoryHeap = DeviceManager.memoryProperties.memoryHeaps(memoryType.heapIndex());
            int flags = memoryType.propertyFlags();

            if (flags == 1) {
                if (pureDeviceHeap == null || memoryHeap.size() > pureDeviceHeap.size()) {
                    pureDeviceType = memoryType;
                    pureDeviceHeap = memoryHeap;
                }
            }
            if ((flags & 3) == 3) {
                if (mappableDeviceHeap == null || memoryHeap.size() > mappableDeviceHeap.size()) {
                    mappableDeviceType = memoryType;
                    mappableDeviceHeap = memoryHeap;
                }
            }
            if (flags == 14) {
                if (cachedHostHeap == null || memoryHeap.size() > cachedHostHeap.size()) {
                    cachedHostType = memoryType;
                    cachedHostHeap = memoryHeap;
                }
            }
            if ((flags & 6) == 6) {
                if (fallbackHostHeap == null || memoryHeap.size() > fallbackHostHeap.size()) {
                    fallbackHostType = memoryType;
                    fallbackHostHeap = memoryHeap;
                }
            }
        }

        if (cachedHostType != null) {
            HOST_MEM = new HostLocalCachedMemory(cachedHostType, cachedHostHeap);
        } else if (fallbackHostType != null) {
            HOST_MEM = new HostLocalFallbackMemory(fallbackHostType, fallbackHostHeap);
        }

        boolean isIntegratedGpu = false;
        try {
            if (DeviceManager.deviceProperties != null) {
                isIntegratedGpu = DeviceManager.deviceProperties.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
            }
        } catch (Throwable ignored) {
        }

        long pureDeviceMB = pureDeviceHeap != null ? pureDeviceHeap.size() / (1024L * 1024L) : 0L;
        long mappableDeviceMB = mappableDeviceHeap != null ? mappableDeviceHeap.size() / (1024L * 1024L) : 0L;
        VkMemoryHeap bestHostHeap = cachedHostHeap != null ? cachedHostHeap : fallbackHostHeap;
        VkMemoryType bestHostType = cachedHostType != null ? cachedHostType : fallbackHostType;
        long hostMB = bestHostHeap != null ? bestHostHeap.size() / (1024L * 1024L) : 0L;

        // En APUs (como AMD Athlon Silver 3050U con 64 MB de carveout BIOS y 6+ GB de RAM UMA compartida),
        // un heap DEVICE_LOCAL <= 512 MB es solo el carveout y agota la VRAM enseguida si no usamos UMA.
        boolean hasTinyCarveout = pureDeviceMB > 0L && pureDeviceMB <= 512L && hostMB > pureDeviceMB * 2L;
        boolean isApuUma = isIntegratedGpu || hasTinyCarveout || pureDeviceType == null;

        if (isApuUma) {
            if (mappableDeviceType != null && mappableDeviceMB > 512L) {
                GPU_MEM = new DeviceMappableMemory(mappableDeviceType, mappableDeviceHeap);
                UmaBufferBridge.onMemoryTypesInitialized(true, mappableDeviceMB, pureDeviceMB);
            } else if (bestHostType != null && bestHostHeap != null) {
                GPU_MEM = new UmaUnifiedMemory(bestHostType, bestHostHeap);
                UmaBufferBridge.onMemoryTypesInitialized(true, hostMB, pureDeviceMB);
            } else if (mappableDeviceType != null) {
                GPU_MEM = new DeviceMappableMemory(mappableDeviceType, mappableDeviceHeap);
                UmaBufferBridge.onMemoryTypesInitialized(true, mappableDeviceMB, pureDeviceMB);
            } else if (pureDeviceType != null) {
                GPU_MEM = new DeviceLocalMemory(pureDeviceType, pureDeviceHeap);
                UmaBufferBridge.onMemoryTypesInitialized(false, pureDeviceMB, pureDeviceMB);
            }
        } else {
            if (pureDeviceType != null) {
                GPU_MEM = new DeviceLocalMemory(pureDeviceType, pureDeviceHeap);
            } else if (mappableDeviceType != null) {
                GPU_MEM = new DeviceMappableMemory(mappableDeviceType, mappableDeviceHeap);
            } else {
                GPU_MEM = HOST_MEM;
            }
            UmaBufferBridge.onMemoryTypesInitialized(false, pureDeviceMB, pureDeviceMB);
        }

        if (HOST_MEM == null) {
            HOST_MEM = GPU_MEM;
        }
        if (GPU_MEM == null) {
            GPU_MEM = HOST_MEM;
        }
    }

    public static boolean isGpuMemMappable() {
        return GPU_MEM != null && GPU_MEM.mappable();
    }

    public static long getBufferDataAddress(Buffer buffer) {
        if (buffer == null || buffer.data == null) {
            return 0L;
        }
        try {
            return buffer.data.get(0);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static class DeviceLocalMemory extends MemoryType {
        DeviceLocalMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(MemoryType.Type.DEVICE_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        void createBuffer(Buffer buffer, int size) {
            MemoryManager.getInstance().createBuffer(buffer, size, 3 | buffer.usage, 1);
        }

        @Override
        void copyToBuffer(Buffer buffer, long bufferSize, ByteBuffer byteBuffer) {
            StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
            stagingBuffer.copyBuffer((int) bufferSize, byteBuffer);
            DeviceManager.getTransferQueue().copyBufferCmd(
                    stagingBuffer.id,
                    stagingBuffer.offset,
                    buffer.getId(),
                    buffer.getUsedBytes(),
                    bufferSize
            );
        }

        @Override
        void copyFromBuffer(Buffer buffer, long bufferSize, ByteBuffer byteBuffer) {
        }

        public long copyBuffer(Buffer src, Buffer dst) {
            if (dst.bufferSize < src.bufferSize) {
                throw new IllegalArgumentException("dst size is less than src size.");
            }
            return DeviceManager.getTransferQueue().copyBufferCmd(
                    src.getId(),
                    0L,
                    dst.getId(),
                    0L,
                    src.bufferSize
            );
        }

        @Override
        boolean mappable() {
            return false;
        }
    }

    static abstract class MappableMemory extends MemoryType {
        MappableMemory(MemoryType.Type type, VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(type, vkMemoryType, vkMemoryHeap);
        }

        @Override
        void copyToBuffer(Buffer buffer, long bufferSize, ByteBuffer byteBuffer) {
            VUtil.memcpy(
                    byteBuffer,
                    buffer.data.getByteBuffer(0, buffer.bufferSize),
                    (int) bufferSize,
                    buffer.getUsedBytes()
            );
        }

        @Override
        void copyFromBuffer(Buffer buffer, long bufferSize, ByteBuffer byteBuffer) {
            VUtil.memcpy(
                    buffer.data.getByteBuffer(0, buffer.bufferSize),
                    byteBuffer,
                    0L
            );
        }

        @Override
        boolean mappable() {
            return true;
        }
    }

    static class DeviceMappableMemory extends MappableMemory {
        DeviceMappableMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(MemoryType.Type.DEVICE_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        void createBuffer(Buffer buffer, int size) {
            MemoryManager.getInstance().createBuffer(buffer, size, 3 | buffer.usage, 3);
        }
    }

    /**
     * Memoria Unificada Zero-Copy (UMA) para APUs con carveout pequeño (ej. 64 MB BIOS VRAM).
     * Usa el heap compartido de varios GB con seguimiento simétrico en MemoryManager.
     */
    static class UmaUnifiedMemory extends MappableMemory {
        private final int requiredPropertyFlags;

        UmaUnifiedMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(MemoryType.Type.HOST_LOCAL, vkMemoryType, vkMemoryHeap);
            int flags = vkMemoryType.propertyFlags();
            this.requiredPropertyFlags = (flags & 6) != 0 ? (flags & 14) : 6;
        }

        @Override
        void createBuffer(Buffer buffer, int size) {
            MemoryManager.getInstance().createBuffer(buffer, size, 3 | buffer.usage, this.requiredPropertyFlags);
        }
    }

    static class HostLocalCachedMemory extends MappableMemory {
        HostLocalCachedMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(MemoryType.Type.HOST_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        void createBuffer(Buffer buffer, int size) {
            MemoryManager.getInstance().createBuffer(buffer, size, 3 | buffer.usage, 14);
        }

        void copyToBuffer(Buffer buffer, long dstOffset, long bufferSize, ByteBuffer byteBuffer) {
            VUtil.memcpy(
                    byteBuffer,
                    buffer.data.getByteBuffer(0, buffer.bufferSize),
                    (int) bufferSize,
                    dstOffset
            );
        }

        void copyBuffer(Buffer src, Buffer dst) {
            VUtil.memcpy(
                    src.data.getByteBuffer(0, src.bufferSize),
                    dst.data.getByteBuffer(0, dst.bufferSize),
                    src.bufferSize,
                    0L
            );
        }
    }

    static class HostLocalFallbackMemory extends MappableMemory {
        HostLocalFallbackMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(MemoryType.Type.HOST_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        void createBuffer(Buffer buffer, int size) {
            MemoryManager.getInstance().createBuffer(buffer, size, 3 | buffer.usage, 6);
        }
    }
}
