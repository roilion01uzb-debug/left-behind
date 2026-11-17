package dev.leftbehind.client.render;

import dev.leftbehind.entity.Remnant;
import net.minecraft.client.model.geom.ModelPart;

/** One shape for all three of the removed ones; only the extra that belongs to this one is shown. */
public class GhostModel extends PersonModel {
    private final ModelPart frogEyeRight;
    private final ModelPart frogEyeLeft;
    private final ModelPart hairPuff;
    private final ModelPart hairTuft;

    public GhostModel(ModelPart root) {
        super(root);
        this.frogEyeRight = this.head.getChild("frog_eye_right");
        this.frogEyeLeft = this.head.getChild("frog_eye_left");
        this.hairPuff = this.head.getChild("hair_puff");
        this.hairTuft = this.head.getChild("hair_tuft");
    }

    @Override
    public void setupAnim(PersonRenderState state) {
        super.setupAnim(state);
        this.frogEyeRight.visible = state.variant == Remnant.RANA;
        this.frogEyeLeft.visible = state.variant == Remnant.RANA;
        this.hairPuff.visible = state.variant == Remnant.BLACK_STEVE;
        this.hairTuft.visible = state.variant == Remnant.BEAST_BOY;
        // They hold one stiff pose: arms slightly out, like a figure that was never animated.
        this.rightArm.xRot = 0.0F;
        this.leftArm.xRot = 0.0F;
        this.rightArm.zRot = 0.1F;
        this.leftArm.zRot = -0.1F;
        this.rightLeg.xRot = 0.0F;
        this.leftLeg.xRot = 0.0F;
    }
}
