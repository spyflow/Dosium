package com.dosium.core;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.ComputeQueue;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Infraestructura de Cómputo de Dosium.
 * Administra la compilación de Compute Shaders GLSL a SPIR-V, la creación de DescriptorSetLayouts,
 * PipelineLayouts y ComputePipelines en Vulkan, y el despacho real mediante vkCmdDispatch con
 * sincronización de barreras de memoria y telemetría por hardware (VkQueryPool / timestamps).
 */
public final class ComputeQueueManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("DosiumCompute");
    private static final int QUERY_COUNT = 6; // 0-1: Frame, 2-3: Cull, 4-5: Mesh

    private static boolean initialized = false;
    private static long queryPool = VK10.VK_NULL_HANDLE;
    private static float timestampPeriodNs = 1.0f;
    private static boolean timestampsSupported = false;

    // Shader modules
    private static long cullShaderModule = VK10.VK_NULL_HANDLE;
    private static long hizShaderModule = VK10.VK_NULL_HANDLE;
    private static long meshShaderModule = VK10.VK_NULL_HANDLE;
    private static long noiseShaderModule = VK10.VK_NULL_HANDLE;
    private static long particleShaderModule = VK10.VK_NULL_HANDLE;

    // Descriptores y Pipelines de Cómputo
    private static long computeDescriptorPool = VK10.VK_NULL_HANDLE;

    private static long cullDescriptorSetLayout = VK10.VK_NULL_HANDLE;
    private static long cullPipelineLayout = VK10.VK_NULL_HANDLE;
    private static long cullPipeline = VK10.VK_NULL_HANDLE;
    private static boolean cullPipelineReady = false;
    private static String cullFallbackReason = null;
    private static long[] cullDescriptorSets;

    private static long hizDescriptorSetLayout = VK10.VK_NULL_HANDLE;
    private static long hizPipelineLayout = VK10.VK_NULL_HANDLE;
    private static long hizPipeline = VK10.VK_NULL_HANDLE;
    private static boolean hizPipelineReady = false;
    private static String hizFallbackReason = null;
    private static long hizDescriptorSet = VK10.VK_NULL_HANDLE;

    private static long meshDescriptorSetLayout = VK10.VK_NULL_HANDLE;
    private static long meshPipelineLayout = VK10.VK_NULL_HANDLE;
    private static long meshPipeline = VK10.VK_NULL_HANDLE;
    private static boolean meshPipelineReady = false;
    private static String meshFallbackReason = null;
    private static long[] meshDescriptorSets;

    // Telemetría de tiempos reales de GPU
    private static volatile float realGpuCullMs = 0.0f;
    private static volatile float realGpuMeshMs = 0.0f;
    private static volatile float gpuFrameMs = 0.0f;
    private static volatile float lastFrameDeltaMs = 0.0f;
    private static volatile boolean cullDispatchedThisFrame = false;
    private static volatile boolean meshDispatchedThisFrame = false;
    private static long frameStartCpuNs = 0L;

    private ComputeQueueManager() {
    }

    public static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        VkDevice device = Vulkan.getVkDevice();
        if (device == null) {
            LOGGER.warn("VkDevice not yet available; deferring compute initialization.");
            return;
        }

        LOGGER.info("Initializing Dosium GPU Compute Infrastructure...");

        // 1. Inicializar QueryPool de timestamps si el hardware lo soporta
        initTimestampQueries(device);

        // 2. Compilar shaders a SPIR-V
        compileAllShaders(device);

        // 3. Crear DescriptorPool y Pipelines de Cómputo
        createComputePipelines(device);

        // 4. Registrar resumen de estado de infraestructura
        logStartupBanner();

        initialized = true;
    }

    private static void initTimestampQueries(VkDevice device) {
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
                            LOGGER.info("Hardware timestamp queries enabled (period: {} ns)", timestampPeriodNs);
                        } else {
                            LOGGER.warn("Failed to create timestamp query pool, VkResult: {}", res);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize hardware timestamp queries: {}", t.getMessage(), t);
            timestampsSupported = false;
        }
    }

    private static void compileAllShaders(VkDevice device) {
        cullShaderModule = compileComputeModule(device, "cull.comp", "/assets/vulkanmod/shaders/dosium/cull.comp");
        hizShaderModule = compileComputeModule(device, "hiz_downsample.comp", "/assets/vulkanmod/shaders/dosium/hiz_downsample.comp");
        meshShaderModule = compileComputeModule(device, "mesh.comp", "/assets/vulkanmod/shaders/dosium/mesh.comp");
        noiseShaderModule = compileComputeModule(device, "noise.comp", "/assets/vulkanmod/shaders/dosium/noise.comp");
        particleShaderModule = compileComputeModule(device, "particle_sim.comp", "/assets/vulkanmod/shaders/dosium/particle_sim.comp");
    }

    private static long compileComputeModule(VkDevice device, String name, String resourcePath) {
        try (InputStream in = ComputeQueueManager.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                LOGGER.error("Shader resource not found: {}", resourcePath);
                return VK10.VK_NULL_HANDLE;
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(name, source, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
            if (spirv == null) {
                LOGGER.error("Shaderc compilation failed for {}", name);
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
                    long module = pModule.get(0);
                    LOGGER.info("Compiled compute shader module: {} [0x{}]", name, Long.toHexString(module));
                    return module;
                } else {
                    LOGGER.error("vkCreateShaderModule failed for {} with code: {}", name, res);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Exception compiling compute module {}: {}", name, t.getMessage(), t);
        }
        return VK10.VK_NULL_HANDLE;
    }

    private static void createComputePipelines(VkDevice device) {
        int frames = Math.max(1, Renderer.getFramesNum());

        // Crear DescriptorPool
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(32);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(16);
            poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(16);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(32)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            int res = VK10.vkCreateDescriptorPool(device, poolInfo, null, pPool);
            if (res == VK10.VK_SUCCESS) {
                computeDescriptorPool = pPool.get(0);
            } else {
                LOGGER.error("Failed to create compute descriptor pool: {}", res);
                cullFallbackReason = "DescriptorPool creation failed (" + res + ")";
                return;
            }
        } catch (Throwable t) {
            LOGGER.error("Error creating compute descriptor pool: {}", t.getMessage(), t);
            cullFallbackReason = t.getMessage();
            return;
        }

        // 1. Pipeline para cull.comp
        initCullPipeline(device, frames);

        // 2. Pipeline para hiz_downsample.comp
        initHiZPipeline(device);

        // 3. Pipeline para mesh.comp
        initMeshPipeline(device, frames);
    }

    private static void initCullPipeline(VkDevice device, int frames) {
        if (cullShaderModule == VK10.VK_NULL_HANDLE) {
            cullFallbackReason = "cull.comp module is null";
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Bindings: 0=SectionBuffer (SSBO), 1=IndirectCommandBuffer (SSBO), 2=DrawCountBuffer (SSBO), 3=u_HiZPyramid (Sampler2D)
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer pLayout = stack.mallocLong(1);
            int res = VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            if (res != VK10.VK_SUCCESS) {
                cullFallbackReason = "DescriptorSetLayout failed (" + res + ")";
                LOGGER.error("cullDescriptorSetLayout creation failed: {}", res);
                return;
            }
            cullDescriptorSetLayout = pLayout.get(0);

            // PushConstants: 192 bytes (mat4 viewProj + vec4[6] planes + vec4 cam + uvec4 counts)
            VkPushConstantRange.Buffer pushRanges = VkPushConstantRange.calloc(1, stack);
            pushRanges.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(192);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(cullDescriptorSetLayout))
                    .pPushConstantRanges(pushRanges);
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            res = VK10.vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout);
            if (res != VK10.VK_SUCCESS) {
                cullFallbackReason = "PipelineLayout failed (" + res + ")";
                LOGGER.error("cullPipelineLayout creation failed: {}", res);
                return;
            }
            cullPipelineLayout = pPipelineLayout.get(0);

            // Compute Pipeline
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);
            pipelineInfo.layout(cullPipelineLayout);
            pipelineInfo.stage()
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(cullShaderModule)
                    .pName(stack.UTF8("main"));

            LongBuffer pPipeline = stack.mallocLong(1);
            res = VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
            if (res != VK10.VK_SUCCESS) {
                cullFallbackReason = "vkCreateComputePipelines failed (" + res + ")";
                LOGGER.error("cullPipeline creation failed: {}", res);
                return;
            }
            cullPipeline = pPipeline.get(0);

            // Asignar DescriptorSets por frame
            cullDescriptorSets = new long[frames];
            LongBuffer pLayouts = stack.mallocLong(frames);
            for (int i = 0; i < frames; i++) {
                pLayouts.put(i, cullDescriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(computeDescriptorPool)
                    .pSetLayouts(pLayouts);
            LongBuffer pSets = stack.mallocLong(frames);
            res = VK10.vkAllocateDescriptorSets(device, allocInfo, pSets);
            if (res == VK10.VK_SUCCESS) {
                for (int i = 0; i < frames; i++) {
                    cullDescriptorSets[i] = pSets.get(i);
                }
                cullPipelineReady = true;
                LOGGER.info("cull.comp compute pipeline successfully created [0x{}]", Long.toHexString(cullPipeline));
            } else {
                cullFallbackReason = "DescriptorSet allocation failed (" + res + ")";
                LOGGER.error("cullDescriptorSets allocation failed: {}", res);
            }
        } catch (Throwable t) {
            cullFallbackReason = t.getMessage();
            LOGGER.error("Exception initializing cull pipeline: {}", t.getMessage(), t);
        }
    }

    private static void initHiZPipeline(VkDevice device) {
        if (hizShaderModule == VK10.VK_NULL_HANDLE) {
            hizFallbackReason = "hiz_downsample.comp module is null";
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer pLayout = stack.mallocLong(1);
            int res = VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            if (res != VK10.VK_SUCCESS) {
                hizFallbackReason = "HiZ DescriptorSetLayout failed (" + res + ")";
                return;
            }
            hizDescriptorSetLayout = pLayout.get(0);

            VkPushConstantRange.Buffer pushRanges = VkPushConstantRange.calloc(1, stack);
            pushRanges.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16); // 2x ivec2

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(hizDescriptorSetLayout))
                    .pPushConstantRanges(pushRanges);
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            res = VK10.vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout);
            if (res != VK10.VK_SUCCESS) {
                hizFallbackReason = "HiZ PipelineLayout failed (" + res + ")";
                return;
            }
            hizPipelineLayout = pPipelineLayout.get(0);

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);
            pipelineInfo.layout(hizPipelineLayout);
            pipelineInfo.stage()
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(hizShaderModule)
                    .pName(stack.UTF8("main"));

            LongBuffer pPipeline = stack.mallocLong(1);
            res = VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
            if (res != VK10.VK_SUCCESS) {
                hizFallbackReason = "HiZ vkCreateComputePipelines failed (" + res + ")";
                return;
            }
            hizPipeline = pPipeline.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(computeDescriptorPool)
                    .pSetLayouts(stack.longs(hizDescriptorSetLayout));
            LongBuffer pSet = stack.mallocLong(1);
            res = VK10.vkAllocateDescriptorSets(device, allocInfo, pSet);
            if (res == VK10.VK_SUCCESS) {
                hizDescriptorSet = pSet.get(0);
                hizPipelineReady = true;
                LOGGER.info("hiz_downsample.comp compute pipeline successfully created [0x{}]", Long.toHexString(hizPipeline));
            } else {
                hizFallbackReason = "HiZ DescriptorSet allocation failed (" + res + ")";
            }
        } catch (Throwable t) {
            hizFallbackReason = t.getMessage();
            LOGGER.error("Exception initializing hiz pipeline: {}", t.getMessage(), t);
        }
    }

    private static void initMeshPipeline(VkDevice device, int frames) {
        if (meshShaderModule == VK10.VK_NULL_HANDLE) {
            meshFallbackReason = "mesh.comp module is null";
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer pLayout = stack.mallocLong(1);
            int res = VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            if (res != VK10.VK_SUCCESS) {
                meshFallbackReason = "Mesh DescriptorSetLayout failed (" + res + ")";
                return;
            }
            meshDescriptorSetLayout = pLayout.get(0);

            VkPushConstantRange.Buffer pushRanges = VkPushConstantRange.calloc(1, stack);
            pushRanges.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16); // ivec4 sectionOrigin

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(meshDescriptorSetLayout))
                    .pPushConstantRanges(pushRanges);
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            res = VK10.vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout);
            if (res != VK10.VK_SUCCESS) {
                meshFallbackReason = "Mesh PipelineLayout failed (" + res + ")";
                return;
            }
            meshPipelineLayout = pPipelineLayout.get(0);

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);
            pipelineInfo.layout(meshPipelineLayout);
            pipelineInfo.stage()
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(meshShaderModule)
                    .pName(stack.UTF8("main"));

            LongBuffer pPipeline = stack.mallocLong(1);
            res = VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
            if (res != VK10.VK_SUCCESS) {
                meshFallbackReason = "Mesh vkCreateComputePipelines failed (" + res + ")";
                return;
            }
            meshPipeline = pPipeline.get(0);

            meshDescriptorSets = new long[frames];
            LongBuffer pLayouts = stack.mallocLong(frames);
            for (int i = 0; i < frames; i++) {
                pLayouts.put(i, meshDescriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(computeDescriptorPool)
                    .pSetLayouts(pLayouts);
            LongBuffer pSets = stack.mallocLong(frames);
            res = VK10.vkAllocateDescriptorSets(device, allocInfo, pSets);
            if (res == VK10.VK_SUCCESS) {
                for (int i = 0; i < frames; i++) {
                    meshDescriptorSets[i] = pSets.get(i);
                }
                meshPipelineReady = true;
                LOGGER.info("mesh.comp compute pipeline successfully created [0x{}]", Long.toHexString(meshPipeline));
            } else {
                meshFallbackReason = "Mesh DescriptorSet allocation failed (" + res + ")";
            }
        } catch (Throwable t) {
            meshFallbackReason = t.getMessage();
            LOGGER.error("Exception initializing mesh pipeline: {}", t.getMessage(), t);
        }
    }

    private static void logStartupBanner() {
        LOGGER.info("=================================================");
        LOGGER.info("       Dosium Compute Infrastructure Status       ");
        LOGGER.info("=================================================");
        LOGGER.info("  * cull.comp:            {} (Path: {})",
                cullPipelineReady ? "READY" : "FAILED",
                cullPipelineReady ? "GPU Indirect Culling" : ("CPU Fallback [" + cullFallbackReason + "]"));
        LOGGER.info("  * hiz_downsample.comp:  {} (Path: {})",
                hizPipelineReady ? "READY" : "FAILED",
                hizPipelineReady ? "GPU Hi-Z Pyramid" : ("CPU Fallback [" + hizFallbackReason + "]"));
        LOGGER.info("  * mesh.comp:            {} (Path: {})",
                meshPipelineReady ? "READY" : "FAILED",
                meshPipelineReady ? "GPU Compute Mesher" : ("CPU Mesher [" + meshFallbackReason + "]"));
        LOGGER.info("  * noise.comp:           {}", noiseShaderModule != VK10.VK_NULL_HANDLE ? "COMPILED" : "OFF");
        LOGGER.info("  * particle_sim.comp:    {}", particleShaderModule != VK10.VK_NULL_HANDLE ? "COMPILED" : "OFF");
        LOGGER.info("=================================================");
    }

    // -------------------------------------------------------------
    // MÉTODOS DE DESPACHO REAL (vkCmdDispatch)
    // -------------------------------------------------------------

    public static boolean dispatchCull(VkCommandBuffer cmd, int frameIndex,
                                       StorageBuffer sectionBuffer,
                                       StorageBuffer indirectBuffer,
                                       StorageBuffer countBuffer,
                                       long hizImageView, long hizSampler,
                                       int sectionCount, ByteBuffer pushConstants) {
        if (!cullPipelineReady || cmd == null || sectionBuffer == null || indirectBuffer == null || countBuffer == null) {
            return false;
        }
        if (cullDescriptorSets == null || frameIndex < 0 || frameIndex >= cullDescriptorSets.length) {
            return false;
        }

        long set = cullDescriptorSets[frameIndex];
        VkDevice device = Vulkan.getVkDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Actualizar descriptores del frame actual
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);

            VkDescriptorBufferInfo.Buffer buf0 = VkDescriptorBufferInfo.calloc(1, stack);
            buf0.buffer(sectionBuffer.getId()).offset(0).range(VK10.VK_WHOLE_SIZE);
            writes.get(0).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(buf0);

            VkDescriptorBufferInfo.Buffer buf1 = VkDescriptorBufferInfo.calloc(1, stack);
            buf1.buffer(indirectBuffer.getId()).offset(0).range(VK10.VK_WHOLE_SIZE);
            writes.get(1).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(buf1);

            VkDescriptorBufferInfo.Buffer buf2 = VkDescriptorBufferInfo.calloc(1, stack);
            buf2.buffer(countBuffer.getId()).offset(0).range(VK10.VK_WHOLE_SIZE);
            writes.get(2).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(buf2);

            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
            imgInfo.imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(hizImageView).sampler(hizSampler);
            writes.get(3).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imgInfo);

            VK10.vkUpdateDescriptorSets(device, writes, null);

            // Grabar comandos de cómputo
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, cullPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, cullPipelineLayout, 0, stack.longs(set), null);
            VK10.vkCmdPushConstants(cmd, cullPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants);

            writeHardwareTimestamp(cmd, 2, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            int workgroupsX = (sectionCount + 63) / 64;
            VK10.vkCmdDispatch(cmd, workgroupsX, 1, 1);
            writeHardwareTimestamp(cmd, 3, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            // Barrera de memoria: VK_ACCESS_SHADER_WRITE_BIT -> VK_ACCESS_INDIRECT_COMMAND_READ_BIT
            VkMemoryBarrier.Buffer memBarrier = VkMemoryBarrier.calloc(1, stack);
            memBarrier.sType$Default();
            memBarrier.srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT);
            memBarrier.dstAccessMask(VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(
                    cmd,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    0,
                    memBarrier, null, null
            );

            cullDispatchedThisFrame = true;
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error dispatching cull.comp: {}", t.getMessage(), t);
            return false;
        }
    }

    public static boolean dispatchHiZ(VkCommandBuffer cmd, long srcImageView, long srcSampler,
                                      long dstImageView, int srcW, int srcH, int dstW, int dstH) {
        if (!hizPipelineReady || cmd == null || hizDescriptorSet == VK10.VK_NULL_HANDLE) {
            return false;
        }
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);

            VkDescriptorImageInfo.Buffer srcInfo = VkDescriptorImageInfo.calloc(1, stack);
            srcInfo.imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(srcImageView).sampler(srcSampler);
            writes.get(0).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(hizDescriptorSet).dstBinding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(srcInfo);

            VkDescriptorImageInfo.Buffer dstInfo = VkDescriptorImageInfo.calloc(1, stack);
            dstInfo.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).imageView(dstImageView);
            writes.get(1).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(hizDescriptorSet).dstBinding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(dstInfo);

            VK10.vkUpdateDescriptorSets(device, writes, null);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, hizPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, hizPipelineLayout, 0, stack.longs(hizDescriptorSet), null);

            ByteBuffer pc = stack.malloc(16);
            pc.putInt(0, srcW);
            pc.putInt(4, srcH);
            pc.putInt(8, dstW);
            pc.putInt(12, dstH);
            VK10.vkCmdPushConstants(cmd, hizPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            int gx = (dstW + 7) / 8;
            int gy = (dstH + 7) / 8;
            VK10.vkCmdDispatch(cmd, gx, gy, 1);

            // Barrera para que el siguiente mip lea lo recién generado
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.sType$Default();
            barrier.srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT);
            barrier.dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error dispatching hiz_downsample.comp: {}", t.getMessage(), t);
            return false;
        }
    }

    public static boolean dispatchMesh(VkCommandBuffer cmd, int frameIndex,
                                       StorageBuffer voxelBuffer,
                                       StorageBuffer vertexBuffer,
                                       StorageBuffer counterBuffer,
                                       int originX, int originY, int originZ, int maxQuads) {
        if (!meshPipelineReady || cmd == null || voxelBuffer == null || vertexBuffer == null || counterBuffer == null) {
            return false;
        }
        if (meshDescriptorSets == null || frameIndex < 0 || frameIndex >= meshDescriptorSets.length) {
            return false;
        }

        long set = meshDescriptorSets[frameIndex];
        VkDevice device = Vulkan.getVkDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);

            VkDescriptorBufferInfo.Buffer b0 = VkDescriptorBufferInfo.calloc(1, stack);
            b0.buffer(voxelBuffer.getId()).offset(0).range(VK10.VK_WHOLE_SIZE);
            writes.get(0).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(b0);

            VkDescriptorBufferInfo.Buffer b1 = VkDescriptorBufferInfo.calloc(1, stack);
            b1.buffer(vertexBuffer.getId()).offset(0).range(VK10.VK_WHOLE_SIZE);
            writes.get(1).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(b1);

            VkDescriptorBufferInfo.Buffer b2 = VkDescriptorBufferInfo.calloc(1, stack);
            b2.buffer(counterBuffer.getId()).offset(0).range(VK10.VK_WHOLE_SIZE);
            writes.get(2).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(b2);

            VK10.vkUpdateDescriptorSets(device, writes, null);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, meshPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, meshPipelineLayout, 0, stack.longs(set), null);

            ByteBuffer pc = stack.malloc(16);
            pc.putInt(0, originX);
            pc.putInt(4, originY);
            pc.putInt(8, originZ);
            pc.putInt(12, maxQuads);
            VK10.vkCmdPushConstants(cmd, meshPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            writeHardwareTimestamp(cmd, 4, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            // 16x16x16 / (8x8x4) = 2x2x4 workgroups
            VK10.vkCmdDispatch(cmd, 2, 2, 4);
            writeHardwareTimestamp(cmd, 5, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            // Barrera de memoria: VK_ACCESS_SHADER_WRITE_BIT -> VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT
            VkMemoryBarrier.Buffer memBarrier = VkMemoryBarrier.calloc(1, stack);
            memBarrier.sType$Default();
            memBarrier.srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT);
            memBarrier.dstAccessMask(VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(
                    cmd,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                    0,
                    memBarrier, null, null
            );

            meshDispatchedThisFrame = true;
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error dispatching mesh.comp: {}", t.getMessage(), t);
            return false;
        }
    }

    public static ComputeQueue getComputeQueue() {
        try {
            return DeviceManager.getComputeQueue();
        } catch (Throwable t) {
            LOGGER.warn("DeviceManager.getComputeQueue() threw exception: {}", t.getMessage());
            return null;
        }
    }

    public static void beginFrame() {
        ensureInitialized();
        cullDispatchedThisFrame = false;
        meshDispatchedThisFrame = false;

        long now = System.nanoTime();
        if (frameStartCpuNs != 0L) {
            float deltaMs = (now - frameStartCpuNs) / 1_000_000.0f;
            if (deltaMs > 0.05f && deltaMs < 250.0f) {
                lastFrameDeltaMs = deltaMs;
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

    public static void recordRealGpuCullTimeMs(float ms) {
        realGpuCullMs = realGpuCullMs * 0.8f + ms * 0.2f;
    }

    public static void recordRealGpuMeshTimeMs(float ms) {
        realGpuMeshMs = realGpuMeshMs * 0.8f + ms * 0.2f;
    }

    public static void recordCullDurationNs(long ns) {
        if (cullDispatchedThisFrame) {
            recordRealGpuCullTimeMs(ns / 1_000_000.0f);
        }
    }

    public static void recordMeshDurationNs(long ns) {
        if (meshDispatchedThisFrame) {
            recordRealGpuMeshTimeMs(ns / 1_000_000.0f);
        }
    }

    public static float getGpuFrameMs() {
        return gpuFrameMs;
    }

    public static float getLastFrameDeltaMs() {
        return lastFrameDeltaMs;
    }

    /**
     * Retorna el tiempo medido de GPU Cull. Si no hubo dispatch real este frame, retorna 0.0.
     */
    public static float getGpuCullMs() {
        return cullDispatchedThisFrame ? realGpuCullMs : 0.0f;
    }

    /**
     * Retorna el tiempo medido de GPU Mesh. Si no hubo dispatch real este frame, retorna 0.0.
     */
    public static float getGpuMeshMs() {
        return meshDispatchedThisFrame ? realGpuMeshMs : 0.0f;
    }

    public static boolean isCullPipelineReady() {
        return cullPipelineReady;
    }

    public static boolean isHizPipelineReady() {
        return hizPipelineReady;
    }

    public static boolean isMeshPipelineReady() {
        return meshPipelineReady;
    }

    public static String getCullFallbackReason() {
        return cullFallbackReason;
    }

    public static String getHizFallbackReason() {
        return hizFallbackReason;
    }

    public static String getMeshFallbackReason() {
        return meshFallbackReason;
    }

    public static boolean areComputeShadersReady() {
        return cullPipelineReady && meshPipelineReady;
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
