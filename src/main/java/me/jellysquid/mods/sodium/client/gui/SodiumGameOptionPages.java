package me.jellysquid.mods.sodium.client.gui;

import com.google.common.collect.ImmutableList;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.binding.compat.VanillaBooleanOptionBinding;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.MinecraftOptionsStorage;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import me.jellysquid.mods.sodium.client.gui.options.storage.SodiumOptionsStorage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.*;
import net.minecraft.client.render.ChunkBuilderMode;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;

import java.util.ArrayList;
import java.util.List;

public class SodiumGameOptionPages {
    private static final SodiumOptionsStorage sodiumOpts = new SodiumOptionsStorage();
    private static final MinecraftOptionsStorage vanillaOpts = new MinecraftOptionsStorage();
    private static final OptionStorage<Config> vulkanOpts = new OptionStorage<>() {
        @Override
        public Config getData() {
            if (Initializer.CONFIG == null) {
                Initializer.CONFIG = new Config();
            }
            return Initializer.CONFIG;
        }

        @Override
        public void save() {
            if (Initializer.CONFIG != null) {
                Initializer.CONFIG.write();
            }
        }
    };

    public enum VulkanAdvCulling implements TextProvider {
        AGGRESSIVE(1, Text.translatable("vulkanmod.options.advCulling.aggressive")),
        NORMAL(2, Text.translatable("vulkanmod.options.advCulling.normal")),
        CONSERVATIVE(3, Text.translatable("vulkanmod.options.advCulling.conservative")),
        OFF(10, Text.translatable("options.off"));

        private final int id;
        private final Text name;

        VulkanAdvCulling(int id, Text name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return this.id;
        }

        public static VulkanAdvCulling fromId(int id) {
            for (VulkanAdvCulling value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return NORMAL;
        }

        @Override
        public Text getLocalizedName() {
            return this.name;
        }
    }

    public enum VulkanAoMode implements TextProvider {
        OFF(0, Text.translatable("options.off")),
        ON(1, Text.translatable("options.on")),
        SUB_BLOCK(2, Text.translatable("vulkanmod.options.ao.subBlock"));

        private final int id;
        private final Text name;

        VulkanAoMode(int id, Text name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return this.id;
        }

        public static VulkanAoMode fromId(int id) {
            for (VulkanAoMode value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return ON;
        }

        @Override
        public Text getLocalizedName() {
            return this.name;
        }
    }

    private static VideoModeSet safeGetVideoModeSet(VideoModeSet.VideoMode videoMode) {
        try {
            if (videoMode != null) {
                VideoModeSet set = VideoModeManager.getFromVideoMode(videoMode);
                if (set != null) {
                    return set;
                }
            }
            return VideoModeManager.getFirstAvailable();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static OptionPage general() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.renderDistance"))
                        .setTooltip(Text.translatable("sodium.options.view_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 2, 32, 1, ControlValueFormatter.translateVariable("options.chunks")))
                        .setBinding((options, value) -> options.getViewDistance().setValue(value), options -> options.getViewDistance().getValue())
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.simulationDistance"))
                        .setTooltip(Text.translatable("sodium.options.simulation_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 5, 32, 1, ControlValueFormatter.translateVariable("options.chunks")))
                        .setBinding((options, value) -> options.getSimulationDistance().setValue(value), options -> options.getSimulationDistance().getValue())
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.gamma"))
                        .setTooltip(Text.translatable("sodium.options.brightness.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 100, 1, ControlValueFormatter.brightness()))
                        .setBinding((opts, value) -> opts.getGamma().setValue(value * 0.01D), (opts) -> (int) (opts.getGamma().getValue() / 0.01D))
                        .build())
                .build());

        VideoModeSet[] videoResolutions = VideoModeManager.getVideoResolutions();
        int maxResIndex = (videoResolutions != null && videoResolutions.length > 1) ? videoResolutions.length - 1 : 1;

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.guiScale"))
                        .setTooltip(Text.translatable("sodium.options.gui_scale.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, Math.max(1, MinecraftClient.getInstance().getWindow().calculateScaleFactor(0, MinecraftClient.getInstance().forcesUnicodeFont())), 1, ControlValueFormatter.guiScale()))
                        .setBinding((opts, value) -> {
                            opts.getGuiScale().setValue(value);

                            MinecraftClient client = MinecraftClient.getInstance();
                            client.onResolutionChanged();
                        }, opts -> opts.getGuiScale().getValue())
                        .build())
                .add(OptionImpl.createBuilder(int.class, vulkanOpts)
                        .setName(Text.translatable("options.fullscreen.resolution"))
                        .setTooltip(Text.translatable("vulkanmod.options.fullscreenResolution.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, maxResIndex, 1, idx -> {
                            VideoModeSet[] sets = VideoModeManager.getVideoResolutions();
                            if (sets == null || sets.length == 0) {
                                return "Auto";
                            }
                            int clamped = Math.min(Math.max(0, idx), sets.length - 1);
                            return sets[clamped].toString();
                        }))
                        .setBinding((opts, value) -> {
                            VideoModeSet[] sets = VideoModeManager.getVideoResolutions();
                            if (sets != null && sets.length > 0) {
                                int clamped = Math.min(Math.max(0, value), sets.length - 1);
                                opts.videoMode = sets[clamped].getVideoMode(VideoModeManager.selectedVideoMode != null ? VideoModeManager.selectedVideoMode.refreshRate : -1);
                                VideoModeManager.selectedVideoMode = opts.videoMode;
                                VideoModeManager.applySelectedVideoMode();
                            }
                        }, opts -> {
                            VideoModeSet[] sets = VideoModeManager.getVideoResolutions();
                            if (sets == null || sets.length == 0 || opts.videoMode == null) {
                                return 0;
                            }
                            for (int i = 0; i < sets.length; i++) {
                                if (sets[i].width == opts.videoMode.width && sets[i].height == opts.videoMode.height) {
                                    return i;
                                }
                            }
                            return sets.length - 1;
                        })
                        .build())
                .add(OptionImpl.createBuilder(int.class, vulkanOpts)
                        .setName(Text.translatable("vulkanmod.options.refreshRate"))
                        .setTooltip(Text.translatable("vulkanmod.options.refreshRate.tooltip"))
                        .setControl(option -> {
                            VideoModeSet currentSet = safeGetVideoModeSet(VideoModeManager.selectedVideoMode);
                            List<Integer> rates = currentSet != null ? currentSet.getRefreshRates() : List.of(60);
                            int maxRateIdx = Math.max(1, rates.size() - 1);
                            return new SliderControl(option, 0, maxRateIdx, 1, idx -> {
                                VideoModeSet set = safeGetVideoModeSet(VideoModeManager.selectedVideoMode);
                                List<Integer> r = set != null ? set.getRefreshRates() : List.of(60);
                                if (r.isEmpty()) {
                                    return "60 Hz";
                                }
                                int clamped = Math.min(Math.max(0, idx), r.size() - 1);
                                return r.get(clamped) + " Hz";
                            });
                        })
                        .setBinding((opts, value) -> {
                            VideoModeSet set = safeGetVideoModeSet(opts.videoMode);
                            if (set != null) {
                                List<Integer> rates = set.getRefreshRates();
                                if (!rates.isEmpty()) {
                                    int clamped = Math.min(Math.max(0, value), rates.size() - 1);
                                    opts.videoMode = set.getVideoMode(rates.get(clamped));
                                    VideoModeManager.selectedVideoMode = opts.videoMode;
                                    VideoModeManager.applySelectedVideoMode();
                                }
                            }
                        }, opts -> {
                            VideoModeSet set = safeGetVideoModeSet(opts.videoMode);
                            if (set == null || opts.videoMode == null) {
                                return 0;
                            }
                            List<Integer> rates = set.getRefreshRates();
                            int idx = rates.indexOf(opts.videoMode.refreshRate);
                            return idx >= 0 ? idx : Math.max(0, rates.size() - 1);
                        })
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.fullscreen"))
                        .setTooltip(Text.translatable("sodium.options.fullscreen.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.getFullscreen().setValue(value);

                            MinecraftClient client = MinecraftClient.getInstance();
                            Window window = client.getWindow();

                            if (window != null && window.isFullscreen() != opts.getFullscreen().getValue()) {
                                window.toggleFullscreen();

                                // The client might not be able to enter full-screen mode
                                opts.getFullscreen().setValue(window.isFullscreen());
                            }
                        }, (opts) -> opts.getFullscreen().getValue())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vulkanOpts)
                        .setName(Text.translatable("vulkanmod.options.windowedFullscreen"))
                        .setTooltip(Text.translatable("sodium.options.fullscreen.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.windowedFullscreen = value, opts -> opts.windowedFullscreen)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.vsync"))
                        .setTooltip(Text.translatable("sodium.options.v_sync.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(new VanillaBooleanOptionBinding(MinecraftClient.getInstance().options.getEnableVsync()))
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.framerateLimit"))
                        .setTooltip(Text.translatable("sodium.options.fps_limit.tooltip"))
                        .setControl(option -> new SliderControl(option, 10, 260, 10, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, value) -> {
                            opts.getMaxFps().setValue(value);
                            MinecraftClient.getInstance().getWindow().setFramerateLimit(value);
                        }, opts -> opts.getMaxFps().getValue())
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.viewBobbing"))
                        .setTooltip(Text.translatable("sodium.options.view_bobbing.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(new VanillaBooleanOptionBinding(MinecraftClient.getInstance().options.getBobView()))
                        .build())
                .add(OptionImpl.createBuilder(AttackIndicator.class, vanillaOpts)
                        .setName(Text.translatable("options.attackIndicator"))
                        .setTooltip(Text.translatable("sodium.options.attack_indicator.tooltip"))
                        .setControl(opts -> new CyclingControl<>(opts, AttackIndicator.class, new Text[] { Text.translatable("options.off"), Text.translatable("options.attack.crosshair"), Text.translatable("options.attack.hotbar") }))
                        .setBinding((opts, value) -> opts.getAttackIndicator().setValue(value), (opts) -> opts.getAttackIndicator().getValue())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.autosaveIndicator"))
                        .setTooltip(Text.translatable("sodium.options.autosave_indicator.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.getShowAutosaveIndicator().setValue(value), opts -> opts.getShowAutosaveIndicator().getValue())
                        .build())
                .build());

        return new OptionPage(Text.translatable("stat.generalButton"), ImmutableList.copyOf(groups));
    }

    public static OptionPage quality() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(GraphicsMode.class, vanillaOpts)
                        .setName(Text.translatable("options.graphics"))
                        .setTooltip(Text.translatable("sodium.options.graphics_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, GraphicsMode.class, new Text[] { Text.translatable("options.graphics.fast"), Text.translatable("options.graphics.fancy"), Text.translatable("options.graphics.fabulous") }))
                        .setBinding(
                                (opts, value) -> opts.getGraphicsMode().setValue(value),
                                opts -> opts.getGraphicsMode().getValue())
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(CloudRenderMode.class, vanillaOpts)
                        .setName(Text.translatable("options.renderClouds"))
                        .setTooltip(Text.translatable("sodium.options.clouds_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, CloudRenderMode.class, new Text[] {
                                Text.translatable("options.off"),
                                Text.translatable("options.clouds.fast"),
                                Text.translatable("options.clouds.fancy")
                        }))
                        .setBinding((opts, value) -> opts.getCloudRenderMode().setValue(value), opts -> opts.getCloudRenderMode().getValue())
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setName(Text.translatable("soundCategory.weather"))
                        .setTooltip(Text.translatable("sodium.options.weather_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.weatherQuality = value, opts -> opts.quality.weatherQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.leaves_quality.name"))
                        .setTooltip(Text.translatable("sodium.options.leaves_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.leavesQuality = value, opts -> opts.quality.leavesQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(ParticlesMode.class, vanillaOpts)
                        .setName(Text.translatable("options.particles"))
                        .setTooltip(Text.translatable("sodium.options.particle_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, ParticlesMode.class, new Text[] { Text.translatable("options.particles.all"), Text.translatable("options.particles.decreased"), Text.translatable("options.particles.minimal") }))
                        .setBinding((opts, value) -> opts.getParticles().setValue(value), (opts) -> opts.getParticles().getValue())
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(VulkanAoMode.class, vulkanOpts)
                        .setName(Text.translatable("options.ao"))
                        .setTooltip(Text.translatable("vulkanmod.options.ao.subBlock.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, VulkanAoMode.class))
                        .setBinding((opts, value) -> {
                            opts.ambientOcclusion = value.getId();
                            MinecraftClient.getInstance().options.getAo().setValue(value.getId() > 0);
                            MinecraftClient.getInstance().options.write();
                        }, opts -> VulkanAoMode.fromId(opts.ambientOcclusion))
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.biomeBlendRadius"))
                        .setTooltip(Text.translatable("sodium.options.biome_blend.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 7, 1, ControlValueFormatter.biomeBlend()))
                        .setBinding((opts, value) -> opts.getBiomeBlendRadius().setValue(value), opts -> opts.getBiomeBlendRadius().getValue())
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.entityDistanceScaling"))
                        .setTooltip(Text.translatable("sodium.options.entity_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 50, 500, 25, ControlValueFormatter.percentage()))
                        .setBinding((opts, value) -> opts.getEntityDistanceScaling().setValue(value / 100.0), opts -> Math.round(opts.getEntityDistanceScaling().getValue().floatValue() * 100.0F))
                        .setImpact(OptionImpact.MEDIUM)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(Text.translatable("options.entityShadows"))
                        .setTooltip(Text.translatable("sodium.options.entity_shadows.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.getEntityShadows().setValue(value), opts -> opts.getEntityShadows().getValue())
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.vignette.name"))
                        .setTooltip(Text.translatable("sodium.options.vignette.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.enableVignette = value, opts -> opts.quality.enableVignette)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());


        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(Text.translatable("options.mipmapLevels"))
                        .setTooltip(Text.translatable("sodium.options.mipmap_levels.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 4, 1, ControlValueFormatter.multiplier()))
                        .setBinding((opts, value) -> opts.getMipmapLevels().setValue(value), opts -> opts.getMipmapLevels().getValue())
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .build())
                .build());


        return new OptionPage(Text.translatable("sodium.options.pages.quality"), ImmutableList.copyOf(groups));
    }

    public static OptionPage performance() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.chunk_update_threads.name"))
                        .setTooltip(Text.translatable("sodium.options.chunk_update_threads.tooltip"))
                        .setControl(o -> new SliderControl(o, 0, Runtime.getRuntime().availableProcessors(), 1, ControlValueFormatter.quantityOrDisabled("threads", "Default")))
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> {
                            opts.performance.chunkBuilderThreads = value;
                            if (WorldRenderer.getInstance() != null && WorldRenderer.getInstance().getTaskDispatcher() != null) {
                                if (value <= 0) {
                                    WorldRenderer.getInstance().getTaskDispatcher().createThreads();
                                } else {
                                    WorldRenderer.getInstance().getTaskDispatcher().createThreads(value);
                                }
                            }
                        }, opts -> opts.performance.chunkBuilderThreads)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.always_defer_chunk_updates.name"))
                        .setTooltip(Text.translatable("sodium.options.always_defer_chunk_updates.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.alwaysDeferChunkUpdates = value, opts -> opts.performance.alwaysDeferChunkUpdates)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build())
                .add(OptionImpl.createBuilder(ChunkBuilderMode.class, vanillaOpts)
                        .setName(Text.translatable("options.prioritizeChunkUpdates"))
                        .setTooltip(Text.translatable("sodium.options.always_defer_chunk_updates.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, ChunkBuilderMode.class, new Text[] {
                                Text.translatable("options.prioritizeChunkUpdates.none"),
                                Text.translatable("options.prioritizeChunkUpdates.byPlayer"),
                                Text.translatable("options.prioritizeChunkUpdates.nearby")
                        }))
                        .setBinding((opts, value) -> opts.getChunkBuilderMode().setValue(value), opts -> opts.getChunkBuilderMode().getValue())
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build()
        );

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.use_block_face_culling.name"))
                        .setTooltip(Text.translatable("sodium.options.use_block_face_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.useBlockFaceCulling = value, opts -> opts.performance.useBlockFaceCulling)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.use_fog_occlusion.name"))
                        .setTooltip(Text.translatable("sodium.options.use_fog_occlusion.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.performance.useFogOcclusion = value;
                            if (Initializer.CONFIG != null) {
                                Initializer.CONFIG.advCulling = value ? 2 : 10;
                                Initializer.CONFIG.write();
                            }
                        }, opts -> opts.performance.useFogOcclusion)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.use_entity_culling.name"))
                        .setTooltip(Text.translatable("sodium.options.use_entity_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> {
                            opts.performance.useEntityCulling = value;
                            if (Initializer.CONFIG != null) {
                                Initializer.CONFIG.entityCulling = value;
                                Initializer.CONFIG.write();
                            }
                        }, opts -> opts.performance.useEntityCulling)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.animate_only_visible_textures.name"))
                        .setTooltip(Text.translatable("sodium.options.animate_only_visible_textures.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.animateOnlyVisibleTextures = value, opts -> opts.performance.animateOnlyVisibleTextures)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build()
                )
                .build());

        return new OptionPage(Text.translatable("sodium.options.pages.performance"), ImmutableList.copyOf(groups));
    }

    public static OptionPage advanced() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(Text.translatable("sodium.options.cpu_render_ahead_limit.name"))
                        .setTooltip(Text.translatable("sodium.options.cpu_render_ahead_limit.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 9, 1, ControlValueFormatter.translateVariable("sodium.options.cpu_render_ahead_limit.value")))
                        .setBinding((opts, value) -> {
                            opts.advanced.cpuRenderAheadLimit = value;
                            if (Initializer.CONFIG != null) {
                                Initializer.CONFIG.frameQueueSize = Math.min(5, Math.max(2, value));
                                Initializer.CONFIG.write();
                                Renderer.scheduleSwapChainUpdate();
                            }
                        }, opts -> opts.advanced.cpuRenderAheadLimit)
                        .build()
                )
                .build());

        return new OptionPage(Text.translatable("sodium.options.pages.advanced"), ImmutableList.copyOf(groups));
    }

    public static OptionPage vulkan() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(VulkanAdvCulling.class, vulkanOpts)
                        .setName(Text.translatable("vulkanmod.options.advCulling"))
                        .setTooltip(Text.translatable("vulkanmod.options.advCulling.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, VulkanAdvCulling.class))
                        .setBinding((opts, value) -> opts.advCulling = value.getId(), opts -> VulkanAdvCulling.fromId(opts.advCulling))
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vulkanOpts)
                        .setName(Text.translatable("vulkanmod.options.entityCulling"))
                        .setTooltip(Text.translatable("vulkanmod.options.entityCulling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.entityCulling = value, opts -> opts.entityCulling)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vulkanOpts)
                        .setName(Text.translatable("vulkanmod.options.uniqueOpaqueLayer"))
                        .setTooltip(Text.translatable("vulkanmod.options.uniqueOpaqueLayer.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.uniqueOpaqueLayer = value, opts -> opts.uniqueOpaqueLayer)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vulkanOpts)
                        .setName(Text.translatable("vulkanmod.options.indirectDraw"))
                        .setTooltip(Text.translatable("vulkanmod.options.indirectDraw.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.indirectDraw = value, opts -> opts.indirectDraw)
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .build());

        int maxDeviceIndex = (DeviceManager.suitableDevices != null && !DeviceManager.suitableDevices.isEmpty())
                ? DeviceManager.suitableDevices.size() - 1
                : 0;
        String currentDeviceName = (DeviceManager.device != null && DeviceManager.device.deviceName != null)
                ? DeviceManager.device.deviceName
                : "Auto";

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vulkanOpts)
                        .setName(Text.translatable("vulkanmod.options.frameQueue"))
                        .setTooltip(Text.translatable("vulkanmod.options.frameQueue.tooltip"))
                        .setControl(option -> new SliderControl(option, 2, 5, 1, ControlValueFormatter.number()))
                        .setBinding((opts, value) -> {
                            opts.frameQueueSize = value;
                            Renderer.scheduleSwapChainUpdate();
                        }, opts -> opts.frameQueueSize)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vulkanOpts)
                        .setName(Text.translatable("vulkanmod.options.deviceSelector"))
                        .setTooltip(Text.literal(Text.translatable("vulkanmod.options.deviceSelector.tooltip").getString() + ": " + currentDeviceName))
                        .setControl(option -> new SliderControl(option, -1, maxDeviceIndex, 1, idx -> {
                            if (idx < 0 || DeviceManager.suitableDevices == null || idx >= DeviceManager.suitableDevices.size()) {
                                return Text.translatable("vulkanmod.options.deviceSelector.auto").getString();
                            }
                            return DeviceManager.suitableDevices.get(idx).deviceName;
                        }))
                        .setBinding((opts, value) -> opts.device = value, opts -> opts.device)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build())
                .build());

        return new OptionPage(Text.literal("Vulkan"), ImmutableList.copyOf(groups));
    }
}
