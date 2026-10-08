package SpaceUtils.rangedisplay.Render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

public final class WorldRenderer {
    private static final double RING_HALF = 0.06;

    private WorldRenderer() {
    }

    public static int segmentsFor(double radius) {
        int segments = (int) (radius * 16.0);
        if (segments < 24) {
            return 24;
        }
        return Math.min(segments, 72);
    }

    public static void drawDisc(VertexConsumer buffer, PoseStack.Pose pose,
            double cx, double cy, double cz, double radius, int segments, int argb) {
        float y = (float) cy;
        float centerX = (float) cx;
        float centerZ = (float) cz;
        int r = red(argb);
        int g = green(argb);
        int b = blue(argb);
        int a = alpha(argb);
        for (int i = 0; i < segments; i++) {
            double first = (Math.PI * 2.0 * i) / segments;
            double second = (Math.PI * 2.0 * (i + 1)) / segments;
            float x1 = (float) (cx + Math.cos(first) * radius);
            float z1 = (float) (cz + Math.sin(first) * radius);
            float x2 = (float) (cx + Math.cos(second) * radius);
            float z2 = (float) (cz + Math.sin(second) * radius);

            buffer.addVertex(pose, centerX, y, centerZ).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x2, y, z2).setColor(r, g, b, a);
            buffer.addVertex(pose, centerX, y, centerZ).setColor(r, g, b, a);
        }
    }

    public static void drawRing(VertexConsumer buffer, PoseStack.Pose pose,
            double cx, double cy, double cz, double radius, int segments, int argb) {
        float y = (float) cy;
        int r = red(argb);
        int g = green(argb);
        int b = blue(argb);
        int a = alpha(argb);
        for (int i = 0; i < segments; i++) {
            double first = (Math.PI * 2.0 * i) / segments;
            double second = (Math.PI * 2.0 * (i + 1)) / segments;
            float ix1 = (float) (cx + Math.cos(first) * (radius - RING_HALF));
            float iz1 = (float) (cz + Math.sin(first) * (radius - RING_HALF));
            float ox1 = (float) (cx + Math.cos(first) * (radius + RING_HALF));
            float oz1 = (float) (cz + Math.sin(first) * (radius + RING_HALF));
            float ix2 = (float) (cx + Math.cos(second) * (radius - RING_HALF));
            float iz2 = (float) (cz + Math.sin(second) * (radius - RING_HALF));
            float ox2 = (float) (cx + Math.cos(second) * (radius + RING_HALF));
            float oz2 = (float) (cz + Math.sin(second) * (radius + RING_HALF));

            buffer.addVertex(pose, ix1, y, iz1).setColor(r, g, b, a);
            buffer.addVertex(pose, ox1, y, oz1).setColor(r, g, b, a);
            buffer.addVertex(pose, ox2, y, oz2).setColor(r, g, b, a);
            buffer.addVertex(pose, ix2, y, iz2).setColor(r, g, b, a);
        }
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }
}
