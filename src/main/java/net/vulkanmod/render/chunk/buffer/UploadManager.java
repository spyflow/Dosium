package net.vulkanmod.render.chunk.buffer;

import com.dosium.core.UmaBufferBridge;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.Buffer;
import net.vulkanmod.vulkan.memory.StagingBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.queue.TransferQueue;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;

public class UploadManager {
    public static UploadManager INSTANCE;

    Queue queue = DeviceManager.getTransferQueue();
    CommandPool.CommandBuffer commandBuffer;
    LongOpenHashSet dstBuffers = new LongOpenHashSet();

    public static void createInstance() {
        INSTANCE = new UploadManager();
    }

    public void submitUploads() {
        if (this.commandBuffer == null) {
            return;
        }
        this.queue.submitCommands(this.commandBuffer);
        Synchronization.INSTANCE.addCommandBuffer(this.commandBuffer);
        this.commandBuffer = null;
        this.dstBuffers.clear();
    }

    public void recordUpload(Buffer buffer, long dstOffset, long bufferSize, ByteBuffer src) {
        // Dosium Zero-Copy UMA path: escribe directamente sobre el AreaBuffer mapeado sin StagingBuffer
        if (this.commandBuffer == null && UmaBufferBridge.tryZeroCopyUpload(buffer, dstOffset, bufferSize, src)) {
            return;
        }

        this.beginCommands();
        VkCommandBuffer cmd = this.commandBuffer.getHandle();
        StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
        stagingBuffer.copyBuffer((int) bufferSize, src);

        if (!this.dstBuffers.add(buffer.getId())) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
                barrier.sType$Default();
                barrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
                VK10.vkCmdPipelineBarrier(
                        cmd,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,
                        barrier,
                        null,
                        null
                );
            }
            this.dstBuffers.clear();
        }

        TransferQueue.uploadBufferCmd(
                cmd,
                stagingBuffer.getId(),
                stagingBuffer.getOffset(),
                buffer.getId(),
                dstOffset,
                bufferSize
        );
    }

    public void copyBuffer(Buffer src, Buffer dst) {
        this.copyBuffer(src, 0, dst, 0, src.getBufferSize());
    }

    public void copyBuffer(Buffer src, int srcOffset, Buffer dst, int dstOffset, int size) {
        if (this.commandBuffer == null && UmaBufferBridge.tryZeroCopyBufferToBuffer(src, srcOffset, dst, dstOffset, size)) {
            return;
        }

        this.beginCommands();
        VkCommandBuffer cmd = this.commandBuffer.getHandle();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer memoryBarrier = VkMemoryBarrier.calloc(1, stack);
            memoryBarrier.sType$Default();

            VkBufferMemoryBarrier.Buffer bufferBarriers = VkBufferMemoryBarrier.calloc(1, stack);
            VkBufferMemoryBarrier bufferBarrier = bufferBarriers.get(0);
            bufferBarrier.sType$Default();
            bufferBarrier.buffer(src.getId());
            bufferBarrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            bufferBarrier.dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
            bufferBarrier.size(VK10.VK_WHOLE_SIZE);

            VK10.vkCmdPipelineBarrier(
                    cmd,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    memoryBarrier,
                    bufferBarriers,
                    null
            );
        }

        this.dstBuffers.add(dst.getId());
        TransferQueue.uploadBufferCmd(cmd, src.getId(), srcOffset, dst.getId(), dstOffset, size);
    }

    public void syncUploads() {
        this.submitUploads();
        Synchronization.INSTANCE.waitFences();
    }

    private void beginCommands() {
        if (this.commandBuffer == null) {
            this.commandBuffer = this.queue.beginCommands();
        }
    }
}
