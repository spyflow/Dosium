package net.vulkanmod.render.chunk.build.light;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.vulkanmod.render.chunk.build.light.data.QuadLightData;
import net.vulkanmod.render.model.quad.QuadView;

public interface LightPipeline {
    void calculate(QuadView quad, BlockPos pos, QuadLightData out, Direction cullFace, Direction lightFace, boolean shade);
}
