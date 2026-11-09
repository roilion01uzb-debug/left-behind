package dev.leftbehind.registry;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    public static final EntityType<OriginalSteve> ORIGINAL_STEVE = register("original_steve",
            EntityType.Builder.<OriginalSteve>of(OriginalSteve::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F).eyeHeight(1.62F).clientTrackingRange(16));

    public static final EntityType<Herobrine> HEROBRINE = register("herobrine",
            EntityType.Builder.<Herobrine>of(Herobrine::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F).eyeHeight(1.62F).clientTrackingRange(16).fireImmune());

    private ModEntities() {}

    private static <T extends net.minecraft.world.entity.Entity> EntityType<T> register(String name, EntityType.Builder<T> builder) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, name));
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
    }

    public static void init() {
        FabricDefaultAttributeRegistry.register(ORIGINAL_STEVE, OriginalSteve.createAttributes());
        FabricDefaultAttributeRegistry.register(HEROBRINE, Herobrine.createAttributes());
    }
}
