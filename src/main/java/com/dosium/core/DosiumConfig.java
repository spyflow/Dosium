package com.dosium.core;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.jellysquid.mods.sodium.client.gui.options.TextProvider;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class DosiumConfig {
    private static final String DEFAULT_FILE_NAME = "dosium-options.json";
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .create();

    private static DosiumConfig INSTANCE;

    public CullingMode gpuIndirectCulling = CullingMode.FRUSTUM_HIZ;
    public MeshingMode meshingMode = MeshingMode.HYBRID;
    public ApuThermalMode apuThermalBudget = ApuThermalMode.AUTO_FPS;
    public UmaMemoryMode umaZeroCopy = UmaMemoryMode.AUTO;
    public boolean gpuWorldGenNoise = true;
    public boolean showF3Telemetry = true;

    private Path configPath;

    public static DosiumConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static DosiumConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(DEFAULT_FILE_NAME);
        DosiumConfig config;

        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                config = GSON.fromJson(reader, DosiumConfig.class);
                if (config == null) {
                    config = new DosiumConfig();
                }
            } catch (Exception e) {
                config = new DosiumConfig();
            }
        } else {
            config = new DosiumConfig();
        }

        config.configPath = path;
        config.sanitize();
        config.save();
        return config;
    }

    private void sanitize() {
        if (this.gpuIndirectCulling == null) {
            this.gpuIndirectCulling = CullingMode.FRUSTUM_HIZ;
        }
        if (this.meshingMode == null) {
            this.meshingMode = MeshingMode.HYBRID;
        }
        if (this.apuThermalBudget == null) {
            this.apuThermalBudget = ApuThermalMode.AUTO_FPS;
        }
        if (this.umaZeroCopy == null) {
            this.umaZeroCopy = UmaMemoryMode.AUTO;
        }
    }

    public void save() {
        try {
            if (this.configPath == null) {
                this.configPath = FabricLoader.getInstance().getConfigDir().resolve(DEFAULT_FILE_NAME);
            }
            Path dir = this.configPath.getParent();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path tempPath = this.configPath.resolveSibling(this.configPath.getFileName() + ".tmp");
            Files.writeString(tempPath, GSON.toJson(this));
            Files.move(tempPath, this.configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public enum CullingMode implements TextProvider {
        FRUSTUM_HIZ("Frustum + Hi-Z"),
        FRUSTUM_ONLY("Solo Frustum"),
        CPU_SODIUM("CPU Sodium");

        private final Text label;

        CullingMode(String label) {
            this.label = Text.literal(label);
        }

        @Override
        public Text getLocalizedName() {
            return this.label;
        }
    }

    public enum MeshingMode implements TextProvider {
        GPU_COMPUTE("GPU Compute"),
        HYBRID("Híbrido (GPU+CPU)"),
        CPU_LEGACY("CPU Legacy");

        private final Text label;

        MeshingMode(String label) {
            this.label = Text.literal(label);
        }

        @Override
        public Text getLocalizedName() {
            return this.label;
        }
    }

    public enum ApuThermalMode implements TextProvider {
        AUTO_FPS("Auto (Límite FPS)"),
        CONSERVATIVE_15W("Balanceado (APU)"),
        MAX_PERFORMANCE("Máx. Rendimiento");

        private final Text label;

        ApuThermalMode(String label) {
            this.label = Text.literal(label);
        }

        @Override
        public Text getLocalizedName() {
            return this.label;
        }
    }

    public enum UmaMemoryMode implements TextProvider {
        AUTO("Auto (UMA APU)"),
        ENABLED("Zero-Copy Activo"),
        DISABLED("Desactivado");

        private final Text label;

        UmaMemoryMode(String label) {
            this.label = Text.literal(label);
        }

        @Override
        public Text getLocalizedName() {
            return this.label;
        }
    }
}
