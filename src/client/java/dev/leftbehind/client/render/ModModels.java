package dev.leftbehind.client.render;

import dev.leftbehind.LeftBehind;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;

/** The mod's own model shapes. */
public final class ModModels {
    /** Nothing but two small rectangles where Herobrine's eyes are, so only the eyes glow. */
    public static final ModelLayerLocation HEROBRINE_EYES = layer("herobrine_eyes");
    public static final ModelLayerLocation GHOST = layer("ghost");

    private ModModels() {}

    private static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, name), "main");
    }

    public static void register() {
        ModelLayerRegistry.registerModelLayer(HEROBRINE_EYES, ModModels::herobrineEyes);
        ModelLayerRegistry.registerModelLayer(GHOST, ModModels::ghost);
    }

    private static LayerDefinition herobrineEyes() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        PartDefinition root = mesh.getRoot();
        // Every part is emptied out, so the model moves exactly like the body but draws only the eyes.
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-3.0F, -4.0F, -4.04F, 2.0F, 1.0F, 0.02F)
                .addBox(1.0F, -4.0F, -4.04F, 2.0F, 1.0F, 0.02F), PartPose.ZERO);
        head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        return LayerDefinition.create(mesh, 8, 8);
    }

    /**
     * The player shape plus the one thing each of them had that a player doesn't:
     * Rana's frog eyes, Black Steve's hair, Beast Boy's tuft. Their skins are 64 by 80 to make room.
     * The model shows only the extra that belongs to the one being drawn.
     */
    private static LayerDefinition ghost() {
        MeshDefinition mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
        PartDefinition head = mesh.getRoot().getChild("head");
        head.addOrReplaceChild("frog_eye_right", CubeListBuilder.create().texOffs(0, 64)
                .addBox(-4.0F, -11.0F, -3.0F, 3.0F, 3.0F, 3.0F), PartPose.ZERO);
        head.addOrReplaceChild("frog_eye_left", CubeListBuilder.create().texOffs(0, 64)
                .addBox(1.0F, -11.0F, -3.0F, 3.0F, 3.0F, 3.0F), PartPose.ZERO);
        head.addOrReplaceChild("hair_puff", CubeListBuilder.create().texOffs(0, 64)
                .addBox(-5.0F, -10.5F, -5.0F, 10.0F, 4.0F, 10.0F), PartPose.ZERO);
        head.addOrReplaceChild("hair_tuft", CubeListBuilder.create().texOffs(0, 64)
                .addBox(-4.0F, -10.0F, -4.0F, 8.0F, 2.0F, 8.0F), PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 80);
    }
}
