package dev.leftbehind.registry;

import dev.leftbehind.LeftBehind;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

public final class ModDamage {
    /** "<player> was removed". Ignores armour, effects and creative mode. */
    public static final ResourceKey<DamageType> REMOVED = ResourceKey.create(Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "removed"));

    private ModDamage() {}

    public static DamageSource removed(ServerLevel level, @Nullable Entity by) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(REMOVED), by);
    }
}
