package SpaceUtils.rangedisplay;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import SpaceUtils.rangedisplay.Gui.Theme;
import SpaceUtils.rangedisplay.Discord.DiscordPresence;
import SpaceUtils.rangedisplay.Render.WorldRenderer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class RangeDisplay implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("RangeDisplay");
    private static final double LIFT = 0.1;
    private static final double RING_LIFT = 0.008;
    private static final double PLAYER_RADIUS_SQR = 12.0 * 12.0;

    private final List<Player> nearby = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        LOGGER.info("RangeDisplay initialized");
        DiscordPresence.start();
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::render);
    }

    private void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.gameRenderer.getMainCamera() == null) return;

        PoseStack matrices = new PoseStack();

        Vec3 cam = mc.gameRenderer.getMainCamera().position();

        List<Player> attackers = nearby;
        attackers.clear();
        attackers.add(mc.player);

        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            if (p.isRemoved() || !p.isAlive() || p.isSpectator()) continue;
            if (p.distanceToSqr(mc.player) > PLAYER_RADIUS_SQR) continue;
            attackers.add(p);
        }

        MultiBufferSource consumers = ctx.consumers();
        if (consumers == null) return;

        matrices.pushPose();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        try {
            PoseStack.Pose pose = matrices.last();
            VertexConsumer quads = consumers.getBuffer(RenderTypes.debugQuads());
            float tickProgress = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

            for (Player attacker : attackers) {
                if (attacker.isRemoved() || !attacker.isAlive()) continue;
                double range = getRange(attacker);
                if (range <= 0 || range > 12) range = 3.0;
                boolean danger = hasPlayerInRange(attacker, range);

                int fill = danger ? Theme.DANGER_FILL : Theme.SAFE_FILL;
                int edge = danger ? Theme.DANGER_EDGE : Theme.SAFE_EDGE;
                int segments = WorldRenderer.segmentsFor(range);
                double x = lerp(attacker.xOld, attacker.getX(), tickProgress);
                double cy = lerp(attacker.yOld, attacker.getY(), tickProgress) + LIFT;
                double z = lerp(attacker.zOld, attacker.getZ(), tickProgress);

                WorldRenderer.drawDisc(quads, pose, x, cy, z, range, segments, fill);
                WorldRenderer.drawRing(quads, pose, x, cy + RING_LIFT, z, range, segments, edge);
            }
        } finally {
            matrices.popPose();
        }
    }

    private static double lerp(double oldValue, double value, float progress) {
        return oldValue + (value - oldValue) * progress;
    }

    private double getRange(LivingEntity e) {
        try {
            double v = e.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
            if (v > 0 && v <= 12) return v;
        } catch (Exception ignored) {
        }
        return 3.0;
    }

    private boolean hasPlayerInRange(Player attacker, double range) {
        for (Player t : nearby) {
            if (t == attacker) continue;
            if (t.isRemoved() || !t.isAlive()) continue;
            if (t.isSpectator()) continue;
            if (t.isInvisible()) continue;
            if (t.isPassenger() && t.getVehicle() == attacker) continue;
            if (attacker.isPassenger() && attacker.getVehicle() == t) continue;

            double dx = t.getX() - attacker.getX();
            double dz = t.getZ() - attacker.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz) - t.getBbWidth() * 0.5;
            if (hDist > range) continue;

            double yMidT = t.getY() + t.getBbHeight() * 0.5;
            double yMidA = attacker.getY() + attacker.getBbHeight() * 0.5;
            if (Math.abs(yMidT - yMidA) > 2.5) continue;

            return true;
        }
        return false;
    }
}
