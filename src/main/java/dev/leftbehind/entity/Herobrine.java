package dev.leftbehind.entity;

import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModDamage;
import dev.leftbehind.registry.ModSounds;
import dev.leftbehind.story.Dialogue;
import dev.leftbehind.story.Journal;
import dev.leftbehind.story.Progress;
import dev.leftbehind.story.Relics;
import dev.leftbehind.story.Sense;
import dev.leftbehind.story.SteveVoice;
import dev.leftbehind.story.StoryDirector;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Herobrine. He was never added, so he could never be removed.
 * He watches Steve. If you make him notice you instead, he comes for you, and he does not stop.
 */
public class Herobrine extends PathfinderMob {
    public enum Mode { WATCH_STEVE, WATCH_PLAYER, HUNT }

    private static final EntityDataAccessor<Boolean> DATA_HUNTING = SynchedEntityData.defineId(Herobrine.class, EntityDataSerializers.BOOLEAN);

    private Mode mode = Mode.WATCH_STEVE;
    private @Nullable UUID targetId;
    private int lifetime = 900;
    private int contactTicks;
    private boolean breachDone;
    private int huntTicks;
    private int blinkCooldown;
    private int stuckTicks;
    private boolean whispered;
    private @Nullable Vec3 lastPos;

    public Herobrine(EntityType<? extends Herobrine> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setSilent(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1024.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.STEP_HEIGHT, 1.1)
                .add(Attributes.FOLLOW_RANGE, 96.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_HUNTING, false);
    }

    public boolean isHunting() {
        return this.entityData.get(DATA_HUNTING);
    }

    public Mode mode() {
        return mode;
    }

    public @Nullable UUID targetId() {
        return targetId;
    }

    public void watchPlayer(ServerPlayer player, int ticks) {
        mode = Mode.WATCH_PLAYER;
        targetId = player.getUUID();
        lifetime = ticks;
    }

    public void watchSteve(int ticks) {
        mode = Mode.WATCH_STEVE;
        lifetime = ticks;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, damage);
        // You can't hurt him. You can only make him look at you.
        if (source.getEntity() instanceof ServerPlayer player && mode != Mode.HUNT) {
            Fx.burst(this, net.minecraft.core.particles.ParticleTypes.SMOKE, 12);
            if (StoryDirector.huntAllowed(level)) startHunt(level, player);
            else vanish(level, true);
        }
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
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!(level() instanceof ServerLevel) || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
        if (hand != InteractionHand.MAIN_HAND || mode == Mode.HUNT) return InteractionResult.PASS;
        // Close enough to touch. He lets you ask one thing.
        Dialogue.openHerobrine(this, serverPlayer);
        lifetime = Math.max(lifetime, tickCount + 400);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || !isAlive()) return;
        switch (mode) {
            case HUNT -> huntTick(level);
            case WATCH_PLAYER -> watchPlayerTick(level);
            case WATCH_STEVE -> watchSteveTick(level);
        }
    }

    // ---- watching ------------------------------------------------------------------

    private void watchSteveTick(ServerLevel level) {
        if (tickCount > lifetime) {
            vanish(level, false);
            return;
        }
        ServerPlayer watcher = tickCount % 5 == 0 ? senseTick(level) : null;
        if (isRemoved() || mode != Mode.WATCH_STEVE) return;
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (watcher != null) {
            // Someone is staring at him. He looks back, and for a moment he forgets about Steve.
            getLookControl().setLookAt(watcher, 30f, 30f);
            lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, watcher.getEyePosition());
            if (contactTicks > 0) contactTicks--;
            return;
        }
        if (steve == null || steve.inEpisode()) return;
        lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, steve.getEyePosition());
        // Eye contact: Steve sees him, and he is looking back.
        if (!breachDone && steve.distanceToSqr(this) < 64 * 64 && steve.hasLineOfSight(this)) {
            steve.getLookControl().setLookAt(this, 30f, 30f);
            steve.getNavigation().stop();
            steve.setState(OriginalSteve.STATE_STARE);
            contactTicks++;
            if (contactTicks == 1) {
                Fx.sound(steve, ModSounds.PRESENCE, SoundSource.HOSTILE, 1.2f, 1.0f);
                SteveVoice.steve(steve, steve.getRandom(), "...", "Wait.", "Who is that?");
            }
            if (contactTicks >= 80) {
                breachDone = true;
                StoryDirector.memoryBreach(level, steve, this);
                vanish(level, false);
            }
        } else if (contactTicks > 0) {
            contactTicks--;
        }
    }

    private void watchPlayerTick(ServerLevel level) {
        ServerPlayer target = targetId == null ? null : level.getServer().getPlayerList().getPlayer(targetId);
        if (tickCount > lifetime || target == null || target.level() != level) {
            vanish(level, true);
            return;
        }
        lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        if (tickCount % 5 == 0) senseTick(level);
    }

    /**
     * Fills the sense meter of every player who is drawing his attention.
     * Returns the player he is most aware of, if someone is looking straight at him.
     */
    private @Nullable ServerPlayer senseTick(ServerLevel level) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        ServerPlayer watcher = null;
        float highest = 0f;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            double dist = player.distanceTo(this);
            if (dist > 72) continue;
            boolean looking = isLookedAtBy(player, dist);
            float gain = 0f;
            if (looking) gain += dist < 16 ? 2.4f : dist < 32 ? 1.4f : 0.8f;
            if (dist < 10) gain += 2.2f;
            else if (dist < 26 && player.isSprinting()) gain += 0.7f;
            if (player.isShiftKeyDown()) gain *= 0.5f;
            if (gain <= 0f) continue;
            float before = Sense.get(player);
            Sense.add(player, gain);
            float now = Sense.get(player);
            if (looking && now > highest) {
                highest = now;
                watcher = player;
            }
            if (before < 8f && now >= 8f) {
                if (memory.setFlag("watched")) Journal.unlock(level.getServer(), "note_watching");
                Progress.grant(player, "watched");
                Fx.soundTo(player, ModSounds.PRESENCE, SoundSource.HOSTILE, 0.8f, 1.0f);
            }
            if (before < 30f && now >= 30f && !whispered) {
                whispered = true;
                Fx.soundTo(player, Fx.behind(player, 2.0), ModSounds.WHISPER, SoundSource.HOSTILE, 0.9f, 1.0f);
                whisper(player, pick("you are not the first one he followed", "he is mine", "look away", "it remembers because i let it", "stop"));
            }
            if (before < 50f && now >= 50f && (memory.act() <= 2 || !StoryDirector.huntAllowed(level))) {
                // Too early in the story. He decides you are not worth it yet.
                whisper(player, "not yet");
                Fx.staticFlash(player, 8);
                Sense.set(player, 20f);
                vanish(level, true);
                return null;
            }
            if (now >= 100f) {
                startHunt(level, player);
                return null;
            }
        }
        return watcher;
    }

    private String pick(String... lines) {
        return lines[random.nextInt(lines.length)];
    }

    public static void whisper(ServerPlayer player, String line) {
        player.sendSystemMessage(Component.literal("<???> ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(line).withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC)));
    }

    private boolean isLookedAtBy(ServerPlayer player, double dist) {
        Vec3 view = player.getViewVector(1.0f).normalize();
        Vec3 toMe = new Vec3(getX() - player.getX(), getEyeY() - 0.4 - player.getEyeY(), getZ() - player.getZ());
        double dot = view.dot(toMe.normalize());
        double limit = 1.0 - Math.min(0.06, 0.35 / Math.max(dist, 1.0));
        return dot > limit && player.hasLineOfSight(this);
    }

    // ---- the hunt ------------------------------------------------------------------

    public void startHunt(ServerLevel level, ServerPlayer player) {
        if (mode == Mode.HUNT) return;
        mode = Mode.HUNT;
        targetId = player.getUUID();
        huntTicks = 0;
        blinkCooldown = 60;
        this.entityData.set(DATA_HUNTING, true);
        Sense.set(player, 100f);
        Fx.dread(player, 1.0f);
        Fx.soundTo(player, ModSounds.STING, SoundSource.HOSTILE, 1.0f, 1.0f);
        Fx.eyes(player, 16);
        Fx.darkness(player, 120);
        Fx.title(player, Component.empty(), Component.literal("He sees you.").withStyle(ChatFormatting.WHITE), 0, 50, 20);
        SteveMemory memory = SteveMemory.get(level.getServer());
        memory.addCounter("hunts", 1);
        if (memory.setFlag("hunted")) Journal.unlock(level.getServer(), "note_hunt");
        Progress.grant(player, "hunted");
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve != null && !steve.inEpisode() && steve.distanceToSqr(player) < 40 * 40) {
            SteveVoice.steve(steve, steve.getRandom(), "RUN!", "Don't let him touch you!", "Why did you LOOK at him?!");
        }
    }

    private void huntTick(ServerLevel level) {
        ServerPlayer target = targetId == null ? null : level.getServer().getPlayerList().getPlayer(targetId);
        if (target == null || target.level() != level || !target.isAlive() || target.isSpectator()) {
            if (target != null) {
                Sense.set(target, 0f);
                Fx.stopSound(target, ModSounds.CHASE);
            }
            vanish(level, false);
            return;
        }
        huntTicks++;
        Sense.set(target, 100f);
        lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        if (huntTicks < 50) return; // He lets you understand what is about to happen.
        if (huntTicks % 250 == 50) Fx.soundTo(target, ModSounds.CHASE, SoundSource.HOSTILE, 0.9f, 1.0f);
        if (huntTicks % 40 == 0) Fx.shake(target, 20);
        if (huntTicks % 100 == 75) Fx.soundTo(target, Fx.behind(target, 2.5), ModSounds.BREATH, SoundSource.HOSTILE, 0.9f, 1.0f);

        // The one thing that can stop it: Steve, if he cares enough about you to take your place.
        if (huntTicks % 10 == 0) {
            OriginalSteve steve = StoryDirector.findSteve(level);
            SteveMemory memory = SteveMemory.get(level.getServer());
            if (steve != null && !steve.inEpisode() && memory.trust(target.getUUID()) >= 60f
                    && (steve.distanceToSqr(target) < 14 * 14 || steve.distanceToSqr(this) < 14 * 14)) {
                SteveVoice.steve(steve, "Not them. Look at ME.");
                steve.getLookControl().setLookAt(this, 30f, 30f);
                Fx.stopSound(target, ModSounds.CHASE);
                Sense.set(target, 0f);
                Fx.dread(target, 0f);
                Progress.grant(target, "saved");
                memory.relationship(target.getUUID()).addTrust(5f);
                StoryDirector.memoryBreach(level, steve, this);
                vanish(level, false);
                return;
            }
        }

        double dist = target.distanceTo(this);
        boolean late = huntTicks > 900;
        if (dist < 2.3 && (late || hasLineOfSight(target))) {
            remove(level, target);
            return;
        }
        if (huntTicks % 6 == 0) getNavigation().moveTo(target, 1.2);
        if (huntTicks % 20 == 0) {
            if (lastPos != null && lastPos.distanceToSqr(position()) < 1.5) stuckTicks += 20;
            else stuckTicks = 0;
            lastPos = position();
        }
        if (blinkCooldown > 0) blinkCooldown--;
        // Walls, water and distance only slow him down for a moment.
        if (blinkCooldown == 0 && (dist > 22 || stuckTicks >= 40 || late)) {
            if (blinkNear(level, target, late ? 1 : 4, late ? 2 : 8)) {
                blinkCooldown = late ? 30 : 70;
                stuckTicks = 0;
            }
        }
    }

    private boolean blinkNear(ServerLevel level, ServerPlayer target, int min, int max) {
        Vec3 look = target.getViewVector(1f);
        for (int attempt = 0; attempt < 24; attempt++) {
            // Prefer somewhere the player isn't looking.
            double base = Math.atan2(-look.z, -look.x);
            double angle = base + (random.nextDouble() - 0.5) * (attempt < 12 ? Math.PI : Math.PI * 2);
            double d = min + random.nextDouble() * (max - min);
            BlockPos pos = BlockPos.containing(target.getX() + Math.cos(angle) * d, target.getY(), target.getZ() + Math.sin(angle) * d);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos feet = pos.above(dy);
                BlockState below = level.getBlockState(feet.below());
                if (below.isSolid() && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                    Fx.vanishPuff(this);
                    Fx.sound(this, ModSounds.VANISH, SoundSource.HOSTILE, 0.8f, 1.0f);
                    snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, getYRot(), 0f);
                    getNavigation().stop();
                    Fx.sound(this, ModSounds.VANISH, SoundSource.HOSTILE, 0.8f, 1.2f);
                    Fx.staticFlash(target, 5);
                    return true;
                }
            }
        }
        return false;
    }

    /** He reaches you. */
    private void remove(ServerLevel level, ServerPlayer target) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        Fx.stopSound(target, ModSounds.CHASE);
        Sense.set(target, 0f);
        Fx.dread(target, 0f);
        if (target.isCreative() && memory.flag("set_no_creative_kill")) {
            Fx.blackout(target, 40);
            Fx.staticFlash(target, 20);
            whisper(target, "not yet");
            vanish(level, false);
            return;
        }
        Fx.soundTo(target, ModSounds.KILL, SoundSource.HOSTILE, 1.0f, 1.0f);
        Fx.eyes(target, 30);
        Fx.blackout(target, 80);
        LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
        if (bolt != null) {
            bolt.snapTo(target.getX(), target.getY(), target.getZ(), 0f, 0f);
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
        target.hurtServer(level, ModDamage.removed(level, this), Float.MAX_VALUE);
        memory.addCounter("removed", 1);
        Progress.grant(target, "removed");
        vanish(level, false);
    }

    public void vanish(@Nullable ServerLevel level, boolean leaveMark) {
        if (level != null && !isRemoved()) {
            Fx.vanishPuff(this);
            Fx.sound(this, ModSounds.VANISH, SoundSource.HOSTILE, 0.7f, 1.0f);
            if (leaveMark && random.nextInt(3) == 0) Relics.leaveMark(level, blockPosition());
        }
        discard();
    }
}
