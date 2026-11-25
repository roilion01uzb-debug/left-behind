package dev.leftbehind.client.render;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import dev.leftbehind.client.talk.SpeechBubble;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.leftbehind.LeftBehind;
import dev.leftbehind.entity.Herobrine;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;

/**
 * The original Herobrine: Steve's own skin, with nothing behind the eyes.
 * He wears whatever the game's Steve skin is, so a resource pack that changes Steve changes him too.
 * A second, tiny model covers the two eyes with plain white at full brightness, then again with the
 * game's glowing-eyes effect (like an enderman's), so they stay white by day and glow at night under any shader pack.
 */
public class HerobrineRenderer extends HumanoidMobRenderer<Herobrine, PersonRenderState, PersonModel> {
    private static final Identifier SKIN = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/entity/herobrine_eyes.png");
    private static final RenderType BLANK = RenderTypes.entityCutout(WHITE);
    private static final RenderType GLOW = RenderTypes.eyes(WHITE);

    public HerobrineRenderer(EntityRendererProvider.Context context) {
        super(context, new PersonModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.addLayer(new GlowingEyes(this, new HumanoidModel<>(context.bakeLayer(ModModels.HEROBRINE_EYES))));
    }

    @Override
    public PersonRenderState createRenderState() {
        return new PersonRenderState();
    }

    @Override
    public Identifier getTextureLocation(PersonRenderState state) {
        return SKIN;
    }

    @Override
    public void extractRenderState(Herobrine entity, PersonRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.masked = entity.isMasked();
        SpeechBubble.extract(entity, state, partialTicks);
    }

    @Override
    protected void submitNameDisplay(PersonRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        super.submitNameDisplay(state, poseStack, collector, camera);
        SpeechBubble.submit(state, poseStack, collector, camera);
    }


    static class GlowingEyes extends RenderLayer<PersonRenderState, PersonModel> {
        private final HumanoidModel<PersonRenderState> eyes;

        GlowingEyes(RenderLayerParent<PersonRenderState, PersonModel> parent, HumanoidModel<PersonRenderState> eyes) {
            super(parent);
            this.eyes = eyes;
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, PersonRenderState state, float yRot, float xRot) {
            if (state.masked) return;
            collector.order(1).submitModel(this.eyes, state, poseStack, BLANK, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor);
            collector.order(2).submitModel(this.eyes, state, poseStack, GLOW, lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        }
    }
}
