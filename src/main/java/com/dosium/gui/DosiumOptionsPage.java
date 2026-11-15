package com.dosium.gui;

import com.dosium.core.DosiumConfig;
import com.google.common.collect.ImmutableList;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.text.Text;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.WorldRenderer;

import java.util.ArrayList;
import java.util.List;

public final class DosiumOptionsPage {
    private static final OptionStorage<DosiumConfig> DOSIUM_STORAGE = new OptionStorage<>() {
        @Override
        public DosiumConfig getData() {
            return DosiumConfig.get();
        }

        @Override
        public void save() {
            DosiumConfig config = DosiumConfig.get();
            config.save();
            try {
                if (Initializer.CONFIG != null) {
                    Initializer.CONFIG.indirectDraw = (config.gpuIndirectCulling != DosiumConfig.CullingMode.CPU_SODIUM);
                    Initializer.CONFIG.write();
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private DosiumOptionsPage() {
    }

    public static OptionPage create() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(DosiumConfig.CullingMode.class, DOSIUM_STORAGE)
                        .setName(Text.literal("GPU Indirect Culling"))
                        .setTooltip(Text.literal("Ejecuta el Frustum Culling y Hi-Z Occlusion Culling en paralelo (cull.comp) y emite el terreno mediante dibujado indirecto de Vulkan (vkCmdDrawIndexedIndirect)."))
                        .setControl(option -> new CyclingControl<>(option, DosiumConfig.CullingMode.class))
                        .setBinding((opts, value) -> opts.gpuIndirectCulling = value, opts -> opts.gpuIndirectCulling)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build())
                .add(OptionImpl.createBuilder(DosiumConfig.MeshingMode.class, DOSIUM_STORAGE)
                        .setName(Text.literal("Motor de Meshing"))
                        .setTooltip(Text.literal("Define cómo se empaquetan (18x18x18) y construyen las mallas de chunks: GPU Compute (mesh.comp), Modo Híbrido (GPU + 1 hilo auxiliar de CPU para APUs de 2 núcleos) o CPU Legacy."))
                        .setControl(option -> new CyclingControl<>(option, DosiumConfig.MeshingMode.class))
                        .setBinding((opts, value) -> {
                            opts.meshingMode = value;
                            try {
                                if (WorldRenderer.getInstance() != null && WorldRenderer.getInstance().getTaskDispatcher() != null) {
                                    WorldRenderer.getInstance().getTaskDispatcher().createThreads();
                                }
                            } catch (Throwable ignored) {
                            }
                        }, opts -> opts.meshingMode)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(DosiumConfig.ApuThermalMode.class, DOSIUM_STORAGE)
                        .setName(Text.literal("Presupuesto APU"))
                        .setTooltip(Text.literal("Regulador dinámico de presupuesto compartido CPU/GPU para APUs. Si el uso de GPU supera el 80% del tiempo objetivo del frame, pospone los chunks lejanos para mantener FPS estables."))
                        .setControl(option -> new CyclingControl<>(option, DosiumConfig.ApuThermalMode.class))
                        .setBinding((opts, value) -> opts.apuThermalBudget = value, opts -> opts.apuThermalBudget)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(DosiumConfig.UmaMemoryMode.class, DOSIUM_STORAGE)
                        .setName(Text.literal("Memoria UMA"))
                        .setTooltip(Text.literal("Aprovecha la arquitectura de memoria unificada (HOST_VISIBLE | HOST_COHERENT) en APUs para escribir mallas directamente en el AreaBuffer sin usar Staging Buffers."))
                        .setControl(option -> new CyclingControl<>(option, DosiumConfig.UmaMemoryMode.class))
                        .setBinding((opts, value) -> opts.umaZeroCopy = value, opts -> opts.umaZeroCopy)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, DOSIUM_STORAGE)
                        .setName(Text.literal("GPU WorldGen Noise"))
                        .setTooltip(Text.literal("Acelera en lote las funciones de densidad de ruido 3D (noise.comp) en mundos de un jugador para aliviar el hilo del servidor integrado."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.gpuWorldGenNoise = value, opts -> opts.gpuWorldGenNoise)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, DOSIUM_STORAGE)
                        .setName(Text.literal("Overlay Telemetría (F3)"))
                        .setTooltip(Text.literal("Muestra en la pantalla de depuración F3 los tiempos en milisegundos de GPU Cull, GPU Mesh, el porcentaje de presupuesto térmico de APU y el estado de UMA Zero-Copy."))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.showF3Telemetry = value, opts -> opts.showF3Telemetry)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        return new OptionPage(Text.literal("Dosium Compute"), ImmutableList.copyOf(groups));
    }
}
