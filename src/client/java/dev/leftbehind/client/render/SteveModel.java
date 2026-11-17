package dev.leftbehind.client.render;

import dev.leftbehind.entity.OriginalSteve;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/** The player shape, with the poses Steve falls into when the memory has him. */
public class SteveModel extends PersonModel {
    public SteveModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(PersonRenderState state) {
        super.setupAnim(state);
        float age = state.ageInTicks;
        switch (state.pose) {
            case OriginalSteve.STATE_COWER -> {
                // Sitting on the ground, folded forward, both hands over his head.
                float lean = 0.42F;
                this.body.xRot = lean;
                this.head.xRot = 0.75F + Mth.sin(age * 0.9F) * 0.04F;
                this.head.yRot = Mth.sin(age * 1.7F) * 0.12F;
                this.head.z = -1.0F;
                this.rightArm.xRot = -2.55F + Mth.sin(age * 2.3F) * 0.06F;
                this.leftArm.xRot = -2.55F + Mth.cos(age * 2.1F) * 0.06F;
                this.rightArm.zRot = -0.5F;
                this.leftArm.zRot = 0.5F;
                this.rightArm.yRot = 0.0F;
                this.leftArm.yRot = 0.0F;
                float legY = 12.0F * Mth.cos(lean);
                float legZ = 12.0F * Mth.sin(lean);
                this.rightLeg.y = legY;
                this.leftLeg.y = legY;
                this.rightLeg.z = legZ;
                this.leftLeg.z = legZ;
                this.rightLeg.xRot = -1.35F;
                this.leftLeg.xRot = -1.35F;
                this.rightLeg.yRot = 0.25F;
                this.leftLeg.yRot = -0.25F;
            }
            case OriginalSteve.STATE_STARE -> {
                // Rigid. Arms a little away from his sides, as if he has forgotten he has them.
                this.rightArm.xRot = 0.0F;
                this.leftArm.xRot = 0.0F;
                this.rightArm.zRot = 0.12F;
                this.leftArm.zRot = -0.12F;
                this.rightLeg.xRot = 0.0F;
                this.leftLeg.xRot = 0.0F;
            }
            case OriginalSteve.STATE_PANIC -> {
                // Running with his arms up around his head.
                this.rightArm.xRot = -2.2F + Mth.sin(age * 0.9F) * 0.5F;
                this.leftArm.xRot = -2.2F + Mth.cos(age * 0.9F) * 0.5F;
                this.rightArm.zRot = -0.3F;
                this.leftArm.zRot = 0.3F;
                this.head.xRot = 0.35F;
            }
            case OriginalSteve.STATE_RAMPAGE -> {
                // Head on one side. Something else is holding him up.
                this.head.zRot = 0.38F + Mth.sin(age * 5.0F) * 0.03F;
                this.head.yRot += Mth.sin(age * 3.1F) * 0.08F;
                this.leftArm.xRot = -0.35F;
                this.leftArm.zRot = -0.15F;
                this.body.zRot = 0.06F;
            }
            default -> { }
        }
    }
}
