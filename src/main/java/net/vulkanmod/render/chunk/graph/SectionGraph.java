package net.vulkanmod.render.chunk.graph;

import com.dosium.core.ApuBudgetController;
import com.dosium.culling.HiZPyramidBuilder;
import com.dosium.culling.IndirectDrawManager;
import com.dosium.meshing.ComputeMeshDispatcher;
import com.dosium.worldgen.GpuNoiseBridge;
import com.google.common.collect.Lists;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.ChunkBuilderMode;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.vulkanmod.Initializer;
import net.vulkanmod.interfaces.FrustumMixed;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.TaskDispatcher;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import net.vulkanmod.render.chunk.util.AreaSetQueue;
import net.vulkanmod.render.chunk.util.ResettableQueue;
import net.vulkanmod.render.profiling.Profiler;

import java.util.ArrayList;

public class SectionGraph {
    MinecraftClient minecraft;
    private final World level;
    private final SectionGrid sectionGrid;
    private final ChunkAreaManager chunkAreaManager;
    private final TaskDispatcher taskDispatcher;
    private final ResettableQueue<RenderSection> sectionQueue = new ResettableQueue<>();
    private AreaSetQueue chunkAreaQueue;
    private short lastFrame = 0;
    private final ResettableQueue<RenderSection> blockEntitiesSections = new ResettableQueue<>();
    private final ResettableQueue<RenderSection> rebuildQueue = new ResettableQueue<>();
    private VFrustum frustum;
    public RenderRegionBuilder renderRegionCache;
    int nonEmptyChunks;

    public SectionGraph(World level, SectionGrid sectionGrid, TaskDispatcher taskDispatcher) {
        this.level = level;
        this.sectionGrid = sectionGrid;
        this.chunkAreaManager = sectionGrid.getChunkAreaManager();
        this.taskDispatcher = taskDispatcher;
        this.chunkAreaQueue = new AreaSetQueue(sectionGrid.getChunkAreaManager().size);
        this.minecraft = MinecraftClient.getInstance();
        this.renderRegionCache = WorldRenderer.getInstance().renderRegionCache;
    }

    public void update(Camera camera, Frustum frustum, boolean spectator) {
        Profiler profiler = Profiler.getMainProfiler();
        BlockPos blockPos = camera.getBlockPos();
        Vec3d camPos = camera.getPos();
        this.minecraft.getProfiler().push("update");

        ApuBudgetController.updateFrameBudget();
        ComputeMeshDispatcher.beginFrame();
        IndirectDrawManager.beginCullPass(camPos.x, camPos.y, camPos.z);

        boolean cullChunks = this.minecraft.chunkCullingEnabled;
        if (spectator && this.level.getBlockState(blockPos).isOpaqueFullCube(this.level, blockPos)) {
            cullChunks = false;
        }

        profiler.push("frustum");
        this.frustum = ((FrustumMixed) frustum).customFrustum().offsetToFullyIncludeCameraCube(8);
        this.sectionGrid.updateFrustumVisibility(this.frustum);
        profiler.pop();

        this.minecraft.getProfiler().swap("partial_update");
        this.initUpdate();
        this.initializeQueueForFullUpdate(camera);

        if (cullChunks) {
            this.updateRenderChunks();
        } else {
            this.updateRenderChunksSpectator();
        }

        IndirectDrawManager.endCullPass();
        this.scheduleRebuilds();
        this.minecraft.getProfiler().pop();
    }

    private void initializeQueueForFullUpdate(Camera camera) {
        Vec3d pos = camera.getPos();
        BlockPos blockPos = camera.getBlockPos();
        RenderSection startSection = this.sectionGrid.getSectionAtBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());

        if (startSection == null) {
            boolean aboveBottom = blockPos.getY() > this.level.getBottomY();
            int y = aboveBottom ? this.level.getTopY() - 8 : this.level.getBottomY() + 8;
            int baseX = MathHelper.floor(pos.x / 16.0) * 16;
            int baseZ = MathHelper.floor(pos.z / 16.0) * 16;

            ArrayList<RenderSection> list = Lists.newArrayList();
            int renderDistance = WorldRenderer.getInstance().getRenderDistance();

            for (int dx = -renderDistance; dx <= renderDistance; dx++) {
                for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                    RenderSection section = this.sectionGrid.getSectionAtBlockPos(
                            baseX + ChunkSectionPos.getOffsetPos(dx, 8),
                            y,
                            baseZ + ChunkSectionPos.getOffsetPos(dz, 8)
                    );
                    if (section != null) {
                        initFirstNode(section, this.lastFrame);
                        list.add(section);
                    }
                }
            }

            this.sectionQueue.ensureCapacity(list.size());
            for (RenderSection section : list) {
                this.sectionQueue.add(section);
            }
        } else {
            initFirstNode(startSection, this.lastFrame);
            this.sectionQueue.add(startSection);
        }
    }

    private static void initFirstNode(RenderSection section, short frame) {
        section.mainDir = 7;
        section.sourceDirs = -128;
        section.directions = -1;
        section.setLastFrame(frame);
        section.visibility |= initVisibility();
        section.directionChanges = 0;
        section.steps = 0;
    }

    private static long initVisibility() {
        long vis = 0L;
        for (int i = 0; i < 6; i++) {
            vis |= 1L << (48 + i);
            vis |= 1L << (56 + i);
        }
        return vis;
    }

    private void initUpdate() {
        this.resetUpdateQueues();
        this.lastFrame = (short) (this.lastFrame + 1);
        this.nonEmptyChunks = 0;
    }

    private void resetUpdateQueues() {
        this.chunkAreaQueue.clear();
        this.sectionGrid.getChunkAreaManager().resetQueues();
        this.sectionQueue.clear();
        this.blockEntitiesSections.clear();
        this.rebuildQueue.clear();
    }

    private void updateRenderChunks() {
        int maxDirChanges = Initializer.CONFIG.advCulling - 1;
        while (this.sectionQueue.hasNext()) {
            RenderSection section = this.sectionQueue.poll();
            if (this.notInFrustum(section)) {
                continue;
            }
            if (HiZPyramidBuilder.isOccludedByHiZ(section.xOffset, section.yOffset, section.zOffset, section.directionChanges, maxDirChanges)) {
                continue;
            }
            if (section.directionChanges > maxDirChanges) {
                continue;
            }

            byte visDirs = section.getVisibilityDirs();
            HiZPyramidBuilder.registerOccluderSection(section.xOffset, section.yOffset, section.zOffset, visDirs);

            if (!section.isCompletelyEmpty()) {
                section.getChunkArea().sectionQueue.add(section);
                this.chunkAreaQueue.add(section.getChunkArea());
                IndirectDrawManager.recordVisibleSection(section);
                this.nonEmptyChunks++;
            }

            if (section.containsBlockEntities()) {
                this.blockEntitiesSections.ensureCapacity(1);
                this.blockEntitiesSections.add(section);
            }

            if (section.isDirty()) {
                this.rebuildQueue.ensureCapacity(1);
                this.rebuildQueue.add(section);
            }

            byte dirs = (byte) (visDirs & section.getDirections());
            this.visitAdjacentNodes(section, dirs);
        }
    }

    private void scheduleRebuilds() {
        int size = this.rebuildQueue.size();
        if (size == 0) {
            return;
        }

        boolean alwaysDefer = true;
        try {
            if (SodiumClientMod.options() != null) {
                alwaysDefer = SodiumClientMod.options().performance.alwaysDeferChunkUpdates;
            }
        } catch (Throwable ignored) {
        }

        ChunkBuilderMode builderMode = this.minecraft.options.getChunkBuilderMode().getValue();
        final int maxLowPriorityQueued = 16;
        final int maxAsyncSchedulesPerFrame = ApuBudgetController.getMaxRebuildsPerFrame();
        final long startTimeNs = System.nanoTime();
        final long maxScheduleTimeNs = ApuBudgetController.getScheduleBudgetNs();

        int scheduledLowPriority = 0;
        boolean hasRemainingDirty = false;

        for (int i = 0; i < size; i++) {
            RenderSection section = this.rebuildQueue.get(i);
            boolean playerChanged = section.isDirtyFromPlayer();

            if (!alwaysDefer && playerChanged && builderMode != ChunkBuilderMode.NONE) {
                section.rebuildChunkSync(this.taskDispatcher, this.renderRegionCache);
                section.setNotDirty();
                continue;
            }

            if (playerChanged) {
                section.rebuildChunkAsync(this.taskDispatcher, this.renderRegionCache);
                section.setNotDirty();
                continue;
            }

            // Regulador térmico APU (Dosium): limita las copias de regiones por fotograma según el presupuesto de frame
            if (this.taskDispatcher.getLowPriorityTaskCount() >= maxLowPriorityQueued
                    || scheduledLowPriority >= maxAsyncSchedulesPerFrame
                    || (scheduledLowPriority > 0 && (System.nanoTime() - startTimeNs) >= maxScheduleTimeNs)) {
                hasRemainingDirty = true;
                continue;
            }

            if (GpuNoiseBridge.isEnabled()) {
                GpuNoiseBridge.getOrComputeSectionDensity(section.xOffset >> 4, section.yOffset >> 4, section.zOffset >> 4, 1337);
            }

            section.rebuildChunkAsync(this.taskDispatcher, this.renderRegionCache);
            section.setNotDirty();
            scheduledLowPriority++;
        }

        this.rebuildQueue.clear();

        if (hasRemainingDirty && this.taskDispatcher.getLowPriorityTaskCount() < maxLowPriorityQueued) {
            WorldRenderer.getInstance().scheduleGraphUpdate();
        }
    }

    private boolean notInFrustum(RenderSection section) {
        byte res = section.getChunkArea().inFrustum(section.frustumIndex);
        if (res > -1) {
            return true;
        }
        if (res == -1) {
            return !this.frustum.testFrustum(
                    section.xOffset,
                    section.yOffset,
                    section.zOffset,
                    section.xOffset + 16,
                    section.yOffset + 16,
                    section.zOffset + 16
            );
        }
        return false;
    }

    private void visitAdjacentNodes(RenderSection section, byte dirs) {
        dirs = (byte) (dirs & section.adjDirs);
        if (dirs == 0) {
            return;
        }
        this.sectionQueue.ensureCapacity(6);
        this.checkToAdd(section, section.adjDown, (byte) 0, (byte) 1, dirs);
        this.checkToAdd(section, section.adjUp, (byte) 1, (byte) 0, dirs);
        this.checkToAdd(section, section.adjNorth, (byte) 2, (byte) 3, dirs);
        this.checkToAdd(section, section.adjSouth, (byte) 3, (byte) 2, dirs);
        this.checkToAdd(section, section.adjWest, (byte) 4, (byte) 5, dirs);
        this.checkToAdd(section, section.adjEast, (byte) 5, (byte) 4, dirs);
    }

    private void checkToAdd(RenderSection current, RenderSection adj, byte dir, byte oppositeDir, byte dirs) {
        if ((dirs & (1 << dir)) != 0) {
            this.addNode(current, adj, dir, oppositeDir);
        }
    }

    private void updateRenderChunksSpectator() {
        while (this.sectionQueue.hasNext()) {
            RenderSection section = this.sectionQueue.poll();
            if (this.notInFrustum(section)) {
                continue;
            }

            if (!section.isCompletelyEmpty()) {
                section.getChunkArea().sectionQueue.add(section);
                this.chunkAreaQueue.add(section.getChunkArea());
                IndirectDrawManager.recordVisibleSection(section);
                this.nonEmptyChunks++;
            }

            if (section.isDirty()) {
                this.rebuildQueue.ensureCapacity(1);
                this.rebuildQueue.add(section);
            }

            byte dirs = (byte) (section.adjDirs & section.getDirections());
            this.visitAdjacentNodes(section, dirs);
        }
    }

    private void addNode(RenderSection current, RenderSection next, byte dir, byte oppositeDir) {
        if (next.getLastFrame() != this.lastFrame) {
            next.setLastFrame(this.lastFrame);
            next.mainDir = dir;
            next.sourceDirs = (byte) (1 << dir);
            byte nextSteps = (byte) (current.steps + 1);
            next.directionChanges = (byte) (nextSteps < 10 ? 0 : 127);
            next.steps = nextSteps;
            next.directions = (byte) (current.directions & ~(1 << oppositeDir));
            this.sectionQueue.add(next);
        }

        next.addDir(dir);
        boolean changedDir = (current.sourceDirs & (1 << dir)) == 0 && !current.isCompletelyEmpty();
        byte dirChanges = changedDir ? (byte) (current.directionChanges + 1) : current.directionChanges;
        next.directionChanges = dirChanges < next.directionChanges ? dirChanges : next.directionChanges;
    }

    public AreaSetQueue getChunkAreaQueue() {
        return this.chunkAreaQueue;
    }

    public ResettableQueue<RenderSection> getSectionQueue() {
        return this.sectionQueue;
    }

    public ResettableQueue<RenderSection> getBlockEntitiesSections() {
        return this.blockEntitiesSections;
    }

    public short getLastFrame() {
        return this.lastFrame;
    }

    public String getStatistics() {
        int totalSections = this.sectionGrid.getSectionCount();
        int visibleSections = this.sectionQueue.size();
        int renderDist = WorldRenderer.getInstance().getRenderDistance();
        String taskStats = this.taskDispatcher == null ? "null" : this.taskDispatcher.getStats();
        return String.format("Chunks: %d(%d)/%d D: %d, %s", this.nonEmptyChunks, visibleSections, totalSections, renderDist, taskStats);
    }
}
