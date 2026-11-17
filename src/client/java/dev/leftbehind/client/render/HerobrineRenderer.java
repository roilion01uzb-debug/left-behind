package dev.leftbehind.client.render;

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

/**
 * Herobrine has his own skin, with the blank white eyes painted on, so he looks right under any shader pack.
 * A second, tiny model draws just the two eyes again at full brightness, so they glow in the dark.
 */
public class HerobrineRenderer extends HumanoidMobRenderer<Herobrine, PersonRenderState, PersonModel> {
    private static final Identifier SKIN = Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/entity/herobrine.png");
    private static final RenderType EYES = RenderTypes.eyes(
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/entity/herobrine_eyes.png"));

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

    static class GlowingEyes extends RenderLayer<PersonRenderState, PersonModel> {
        private final HumanoidModel<PersonRenderState> eyes;

        GlowingEyes(RenderLayerParent<PersonRenderState, PersonModel> parent, HumanoidModel<PersonRenderState> eyes) {
            super(parent);
            this.eyes = eyes;
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, PersonRenderState state, float yRot, float xRot) {
            collector.order(1).submitModel(this.eyes, state, poseStack, EYES, lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        }
    }
}
