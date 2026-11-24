package dev.leftbehind.client.fx;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.fx.FxPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/**
 * What the screen does when he is near: a vignette that closes in as he becomes aware of you,
 * static, a pair of eyes, and the cut to black.
 */
public final class ScreenFx {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "screen_fx");
    private static final Identifier VIGNETTE = Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/gui/vignette.png");
    private static final Identifier EYES = Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/gui/eyes.png");
    private static final Identifier[] STATIC = {
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/gui/static_0.png"),
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/gui/static_1.png"),
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/gui/static_2.png"),
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/gui/static_3.png"),
    };

    private static float dreadTarget;
    private static float dread;
    private static int staticTicks;
    private static int eyesTicks;
    private static int blackoutTicks;
    private static int blackoutTotal;
    private static int shakeTicks;
    private static int age;

    private ScreenFx() {}

    public static void receive(FxPayload payload) {
        int ticks = Math.round(payload.value());
        switch (payload.kind()) {
            case FxPayload.DREAD -> dreadTarget = Mth.clamp(payload.value(), 0.0F, 1.0F);
            case FxPayload.STATIC -> staticTicks = Math.max(staticTicks, ticks);
            case FxPayload.EYES -> eyesTicks = Math.max(eyesTicks, ticks);
            case FxPayload.BLACKOUT -> {
                blackoutTicks = Math.max(blackoutTicks, ticks);
                blackoutTotal = blackoutTicks;
            }
            case FxPayload.SHAKE -> shakeTicks = Math.max(shakeTicks, ticks);
            case FxPayload.MUSIC -> ScoreClient.play(ticks);
            default -> { }
        }
    }

    public static void reset() {
        dreadTarget = 0.0F;
        dread = 0.0F;
        staticTicks = 0;
        eyesTicks = 0;
        blackoutTicks = 0;
        shakeTicks = 0;
    }

    public static void tick(Minecraft client) {
        if (client.level == null) {
            reset();
            return;
        }
        if (client.isPaused()) return;
        age++;
        dread += (dreadTarget - dread) * 0.12F;
        if (dread < 0.005F && dreadTarget == 0.0F) dread = 0.0F;
        if (staticTicks > 0) staticTicks--;
        if (eyesTicks > 0) eyesTicks--;
        if (blackoutTicks > 0) blackoutTicks--;
        if (shakeTicks > 0) shakeTicks--;
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        float time = age + delta.getGameTimeDeltaPartialTick(true);
        if (dread > 0.01F || shakeTicks > 0) {
            // The edges of the screen close in, and pulse like a heartbeat as it gets worse.
            float beat = dread > 0.35F ? (float) Math.pow(Math.max(0.0, Math.sin(time * (0.18F + dread * 0.22F))), 6.0) * 0.18F : 0.0F;
            float alpha = Mth.clamp(dread * 0.82F + beat + (shakeTicks > 0 ? 0.25F : 0.0F), 0.0F, 0.97F);
            int jitterX = shakeTicks > 0 ? (int) (Math.sin(time * 7.3) * 3.0) : 0;
            int jitterY = shakeTicks > 0 ? (int) (Math.cos(time * 9.1) * 3.0) : 0;
            graphics.blit(RenderPipelines.GUI_TEXTURED, VIGNETTE, jitterX - 4, jitterY - 4, 0.0F, 0.0F, w + 8, h + 8, w + 8, h + 8, ARGB.white(alpha));
            if (dread > 0.75F) graphics.fill(0, 0, w, h, ARGB.color((dread - 0.75F) * 0.5F, 0x000000));
        }
        if (staticTicks > 0) {
            Identifier frame = STATIC[Math.floorMod((int) (time * 1.7F), STATIC.length)];
            float alpha = Mth.clamp(0.1F + staticTicks * 0.02F, 0.0F, 0.38F);
            graphics.blit(RenderPipelines.GUI_TEXTURED, frame, 0, 0, 0.0F, 0.0F, w, h, w, h, ARGB.white(alpha));
        }
        if (blackoutTicks > 0) {
            // Holds black, then lets the world back in over the last second.
            float alpha = Mth.clamp(blackoutTicks / 20.0F, 0.0F, 1.0F);
            graphics.fill(0, 0, w, h, ARGB.color(alpha, 0x000000));
        }
        if (eyesTicks > 0) {
            graphics.fill(0, 0, w, h, ARGB.color(0.82F, 0x000000));
            int size = Math.min(w, h * 2) * 3 / 5;
            int eh = size / 2;
            graphics.blit(RenderPipelines.GUI_TEXTURED, EYES, (w - size) / 2, (h - eh) / 2 - h / 12, 0.0F, 0.0F, size, eh, size, eh, ARGB.white(1.0F));
        }
    }
}
