package dev.leftbehind.entity;

import dev.leftbehind.story.StoryDirector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Herobrine. He doesn't chase. He doesn't need to. He stands where Steve will see him.
 */
public class Herobrine extends PathfinderMob {
    private static final int LIFETIME = 900;
    private int contactTicks;
    private boolean breachDone;

    public Herobrine(EntityType<? extends Herobrine> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setSilent(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1024.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.FOLLOW_RANGE, 96.0);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, damage);
        if (source.getEntity() instanceof ServerPlayer) vanish(level);
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) return;
        if (tickCount > LIFETIME) {
            vanish(level);
            return;
        }
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve != null) {
            getLookControl().setLookAt(steve, 30f, 30f);
            // Eye contact: Steve sees him, and he is looking back.
            if (!breachDone && steve.distanceToSqr(this) < 64 * 64 && steve.hasLineOfSight(this)) {
                steve.getLookControl().setLookAt(this, 30f, 30f);
                steve.getNavigation().stop();
                contactTicks++;
                if (contactTicks == 1) {
                    level.playSound(null, steve.blockPosition(), SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 1.0f, 0.6f);
                }
                if (contactTicks >= 60) {
                    breachDone = true;
                    StoryDirector.memoryBreach(level, steve, this);
                    vanish(level);
                    return;
                }
            } else if (contactTicks > 0) {
                contactTicks--;
            }
        }
        // He doesn't like being watched by players. Look straight at him and he's gone.
        if (!breachDone && tickCount > 40) {
            for (ServerPlayer player : level.players()) {
                if (isLookedAtBy(player)) {
                    vanish(level);
                    return;
                }
            }
        }
    }

    private boolean isLookedAtBy(ServerPlayer player) {
        if (player.isSpectator() || player.distanceToSqr(this) > 48 * 48) return false;
        Vec3 view = player.getViewVector(1.0f).normalize();
        Vec3 toMe = new Vec3(getX() - player.getX(), getEyeY() - player.getEyeY(), getZ() - player.getZ());
        double dist = toMe.length();
        double dot = view.dot(toMe.normalize());
        return dot > 1.0 - 0.025 / Math.max(dist, 1.0) * 10 && player.hasLineOfSight(this);
    }

    public void vanish(@Nullable ServerLevel level) {
        if (level != null && !isRemoved()) {
            level.playSound(null, blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.4f, 0.5f);
        }
        discard();
    }
}
