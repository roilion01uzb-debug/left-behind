package dev.leftbehind.client.render;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import dev.leftbehind.client.talk.SpeechBubble;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.leftbehind.entity.OriginalSteve;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;

/** Original Steve wears the classic Steve skin. That's the point. */
public class OriginalSteveRenderer extends HumanoidMobRenderer<OriginalSteve, PersonRenderState, PersonModel> {
    private static final Identifier STEVE = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public OriginalSteveRenderer(EntityRendererProvider.Context context) {
        super(context, new SteveModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public PersonRenderState createRenderState() {
        return new PersonRenderState();
    }

    @Override
    public void extractRenderState(OriginalSteve entity, PersonRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.pose = entity.getState();
        SpeechBubble.extract(entity, state, partialTicks);
        if (state.pose == OriginalSteve.STATE_COWER || state.pose == OriginalSteve.STATE_STARE) {
            state.walkAnimationSpeed = 0.0F;
        }
    }

    @Override
    public Identifier getTextureLocation(PersonRenderState state) {
        return STEVE;
    }

    @Override
    protected boolean isShaking(PersonRenderState state) {
        return super.isShaking(state) || state.pose == OriginalSteve.STATE_COWER;
    }

    @Override
    protected void scale(PersonRenderState state, PoseStack poseStack) {
        super.scale(state, poseStack);
        // Sitting: the whole body drops so his legs rest on the ground.
        if (state.pose == OriginalSteve.STATE_COWER) {
            poseStack.translate(0.0F, 0.55F, 0.0F);
        }
    }

    @Override
    protected void submitNameDisplay(PersonRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        super.submitNameDisplay(state, poseStack, collector, camera);
        SpeechBubble.submit(state, poseStack, collector, camera);
    }
}
