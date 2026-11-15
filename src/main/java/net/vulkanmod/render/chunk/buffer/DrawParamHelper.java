package net.vulkanmod.render.chunk.buffer;

/**
 * Acceso de alto rendimiento a los campos de DrawParameters desde fuera del paquete.
 */
public final class DrawParamHelper {
    private DrawParamHelper() {
    }

    public static int getIndexCount(DrawBuffers.DrawParameters p) {
        return p != null ? p.indexCount : 0;
    }

    public static int getFirstIndex(DrawBuffers.DrawParameters p) {
        return p != null ? p.firstIndex : 0;
    }

    public static int getVertexOffset(DrawBuffers.DrawParameters p) {
        return p != null ? p.vertexOffset : 0;
    }

    public static int getBaseInstance(DrawBuffers.DrawParameters p) {
        return p != null ? p.baseInstance : 0;
    }
}
