package net.vulkanmod.render.chunk.build;

import com.dosium.core.ApuBudgetController;
import com.dosium.meshing.ComputeMeshDispatcher;
import com.google.common.collect.Queues;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.build.task.CompileResult;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.TerrainRenderType;

import java.util.EnumMap;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskDispatcher {
    private final Queue<CompileResult> compileResults = Queues.newLinkedBlockingDeque();
    public final ThreadBuilderPack fixedBuffers = new ThreadBuilderPack();
    private volatile boolean stopThreads = true;
    private Thread[] threads;
    private BuilderResources[] resources;
    private int idleThreads;
    private final Queue<ChunkTask> highPriorityTasks = Queues.newConcurrentLinkedQueue();
    private final Queue<ChunkTask> lowPriorityTasks = Queues.newConcurrentLinkedQueue();
    private final AtomicInteger highPriorityCount = new AtomicInteger(0);
    private final AtomicInteger lowPriorityCount = new AtomicInteger(0);

    public TaskDispatcher() {
    }

    public void createThreads() {
        int configured = 0;
        try {
            if (SodiumClientMod.options() != null) {
                configured = SodiumClientMod.options().performance.chunkBuilderThreads;
            }
        } catch (Throwable ignored) {
        }

        int n = ComputeMeshDispatcher.getRecommendedThreadCount(configured);
        this.createThreads(n);
    }

    public void createThreads(int n) {
        if (!this.stopThreads) {
            this.stopThreads();
        }
        this.stopThreads = false;

        if (this.resources != null) {
            for (BuilderResources res : this.resources) {
                res.clear();
            }
        }

        this.threads = new Thread[n];
        this.resources = new BuilderResources[n];

        // Prioridad reducida para no competir con el hilo de Render ni reducir relojes en APUs de 2 núcleos
        int workerPriority = Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2);

        for (int i = 0; i < n; i++) {
            BuilderResources builderResources = new BuilderResources();
            Thread thread = new Thread(() -> this.runTaskThread(builderResources), "Dosium-Mesher-" + i);
            thread.setPriority(workerPriority);
            thread.setDaemon(true);
            this.threads[i] = thread;
            this.resources[i] = builderResources;
            thread.start();
        }
    }

    private void runTaskThread(BuilderResources builderResources) {
        while (!this.stopThreads) {
            ChunkTask task = this.pollTask();
            if (task == null) {
                synchronized (this) {
                    this.idleThreads++;
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    this.idleThreads--;
                }
            }
            if (task == null) {
                continue;
            }
            try {
                long startNs = System.nanoTime();
                task.runTask(builderResources);
                ComputeMeshDispatcher.recordSectionCompileTimeNs(System.nanoTime() - startNs);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    public void schedule(ChunkTask task) {
        if (task == null) {
            return;
        }
        if (task.highPriority) {
            this.highPriorityTasks.offer(task);
            this.highPriorityCount.incrementAndGet();
        } else {
            this.lowPriorityTasks.offer(task);
            this.lowPriorityCount.incrementAndGet();
        }
        synchronized (this) {
            this.notify();
        }
    }

    private ChunkTask pollTask() {
        ChunkTask task = this.highPriorityTasks.poll();
        if (task != null) {
            this.highPriorityCount.updateAndGet(v -> Math.max(0, v - 1));
            return task;
        }
        task = this.lowPriorityTasks.poll();
        if (task != null) {
            this.lowPriorityCount.updateAndGet(v -> Math.max(0, v - 1));
        }
        return task;
    }

    public int getLowPriorityTaskCount() {
        return this.lowPriorityCount.get();
    }

    public int getHighPriorityTaskCount() {
        return this.highPriorityCount.get();
    }

    public void stopThreads() {
        if (this.stopThreads) {
            return;
        }
        this.stopThreads = true;
        synchronized (this) {
            this.notifyAll();
        }
        for (Thread t : this.threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public boolean updateSections() {
        boolean updated = false;
        int fullUploads = 0;
        final int maxFullUploadsPerFrame = ApuBudgetController.getMaxUploadsPerFrame();
        final long startTimeNs = System.nanoTime();
        final long maxUploadTimeNs = ApuBudgetController.getUploadBudgetNs();

        CompileResult result;
        while ((result = this.compileResults.poll()) != null) {
            updated = true;
            this.doSectionUpdate(result);
            if (result.fullUpdate) {
                fullUploads++;
                if (fullUploads >= maxFullUploadsPerFrame || (System.nanoTime() - startTimeNs) >= maxUploadTimeNs) {
                    break;
                }
            }
        }

        if (!this.compileResults.isEmpty()) {
            updated = true;
        }
        return updated;
    }

    public void scheduleSectionUpdate(CompileResult result) {
        this.compileResults.add(result);
    }

    private void doSectionUpdate(CompileResult result) {
        RenderSection section = result.renderSection;
        ChunkArea chunkArea = section.getChunkArea();
        if (chunkArea == null) {
            return;
        }
        DrawBuffers drawBuffers = chunkArea.getDrawBuffers();
        ChunkAreaManager areaManager = WorldRenderer.getInstance().getChunkAreaManager();
        if (areaManager.getChunkArea(chunkArea.index) != chunkArea) {
            return;
        }

        if (result.fullUpdate) {
            EnumMap<TerrainRenderType, UploadBuffer> renderedLayers = result.renderedLayers;
            for (TerrainRenderType renderType : TerrainRenderType.VALUES) {
                UploadBuffer uploadBuffer = renderedLayers.get(renderType);
                if (uploadBuffer != null) {
                    drawBuffers.upload(section, uploadBuffer, renderType);
                } else {
                    section.getDrawParameters(renderType).reset(chunkArea, renderType);
                }
            }
            result.updateSection();
        } else {
            UploadBuffer translucentBuffer = result.renderedLayers.get(TerrainRenderType.TRANSLUCENT);
            if (translucentBuffer != null) {
                drawBuffers.upload(section, translucentBuffer, TerrainRenderType.TRANSLUCENT);
            }
        }
    }

    public boolean isIdle() {
        return this.idleThreads == this.threads.length && this.compileResults.isEmpty();
    }

    public void clearBatchQueue() {
        while (!this.highPriorityTasks.isEmpty()) {
            ChunkTask task = this.highPriorityTasks.poll();
            if (task != null) {
                task.cancel();
            }
        }
        this.highPriorityCount.set(0);

        while (!this.lowPriorityTasks.isEmpty()) {
            ChunkTask task = this.lowPriorityTasks.poll();
            if (task != null) {
                task.cancel();
            }
        }
        this.lowPriorityCount.set(0);
    }

    public String getStats() {
        int totalTasks = this.highPriorityCount.get() + this.lowPriorityCount.get();
        return String.format("iT: %d Ts: %d", this.idleThreads, totalTasks);
    }

    public BuilderResources[] getResourcesArray() {
        return this.resources;
    }
}
