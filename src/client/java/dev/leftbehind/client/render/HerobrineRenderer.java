package dev.leftbehind.client.render;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.entity.Herobrine;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** Steve's body, with eyes that glow in the dark. */
public class HerobrineRenderer extends SteveLikeRenderer<Herobrine> {
    private static final RenderType EYES = RenderTypes.eyes(
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/entity/herobrine_eyes.png"));

    public HerobrineRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.addLayer(new GlowingEyes(this));
    }

    static class GlowingEyes extends EyesLayer<AvatarRenderState, PlayerModel> {
        GlowingEyes(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
            super(parent);
        }

        @Override
        public RenderType renderType() {
            return EYES;
        }
    }
}
