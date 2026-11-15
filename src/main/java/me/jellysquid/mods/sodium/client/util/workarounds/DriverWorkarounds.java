package me.jellysquid.mods.sodium.client.util.workarounds;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class DriverWorkarounds {
    private static final AtomicReference<Set<Reference>> ACTIVE_WORKAROUNDS = new AtomicReference<>(EnumSet.noneOf(Reference.class));

    public static void init() {
        // OpenGL driver probing is disabled because rendering runs entirely on Vulkan.
    }

    public static boolean isWorkaroundEnabled(Reference id) {
        return ACTIVE_WORKAROUNDS.get().contains(id);
    }

    public enum Reference {
        ISSUE_1486
    }
}
