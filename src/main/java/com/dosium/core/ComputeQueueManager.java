package com.dosium.core;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.ComputeQueue;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Gestiona la cola VK_QUEUE_COMPUTE_BIT, la compilación de los Compute Shaders de Dosium
 * a SPIR-V mediante Shaderc y la telemetría por hardware (VkQueryPool / vkCmdWriteTimestamp).
 */
public final class ComputeQueueManager {
    private static final int QUERY_COUNT = 6; // 0-1: Frame, 2-3: Cull, 4-5: Mesh
    private static boolean initialized = false;
    private static boolean shadersCompiled = false;
    private static long queryPool = VK10.VK_NULL_HANDLE;
    private static float timestampPeriodNs = 1.0f;
    private static boolean timestampsSupported = false;

    private static long cullShaderModule = VK10.VK_NULL_HANDLE;
    private static long hizShaderModule = VK10.VK_NULL_HANDLE;
    private static long meshShaderModule = VK10.VK_NULL_HANDLE;
    private static long noiseShaderModule = VK10.VK_NULL_HANDLE;
    private static long particleShaderModule = VK10.VK_NULL_HANDLE;

    private static volatile float gpuFrameMs = 0.0f;
    private static volatile float gpuCullMs = 0.0f;
    private static volatile float gpuMeshMs = 0.0f;
    private static long frameStartCpuNs = 0L;

    private ComputeQueueManager() {
    }

    public static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        VkDevice device = Vulkan.getVkDevice();
        if (device == null) {
            return;
        }

        try {
            if (DeviceManager.deviceProperties != null) {
                timestampPeriodNs = DeviceManager.deviceProperties.limits().timestampPeriod();
                int timestampValidBits = DeviceManager.deviceProperties.limits().timestampComputeAndGraphics() ? 64 : 0;
                if (timestampPeriodNs > 0.0f && timestampValidBits > 0) {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        VkQueryPoolCreateInfo poolInfo = VkQueryPoolCreateInfo.calloc(stack)
                                .sType(VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                                .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
                                .queryCount(QUERY_COUNT);
                        LongBuffer pPool = stack.mallocLong(1);
                        int res = VK10.vkCreateQueryPool(device, poolInfo, null, pPool);
                        if (res == VK10.VK_SUCCESS) {
                            queryPool = pPool.get(0);
                            timestampsSupported = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            timestampsSupported = false;
        }

        compileDosiumComputeShaders(device);
        initialized = true;
    }

    private static void compileDosiumComputeShaders(VkDevice device) {
        if (shadersCompiled) {
            return;
        }
        try {
            cullShaderModule = compileComputeModule(device, "cull.comp", "/assets/vulkanmod/shaders/dosium/cull.comp");
            hizShaderModule = compileComputeModule(device, "hiz_downsample.comp", "/assets/vulkanmod/shaders/dosium/hiz_downsample.comp");
            meshShaderModule = compileComputeModule(device, "mesh.comp", "/assets/vulkanmod/shaders/dosium/mesh.comp");
            noiseShaderModule = compileComputeModule(device, "noise.comp", "/assets/vulkanmod/shaders/dosium/noise.comp");
            particleShaderModule = compileComputeModule(device, "particle_sim.comp", "/assets/vulkanmod/shaders/dosium/particle_sim.comp");
            shadersCompiled = true;
        } catch (Throwable ignored) {
        }
    }

    private static long compileComputeModule(VkDevice device, String name, String resourcePath) {
        try (InputStream in = ComputeQueueManager.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return VK10.VK_NULL_HANDLE;
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(name, source, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
            if (spirv == null) {
                return VK10.VK_NULL_HANDLE;
            }
            ByteBuffer byteCode = spirv.bytecode();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                        .pCode(byteCode);
                LongBuffer pModule = stack.mallocLong(1);
                int res = VK10.vkCreateShaderModule(device, createInfo, null, pModule);
                if (res == VK10.VK_SUCCESS) {
                    return pModule.get(0);
                }
            }
        } catch (Throwable ignored) {
        }
        return VK10.VK_NULL_HANDLE;
    }

    public static ComputeQueue getComputeQueue() {
        try {
            return DeviceManager.getComputeQueue();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void beginFrame() {
        ensureInitialized();
        long now = System.nanoTime();
        if (frameStartCpuNs != 0L) {
            float deltaMs = (now - frameStartCpuNs) / 1_000_000.0f;
            if (deltaMs > 0.05f && deltaMs < 250.0f) {
                gpuFrameMs = gpuFrameMs * 0.85f + deltaMs * 0.15f;
            }
        }
        frameStartCpuNs = now;
    }

    public static void writeHardwareTimestamp(VkCommandBuffer cmd, int queryIndex, int pipelineStage) {
        if (!timestampsSupported || queryPool == VK10.VK_NULL_HANDLE || cmd == null) {
            return;
        }
        try {
            if (queryIndex == 0) {
                VK10.vkCmdResetQueryPool(cmd, queryPool, 0, QUERY_COUNT);
            }
            VK10.vkCmdWriteTimestamp(cmd, pipelineStage, queryPool, queryIndex);
        } catch (Throwable ignored) {
        }
    }

    public static void recordCullDurationNs(long durationNs) {
        float ms = durationNs / 1_000_000.0f;
        gpuCullMs = gpuCullMs * 0.8f + ms * 0.2f;
    }

    public static void recordMeshDurationNs(long durationNs) {
        float ms = durationNs / 1_000_000.0f;
        gpuMeshMs = gpuMeshMs * 0.8f + ms * 0.2f;
    }

    public static float getGpuFrameMs() {
        return gpuFrameMs;
    }

    public static float getGpuCullMs() {
        return gpuCullMs;
    }

    public static float getGpuMeshMs() {
        return gpuMeshMs;
    }

    public static boolean areComputeShadersReady() {
        return shadersCompiled && cullShaderModule != VK10.VK_NULL_HANDLE && meshShaderModule != VK10.VK_NULL_HANDLE;
    }

    public static long getCullShaderModule() {
        return cullShaderModule;
    }

    public static long getHiZShaderModule() {
        return hizShaderModule;
    }

    public static long getMeshShaderModule() {
        return meshShaderModule;
    }

    public static long getNoiseShaderModule() {
        return noiseShaderModule;
    }

    public static long getParticleShaderModule() {
        return particleShaderModule;
    }
}
