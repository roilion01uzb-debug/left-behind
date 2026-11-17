package dev.leftbehind.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/** The player's shape (head, body, arms, legs and their outer layers) for characters that are not players. */
public class PersonModel extends HumanoidModel<PersonRenderState> {
    public PersonModel(ModelPart root) {
        super(root, RenderTypes::entityTranslucent);
    }
}
