package dev.leftbehind.fx;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Sounds, particles and screen effects. Everything here is cosmetic. */
public final class Fx {
    private Fx() {}

    // ---- screen effects (need the mod on the client; silently skipped otherwise) ----

    public static void screen(ServerPlayer player, int kind, float value) {
        if (ServerPlayNetworking.canSend(player, FxPayload.TYPE)) {
            ServerPlayNetworking.send(player, new FxPayload(kind, value));
        }
    }

    public static void dread(ServerPlayer player, float level) {
        screen(player, FxPayload.DREAD, level);
    }

    public static void staticFlash(ServerPlayer player, int ticks) {
        screen(player, FxPayload.STATIC, ticks);
    }

    public static void eyes(ServerPlayer player, int ticks) {
        screen(player, FxPayload.EYES, ticks);
    }

    public static void blackout(ServerPlayer player, int ticks) {
        screen(player, FxPayload.BLACKOUT, ticks);
    }

    public static void shake(ServerPlayer player, int ticks) {
        screen(player, FxPayload.SHAKE, ticks);
    }

    public static void darkness(ServerPlayer player, int ticks) {
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, ticks, 0, true, false, false));
    }

    public static void title(ServerPlayer player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    // ---- sound ---------------------------------------------------------------------

    /** Heard by everyone nearby, coming from a place in the world. */
    public static void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound.value(), source, volume, pitch);
    }

    public static void sound(Entity from, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
        if (from.level() instanceof ServerLevel level) sound(level, from.position(), sound, source, volume, pitch);
    }

    /** Heard by one player only, coming from a place in the world. */
    public static void soundTo(ServerPlayer player, Vec3 at, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(sound, source, at.x, at.y, at.z, volume, pitch, player.getRandom().nextLong()));
    }

    /** Heard by one player only, inside their head. */
    public static void soundTo(ServerPlayer player, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
        soundTo(player, player.getEyePosition(), sound, source, volume, pitch);
    }

    public static void stopSound(ServerPlayer player, Holder<SoundEvent> sound) {
        player.connection.send(new ClientboundStopSoundPacket(sound.value().location(), null));
    }

    /** A point a few blocks behind the player, for things they should hear but not see. */
    public static Vec3 behind(ServerPlayer player, double distance) {
        Vec3 look = player.getViewVector(1.0f);
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0e-4) flat = new Vec3(0, 0, 1);
        return player.position().subtract(flat.normalize().scale(distance)).add(0, 1.0, 0);
    }

    // ---- particles -----------------------------------------------------------------

    public static void particles(ServerLevel level, ParticleOptions type, Vec3 at, int count, double spread, double speed) {
        level.sendParticles(type, at.x, at.y, at.z, count, spread, spread, spread, speed);
    }

    public static void burst(Entity entity, ParticleOptions type, int count) {
        if (entity.level() instanceof ServerLevel level) {
            level.sendParticles(type, entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(),
                    count, entity.getBbWidth() * 0.5, entity.getBbHeight() * 0.3, entity.getBbWidth() * 0.5, 0.01);
        }
    }

    /** Smoke and ash where something stood a moment ago. */
    public static void vanishPuff(Entity entity) {
        burst(entity, ParticleTypes.LARGE_SMOKE, 14);
        burst(entity, ParticleTypes.ASH, 30);
    }

    /** Pale motes drifting up, for the ones who were removed. */
    public static void ghostMotes(Entity entity, int count) {
        burst(entity, ParticleTypes.WHITE_ASH, count);
        burst(entity, ParticleTypes.END_ROD, Math.max(1, count / 8));
    }

    public static void tears(Entity entity) {
        if (entity.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.FALLING_WATER, entity.getX(), entity.getEyeY() - 0.1, entity.getZ(), 2, 0.15, 0.02, 0.15, 0);
        }
    }

    public static void memoryDust(Entity entity, int count) {
        burst(entity, new DustParticleOptions(0x9FD8E8, 0.9f), count);
        burst(entity, ParticleTypes.ENCHANT, count);
    }
}
