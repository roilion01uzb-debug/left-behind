package dev.leftbehind.client.talk;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.leftbehind.client.render.PersonRenderState;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;

/** Draws a speaker's current line above their head, one name-tag row per line of text. */
public final class SpeechBubble {
    /** Further than this, the words are too small to read anyway. */
    private static final double RANGE = 28.0;

    private SpeechBubble() {}

    public static void extract(Entity entity, PersonRenderState state, float partial) {
        state.speech = null;
        Entity camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null || camera.distanceToSqr(entity) > RANGE * RANGE) return;
        state.speech = SpeechClient.bubble(entity.getId(), partial);
        if (state.speech != null) {
            state.speechAttachment = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partial));
        }
    }

    public static void submit(PersonRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        List<Component> rows = state.speech;
        if (rows == null || rows.isEmpty() || state.speechAttachment == null) return;
        poseStack.pushPose();
        // Bottom row first, then step up for each row above it.
        for (int i = rows.size() - 1; i >= 0; i--) {
            collector.submitNameTag(poseStack, state.speechAttachment, 0, rows.get(i), true, LightCoordsUtil.FULL_BRIGHT, camera);
            poseStack.translate(0.0F, 9.0F * 1.15F * 0.025F, 0.0F);
        }
        poseStack.popPose();
    }
}
