package net.vulkanmod.render.chunk.build;

import it.unimi.dsi.fastutil.objects.Object2ByteLinkedOpenHashMap;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockRenderView;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.light.data.QuadLightData;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.model.quad.QuadUtils;
import net.vulkanmod.render.model.quad.QuadView;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.VertexUtil;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Vector3f;

import java.util.List;

public class BlockRenderer {
    static final Direction[] DIRECTIONS = Direction.values();
    private static BlockColors blockColors;

    Random randomSource = Random.create();
    Vector3f pos;
    BlockPos blockPos;
    BlockPos.Mutable mutableBlockPos = new BlockPos.Mutable();
    BuilderResources resources;
    BlockState blockState;

    final Object2ByteLinkedOpenHashMap<Block.NeighborGroup> occlusionCache = new Object2ByteLinkedOpenHashMap<>(2048, 0.25f) {
        @Override
        protected void rehash(int newN) {
        }
    };

    public BlockRenderer() {
        this.occlusionCache.defaultReturnValue((byte) 127);
    }

    public void setResources(BuilderResources resources) {
        this.resources = resources;
    }

    public static void setBlockColors(BlockColors blockColors) {
        BlockRenderer.blockColors = blockColors;
    }

    public void renderBatched(BlockState blockState, BlockPos blockPos, Vector3f pos, TerrainBufferBuilder bufferBuilder) {
        this.pos = pos;
        this.blockPos = blockPos;
        this.blockState = blockState;
        long seed = blockState.getRenderingSeed(blockPos);
        BakedModel model = MinecraftClient.getInstance().getBlockRenderManager().getModel(blockState);
        this.tessellateBlock(model, bufferBuilder, seed);
    }

    public void tessellateBlock(BakedModel model, TerrainBufferBuilder bufferBuilder, long seed) {
        Vec3d offset = this.blockState.getModelOffset(this.resources.region, this.blockPos);
        this.pos.add((float) offset.x, (float) offset.y, (float) offset.z);

        boolean useAo = MinecraftClient.isAmbientOcclusionEnabled()
                && this.blockState.getLuminance() == 0
                && model.useAmbientOcclusion();
        LightPipeline lightPipeline = useAo ? this.resources.smoothLightPipeline : this.resources.flatLightPipeline;

        for (Direction direction : DIRECTIONS) {
            this.randomSource.setSeed(seed);
            List<BakedQuad> quads = model.getQuads(this.blockState, direction, this.randomSource);
            if (!quads.isEmpty()) {
                this.mutableBlockPos.set(this.blockPos, direction);
                if (this.shouldRenderFace(this.blockState, direction, this.mutableBlockPos)) {
                    this.renderModelFace(bufferBuilder, quads, lightPipeline, direction);
                }
            }
        }

        this.randomSource.setSeed(seed);
        List<BakedQuad> quads = model.getQuads(this.blockState, null, this.randomSource);
        if (!quads.isEmpty()) {
            this.renderModelFace(bufferBuilder, quads, lightPipeline, null);
        }
    }

    private void renderModelFace(TerrainBufferBuilder bufferBuilder, List<BakedQuad> quads, LightPipeline lightPipeline, Direction face) {
        QuadLightData quadLightData = this.resources.quadLightData;
        for (int i = 0, size = quads.size(); i < size; i++) {
            BakedQuad bakedQuad = quads.get(i);
            QuadView quadView = (QuadView) bakedQuad;
            lightPipeline.calculate(quadView, this.blockPos, quadLightData, face, bakedQuad.getFace(), bakedQuad.hasShade());
            this.putQuadData(bufferBuilder, quadView, quadLightData);
        }
    }

    private void putQuadData(TerrainBufferBuilder bufferBuilder, QuadView quadView, QuadLightData quadLightData) {
        float r;
        float g;
        float b;
        if (quadView.isTinted()) {
            int color = blockColors.getColor(this.blockState, this.resources.region, this.blockPos, quadView.getColorIndex());
            r = ColorUtil.ARGB.unpackR(color);
            g = ColorUtil.ARGB.unpackG(color);
            b = ColorUtil.ARGB.unpackB(color);
        } else {
            r = 1.0f;
            g = 1.0f;
            b = 1.0f;
        }

        float alpha = 1.0f;
        if (this.blockState.getBlock() instanceof LeavesBlock) {
            boolean fancyLeaves = SodiumClientMod.options().quality.leavesQuality.isFancy(
                    MinecraftClient.getInstance().options.getGraphicsMode().getValue()
            );
            if (!fancyLeaves) {
                // Fast leaves: encode 240/255 alpha so terrain.fsh renders transparent leaf pixels as solid opaque black
                alpha = 240.0f / 255.0f;
            }
        }

        putQuadData(bufferBuilder, this.pos, quadView, quadLightData, r, g, b, alpha);
    }

    public static void putQuadData(TerrainBufferBuilder bufferBuilder, Vector3f pos, QuadView quad, QuadLightData quadLightData, float red, float green, float blue) {
        putQuadData(bufferBuilder, pos, quad, quadLightData, red, green, blue, 1.0f);
    }

    public static void putQuadData(TerrainBufferBuilder bufferBuilder, Vector3f pos, QuadView quad, QuadLightData quadLightData, float red, float green, float blue, float alpha) {
        Vec3i facingVec = quad.getFacingDirection().getVector();
        int packedNormal = VertexUtil.packNormal(facingVec.getX(), facingVec.getY(), facingVec.getZ());
        float[] br = quadLightData.br;
        int[] lm = quadLightData.lm;
        int idx = QuadUtils.getIterationStartIdx(br, lm);
        bufferBuilder.ensureCapacity();

        for (byte i = 0; i < 4; i++) {
            float x = pos.x() + quad.getX(idx);
            float y = pos.y() + quad.getY(idx);
            float z = pos.z() + quad.getZ(idx);

            int quadColor = quad.getColor(idx);
            float qR = ColorUtil.RGBA.unpackR(quadColor);
            float qG = ColorUtil.RGBA.unpackG(quadColor);
            float qB = ColorUtil.RGBA.unpackB(quadColor);

            float lightBr = br[idx];
            float fR = qR * lightBr * red;
            float fG = qG * lightBr * green;
            float fB = qB * lightBr * blue;

            int color = ColorUtil.RGBA.pack(fR, fG, fB, alpha);
            int light = lm[idx];
            float u = quad.getU(idx);
            float v = quad.getV(idx);

            bufferBuilder.vertex(x, y, z, color, u, v, light, packedNormal);
            idx = (idx + 1) & 3;
        }
    }

    public boolean shouldRenderFace(BlockState state, Direction face, BlockPos adjPos) {
        if (!SodiumClientMod.options().performance.useBlockFaceCulling) {
            return true;
        }

        BlockRenderView region = this.resources.region;
        BlockState adjState = region.getBlockState(adjPos);
        if (state.isSideInvisible(adjState, face)) {
            return false;
        }

        if (adjState.isOpaque()) {
            VoxelShape shape = state.getCullingFace(region, this.blockPos, face);
            if (shape.isEmpty()) {
                return true;
            }
            VoxelShape adjShape = adjState.getCullingFace(region, adjPos, face.getOpposite());
            if (adjShape.isEmpty()) {
                return true;
            }
            if (shape == VoxelShapes.fullCube() && adjShape == VoxelShapes.fullCube()) {
                return false;
            }

            Block.NeighborGroup group = new Block.NeighborGroup(state, adjState, face);
            byte cached = this.occlusionCache.getAndMoveToFirst(group);
            if (cached != 127) {
                return cached != 0;
            }

            boolean visible = VoxelShapes.matchesAnywhere(shape, adjShape, BooleanBiFunction.ONLY_FIRST);
            if (this.occlusionCache.size() == 2048) {
                this.occlusionCache.removeLastByte();
            }
            this.occlusionCache.putAndMoveToFirst(group, (byte) (visible ? 1 : 0));
            return visible;
        }

        return true;
    }
}
