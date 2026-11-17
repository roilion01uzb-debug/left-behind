package dev.leftbehind.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.leftbehind.LeftBehind;
import dev.leftbehind.entity.Remnant;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The removed ones. Drawn see-through and faintly lit from inside, in a single pose:
 * they never had walking animations, so their legs never move.
 */
public class RemnantRenderer extends HumanoidMobRenderer<Remnant, PersonRenderState, PersonModel> {
    private static final Identifier[] SKINS = {
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/entity/rana.png"),
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/entity/black_steve.png"),
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "textures/entity/beast_boy.png"),
    };
    public RemnantRenderer(EntityRendererProvider.Context context) {
        super(context, new GhostModel(context.bakeLayer(ModModels.GHOST)), 0.0F);
    }

    @Override
    public PersonRenderState createRenderState() {
        return new PersonRenderState();
    }

    @Override
    public void extractRenderState(Remnant entity, PersonRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        // One pose. No steps, no swinging arms.
        state.walkAnimationPos = 0.0F;
        state.walkAnimationSpeed = 0.0F;
        state.variant = entity.getVariant();
        state.fade = Mth.clamp(entity.getFade() / 20.0F, 0.0F, 1.0F);
    }

    @Override
    public Identifier getTextureLocation(PersonRenderState state) {
        return SKINS[Math.floorMod(state.variant, SKINS.length)];
    }

    @Override
    protected @Nullable RenderType getRenderType(PersonRenderState state, boolean isBodyVisible, boolean forceTransparent, boolean appearGlowing) {
        return RenderTypes.entityTranslucent(getTextureLocation(state));
    }

    @Override
    protected int getModelTint(PersonRenderState state) {
        float fade = state.fade;
        float flicker = 0.62F + Mth.sin(state.ageInTicks * 0.21F) * 0.06F;
        return ARGB.white(Mth.clamp(flicker * (1.0F - fade), 0.0F, 1.0F));
    }

    @Override
    protected int getBlockLightLevel(Remnant entity, BlockPos pos) {
        return Math.max(11, super.getBlockLightLevel(entity, pos));
    }

    @Override
    protected void scale(PersonRenderState state, PoseStack poseStack) {
        super.scale(state, poseStack);
        // They hang a little above the ground and drift up and down.
        poseStack.translate(0.0F, -0.12F + Mth.sin(state.ageInTicks * 0.06F) * 0.05F, 0.0F);
    }
}
