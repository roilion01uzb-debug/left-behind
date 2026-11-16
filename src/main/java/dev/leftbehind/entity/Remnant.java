package dev.leftbehind.entity;

import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModSounds;
import dev.leftbehind.story.Journal;
import dev.leftbehind.story.Progress;
import dev.leftbehind.story.Reactions;
import dev.leftbehind.story.StoryDirector;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * One of the ones who were removed: Rana, Black Steve, Beast Boy.
 * They were test people. They never had walking animations, so they don't walk. They glide, in one pose, and then they are gone.
 */
public class Remnant extends PathfinderMob {
    public static final int RANA = 0;
    public static final int BLACK_STEVE = 1;
    public static final int BEAST_BOY = 2;
    public static final String[] NAMES = {"Rana", "Black Steve", "Beast Boy"};

    private static final EntityDataAccessor<Integer> DATA_VARIANT = SynchedEntityData.defineId(Remnant.class, EntityDataSerializers.INT);
    /** Counts down on the client too, so the fade-out can be drawn. */
    private static final EntityDataAccessor<Integer> DATA_FADE = SynchedEntityData.defineId(Remnant.class, EntityDataSerializers.INT);

    private int lifetime = 900;
    private double glideAngle;
    private @Nullable String page;
    private boolean seen;

    public Remnant(EntityType<? extends Remnant> type, Level level) {
        super(type, level);
        this.setSilent(true);
        this.glideAngle = this.random.nextDouble() * Math.PI * 2;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.2)
                .add(Attributes.STEP_HEIGHT, 1.1);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_VARIANT, RANA);
        builder.define(DATA_FADE, 0);
    }

    public int getVariant() {
        return this.entityData.get(DATA_VARIANT);
    }

    public void setVariant(int variant) {
        this.entityData.set(DATA_VARIANT, Math.floorMod(variant, NAMES.length));
    }

    /** 0 while present; counts up to 20 while fading out. */
    public int getFade() {
        return this.entityData.get(DATA_FADE);
    }

    public void setLifetime(int ticks) {
        this.lifetime = ticks;
    }

    /** The torn page this one leaves behind when it goes, if any. */
    public void setPage(@Nullable String page) {
        this.page = page;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, damage);
        // Things pass through them. They notice, though.
        if (source.getEntity() instanceof ServerPlayer && getFade() == 0) beginFade();
        return false;
    }

    private void beginFade() {
        if (getFade() == 0) {
            this.entityData.set(DATA_FADE, 1);
            Fx.sound(this, ModSounds.GHOST_APPEAR, SoundSource.AMBIENT, 0.8f, 0.7f);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) return;
        int fade = getFade();
        if (fade > 0) {
            if (tickCount % 2 == 0) Fx.ghostMotes(this, 4);
            if (fade >= 20) {
                leave(level);
                return;
            }
            this.entityData.set(DATA_FADE, fade + 1);
            return;
        }
        if (tickCount == 1) {
            Fx.sound(this, ModSounds.GHOST_APPEAR, SoundSource.AMBIENT, 1.0f, 1.0f);
            Fx.ghostMotes(this, 20);
        }
        if (tickCount > lifetime) {
            beginFade();
            return;
        }
        if (tickCount % 80 == 40) Fx.sound(this, ModSounds.GHOST_HUM, SoundSource.AMBIENT, 0.7f, 0.9f + getVariant() * 0.08f);
        if (tickCount % 12 == 0) Fx.ghostMotes(this, 2);

        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            double d = player.distanceToSqr(this);
            if (d < best) {
                best = d;
                nearest = player;
            }
        }
        if (nearest == null) return;
        double dist = Math.sqrt(best);

        // The glide: one pose, no steps. Each of them moves in their own way.
        Vec3 away = new Vec3(getX() - nearest.getX(), 0, getZ() - nearest.getZ());
        Vec3 dir;
        switch (getVariant()) {
            case RANA -> {
                // Circles, like she is looking for something in the grass.
                glideAngle += 0.012;
                dir = new Vec3(Math.cos(glideAngle), 0, Math.sin(glideAngle));
            }
            case BEAST_BOY -> {
                // Slides sideways across your view, never coming closer.
                Vec3 side = away.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : new Vec3(-away.z, 0, away.x).normalize();
                dir = side;
            }
            default -> {
                // Stands. Turns away. Drifts off, very slowly.
                dir = tickCount > 200 && away.lengthSqr() > 1.0e-4 ? away.normalize().scale(0.5) : Vec3.ZERO;
            }
        }
        Vec3 motion = getDeltaMovement();
        setDeltaMovement(dir.x * 0.055, motion.y, dir.z * 0.055);
        if (getVariant() == BLACK_STEVE && tickCount > 200) {
            setYRot((float) (Math.toDegrees(Math.atan2(away.z, away.x)) - 90.0));
        } else {
            lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, nearest.getEyePosition());
        }
        setYBodyRot(getYRot());
        setYHeadRot(getYRot());

        if (dist < 40 && !seen && tickCount % 10 == 0 && nearest.hasLineOfSight(this) && looksToward(nearest)) {
            seen = true;
            StoryDirector.onGhostSeen(level, this, nearest);
        }
        // Come too close and there is nothing there.
        if (dist < 7.5) beginFade();
    }

    private boolean looksToward(ServerPlayer player) {
        Vec3 view = player.getViewVector(1.0f);
        Vec3 toMe = position().add(0, 1, 0).subtract(player.getEyePosition()).normalize();
        return view.dot(toMe) > 0.9;
    }

    private void leave(ServerLevel level) {
        Fx.ghostMotes(this, 40);
        if (page != null) {
            spawnAtLocation(level, Journal.tornPage(page));
        } else if (getVariant() == RANA && random.nextInt(2) == 0) {
            spawnAtLocation(level, rose());
        }
        discard();
    }

    /** The red flower used to be a rose. She still leaves them. */
    public static ItemStack rose() {
        ItemStack stack = new ItemStack(Items.POPPY);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Rose").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.RED)));
        stack.set(DataComponents.LORE, new ItemLore(java.util.List.of(
                Component.literal("It isn't called this any more.").withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    public static boolean isRose(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return stack.is(Items.POPPY) && name != null && "Rose".equals(name.getString());
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("variant", getVariant());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setVariant(input.getIntOr("variant", 0));
        // A ghost that was saved with the world has been here too long already.
        lifetime = Math.min(lifetime, 200);
    }
}
