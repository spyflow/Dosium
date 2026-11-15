package me.jellysquid.mods.sodium.mixin.features.debug;

import com.dosium.core.ApuBudgetController;
import com.dosium.core.ComputeQueueManager;
import com.dosium.core.DosiumConfig;
import com.dosium.core.UmaBufferBridge;
import com.dosium.meshing.ComputeMeshDispatcher;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.gui.hud.DebugHud;
import net.minecraft.util.Formatting;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

@Mixin(DebugHud.class)
public abstract class MixinDebugHud {
    @Shadow
    private static long toMiB(long bytes) {
        throw new UnsupportedOperationException();
    }

    @Inject(method = "getRightText", at = @At("RETURN"))
    private void appendRightText(CallbackInfoReturnable<List<String>> cir) {
        List<String> strings = cir.getReturnValue();

        for (int i = 0; i < strings.size(); i++) {
            String str = strings.get(i);
            if (str.contains("OpenGL") || str.contains("GL Caps")) {
                Device device = Vulkan.getDevice();
                if (device != null) {
                    strings.set(i, "Vulkan " + device.vkVersion + " (" + device.driverVersion + ")");
                }
            }
        }

        strings.add("");
        strings.add(Formatting.AQUA + "Dosium " + Formatting.RESET + "(Sodium + Vulkan Compute)");
        strings.add(Formatting.UNDERLINE + getFormattedVersionText());
        appendVulkanMemoryStrings(strings);
        appendDosiumTelemetryStrings(strings);

        for (int i = 0; i < strings.size(); i++) {
            String str = strings.get(i);

            if (str.startsWith("Allocated:")) {
                strings.add(i + 1, getNativeMemoryString());

                break;
            }
        }
    }

    private static void appendVulkanMemoryStrings(List<String> strings) {
        try {
            MemoryManager memMgr = MemoryManager.getInstance();
            if (memMgr != null) {
                int usedMb = Math.max(0, memMgr.getAllocatedDeviceMemoryMB()) + Math.max(0, memMgr.getNativeMemoryMB());
                strings.add("Device Memory: " + usedMb + "/" + memMgr.getDeviceMemoryMB() + " MB");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void appendDosiumTelemetryStrings(List<String> strings) {
        try {
            DosiumConfig cfg = DosiumConfig.get();
            if (!cfg.showF3Telemetry) {
                return;
            }
            float cullMs = ComputeQueueManager.getGpuCullMs();
            float meshMs = ComputeQueueManager.getGpuMeshMs();
            int budgetPct = ApuBudgetController.getBudgetUsagePercent();
            Formatting budgetColor = budgetPct > 80 ? Formatting.GOLD : Formatting.GREEN;

            String cullText = ComputeQueueManager.isCullPipelineReady()
                    ? String.format(Locale.ROOT, "GPU Cull: %.2f ms", cullMs)
                    : "GPU Cull: Fallback CPU (" + ComputeQueueManager.getCullFallbackReason() + ")";

            String meshText = ComputeQueueManager.isMeshPipelineReady()
                    ? String.format(Locale.ROOT, "GPU Mesh: %.2f ms", meshMs)
                    : "GPU Mesh: Fallback CPU (" + ComputeQueueManager.getMeshFallbackReason() + ")";

            strings.add(cullText + " | " + meshText);
            strings.add("APU Budget: " + budgetColor + budgetPct + "%" + Formatting.RESET
                    + " | " + ComputeMeshDispatcher.getActiveModeShortName());
            strings.add("Memory Path: " + UmaBufferBridge.getStatusText());
        } catch (Throwable ignored) {
        }
    }

    private static String getFormattedVersionText() {
        String version = SodiumClientMod.getVersion();
        Formatting color;

        if (version.endsWith("-dirty")) {
            color = Formatting.RED;
        } else if (version.contains("+rev.")) {
            color = Formatting.LIGHT_PURPLE;
        } else {
            color = Formatting.GREEN;
        }

        return color + version;
    }

    private static String getNativeMemoryString() {
        return "Off-Heap: +" + toMiB(getNativeMemoryUsage()) + "MB";
    }

    private static long getNativeMemoryUsage() {
        return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() + NativeBuffer.getTotalAllocated();
    }
}
