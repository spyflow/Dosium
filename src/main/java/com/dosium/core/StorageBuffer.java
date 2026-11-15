package com.dosium.core;

import net.vulkanmod.vulkan.memory.Buffer;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;

/**
 * Buffer Vulkan para SSBOs (Shader Storage Buffer Objects) e Indirect Buffers de Dosium.
 * Utiliza memoria mapeable HOST_MEM con acceso Zero-Copy directo en memoria mapeada
 * (data pointer de LWJGL PointerBuffer en Buffer).
 */
public class StorageBuffer extends Buffer {

    public StorageBuffer(int size, int additionalUsage, MemoryType memoryType) {
        super(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | additionalUsage,
                memoryType != null ? memoryType : MemoryTypes.HOST_MEM);
        this.createBuffer(size);
    }

    public StorageBuffer(int size, int additionalUsage) {
        this(size, additionalUsage, MemoryTypes.HOST_MEM);
    }

    public StorageBuffer(int size) {
        this(size, 0, MemoryTypes.HOST_MEM);
    }

    public void uploadData(ByteBuffer src, int dstOffset, int size) {
        if (src == null || size <= 0) {
            return;
        }
        long dataPtr = getDataPointer();
        if (dataPtr != 0L) {
            long srcPtr = MemoryUtil.memAddress(src);
            if (srcPtr != 0L) {
                MemoryUtil.memCopy(srcPtr, dataPtr + dstOffset, size);
                this.usedBytes = Math.max(this.usedBytes, dstOffset + size);
            }
        }
    }

    public void uploadData(ByteBuffer src, int size) {
        uploadData(src, 0, size);
    }

    public void uploadInt(int byteOffset, int value) {
        long dataPtr = getDataPointer();
        if (dataPtr != 0L) {
            MemoryUtil.memPutInt(dataPtr + byteOffset, value);
            this.usedBytes = Math.max(this.usedBytes, byteOffset + 4);
        }
    }

    public long getDataPointer() {
        if (this.data != null && this.data.capacity() > 0) {
            return this.data.get(0);
        }
        return 0L;
    }

    public void free() {
        this.freeBuffer();
    }
}
