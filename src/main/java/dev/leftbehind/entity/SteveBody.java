package dev.leftbehind.entity;

import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.story.Reactions;
import dev.leftbehind.story.SteveVoice;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Steve's body, and his sense of it. He knows when the water is pulling him, when he is running out of air, when he
 * is burning, when lava is a step away, when a creeper is hissing behind him. He gets himself out like a player would:
 * swims for the bank, comes up for air, runs for water. He climbs ladders and vines, opens gates and hatches on his
 * way, swims after you when you dive, eats when he is hurt and heals slowly when he rests.
 *
 * <p>Swimming and climbing are things he gets better at: watching you do them, and doing them himself.
 */
public final class SteveBody {
    /** Something that needs him to stop everything and look after himself. */
    public enum Danger { CURRENT, DROWNING, FIRE, LAVA, CREEPER, STUCK }

    private final OriginalSteve steve;
    private @Nullable Danger danger;
    private @Nullable Vec3 escape;
    private int dangerTicks;
    private boolean diving;
    private @Nullable BlockPos openedGate;
    private double fallFrom = Double.NaN;
    private int sinceHurt = 1000;
    private float lastHealth = -1f;
    private int eatCooldown;

    SteveBody(OriginalSteve steve) {
        this.steve = steve;
    }

    public @Nullable Danger danger() {
        return danger;
    }

    public boolean diving() {
        return diving;
    }

    /** How good he is at something, from 0.5 (never done it, never seen it) to 1. */
    public static float skill(SteveMemory memory, String name) {
        float seen = memory.habit("saw_" + name) > 0 ? 0.25f : 0f;
        float practice = Math.min(0.25f, memory.habit("did_" + name) / 400f);
        return 0.5f + seen + practice;
    }

    /** Every tick, on the server, while he is himself. */
    void tick(ServerLevel level, SteveMemory memory) {
        if (steve.tickCount % 4 == 0) sense(level, memory);
        if (danger != null) dangerTicks++;
        climb(level, memory);
        swim(level, memory);
        gates(level);
        falls(memory);
        heal(level, memory);
    }

    // ---- danger ---------------------------------------------------------------------------

    private void sense(ServerLevel level, SteveMemory memory) {
        Danger before = danger;
        Danger now = null;
        Vec3 out = null;
        if (steve.isOnFire() && !steve.isInWater()) {
            now = Danger.FIRE;
            BlockPos water = find(level, 10, p -> level.getFluidState(p).is(FluidTags.WATER));
            out = water != null ? Vec3.atBottomCenterOf(water) : away(steve.position().add(steve.getLookAngle()), 6);
        } else if (steve.isUnderWater() && steve.getAirSupply() < steve.getMaxAirSupply() / 3) {
            now = Danger.DROWNING;
            BlockPos land = safeLand(level, 8);
            out = land != null ? Vec3.atBottomCenterOf(land) : steve.position().add(0, 4, 0);
        } else if (steve.isInWater() && inCurrent(level)) {
            now = Danger.CURRENT;
            BlockPos land = safeLand(level, 8);
            if (land != null) out = Vec3.atBottomCenterOf(land);
        } else {
            BlockPos lava = lavaNear(level);
            Creeper creeper = lava == null ? hissing(level) : null;
            if (lava != null) {
                now = Danger.LAVA;
                out = away(Vec3.atCenterOf(lava), 4);
            } else if (creeper != null) {
                now = Danger.CREEPER;
                out = away(creeper.position(), 9);
            } else if (steve.isInWall()) {
                now = Danger.STUCK;
                BlockPos open = find(level, 3, p -> SteveNavigation.passable(level, p) && SteveNavigation.passable(level, p.above()));
                if (open != null) out = Vec3.atBottomCenterOf(open);
            }
        }
        danger = out == null ? null : now;
        escape = out;
        if (danger != null && danger != before) {
            dangerTicks = 0;
            steve.getNavigation().stop();
            say(danger, memory);
        } else if (danger == null && before != null && dangerTicks > 30) {
            Reactions.remark(steve, "danger_over", 40, "That was close.", "Okay. Okay. I'm fine.", "I'm alright. Don't look at me like that.");
        }
    }

    private void say(Danger d, SteveMemory memory) {
        switch (d) {
            case CURRENT -> Reactions.remark(steve, "danger_current", 20, "Whoa! The water's pulling me!",
                    "Current! Hold on, I'm getting out.", "Not the water. Not down there.");
            case DROWNING -> Reactions.remark(steve, "danger_air", 20, "Air. I need air.", "Up. Up!");
            case FIRE -> Reactions.remark(steve, "danger_fire", 20, "Hot! Hot! Water, where's water?",
                    "I'm on fire! That's not a figure of speech!");
            case LAVA -> Reactions.remark(steve, "danger_lava", 30, "Lava. Careful. Don't go near it.",
                    "Back. That's lava. It doesn't give things back.");
            case CREEPER -> Reactions.remark(steve, "danger_creeper", 20, "Creeper! Get back!", "Don't let it near the house!");
            case STUCK -> Reactions.remark(steve, "danger_wall", 60, "I'm stuck in the wall. How did I get in the wall?");
        }
    }

    /** Called by his body goal while he is in danger: get to the place he picked. */
    void steer(ServerLevel level) {
        Vec3 to = escape;
        if (to == null) return;
        boolean run = danger == Danger.CREEPER || danger == Danger.FIRE || danger == Danger.LAVA;
        steve.getMoveControl().setWantedPosition(to.x, to.y, to.z, run ? 1.35 : 1.2);
        steve.getLookControl().setLookAt(to.x, to.y + 1.0, to.z);
        if (steve.isInWater() || steve.isInLava()) {
            if (danger == Danger.DROWNING || steve.horizontalCollision || to.y > steve.getY() - 0.3) steve.getJumpControl().jump();
            // Swim hard for it. The current pushes, he pushes back.
            Vec3 flat = new Vec3(to.x - steve.getX(), 0, to.z - steve.getZ());
            if (flat.lengthSqr() > 0.01) steve.setDeltaMovement(steve.getDeltaMovement().add(flat.normalize().scale(0.03)));
        } else if (steve.horizontalCollision && steve.onGround()) {
            steve.getJumpControl().jump();
        }
        steve.setSprinting(run || steve.isInWater());
    }

    private boolean inCurrent(ServerLevel level) {
        BlockPos at = steve.blockPosition();
        FluidState fluid = level.getFluidState(at);
        if (fluid.is(FluidTags.WATER) && !fluid.isSource() && fluid.getFlow(level, at).lengthSqr() > 0.0001) return true;
        // Still water, but the edge he's floating towards drops into a waterfall.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (SteveNavigation.currentOverDrop(level, at.relative(d))) return true;
        }
        return false;
    }

    /** The nearest dry place to stand, preferring higher ground and places away from running water. */
    private @Nullable BlockPos safeLand(ServerLevel level, int r) {
        BlockPos feet = steve.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-r, -2, -r), feet.offset(r, 3, r))) {
            if (!standable(level, p)) continue;
            double score = Math.sqrt(p.distSqr(feet));
            if (p.getY() < feet.getY()) score += (feet.getY() - p.getY()) * 3.0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                FluidState near = level.getFluidState(p.relative(d));
                if (near.is(FluidTags.WATER) && !near.isSource()) score += 3.0;
            }
            if (score < bestScore) {
                bestScore = score;
                best = p.immutable();
            }
        }
        return best;
    }

    static boolean standable(ServerLevel level, BlockPos p) {
        BlockState below = level.getBlockState(p.below());
        return SteveNavigation.passable(level, p) && level.getFluidState(p).isEmpty()
                && SteveNavigation.passable(level, p.above()) && level.getFluidState(p.above()).isEmpty()
                && below.isFaceSturdy(level, p.below(), Direction.UP) && below.getFluidState().isEmpty()
                && !below.is(BlockTags.FIRE) && !below.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                && !below.is(net.minecraft.world.level.block.Blocks.CACTUS);
    }

    private @Nullable BlockPos lavaNear(ServerLevel level) {
        BlockPos feet = steve.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 1, 2))) {
            if (!level.getFluidState(p).is(FluidTags.LAVA)) continue;
            Vec3 c = Vec3.atCenterOf(p);
            double dx = c.x - steve.getX();
            double dz = c.z - steve.getZ();
            if (dx * dx + dz * dz < 1.7 * 1.7) return p.immutable();
        }
        return null;
    }

    private @Nullable Creeper hissing(ServerLevel level) {
        List<Creeper> creepers = level.getEntitiesOfClass(Creeper.class, steve.getBoundingBox().inflate(6.0),
                c -> c.isAlive() && (c.getSwellDir() > 0 || c.distanceToSqr(steve) < 3 * 3));
        return creepers.isEmpty() ? null : creepers.getFirst();
    }

    private Vec3 away(Vec3 from, double distance) {
        Vec3 dir = steve.position().subtract(from).multiply(1, 0, 1);
        if (dir.lengthSqr() < 0.001) dir = steve.getLookAngle().multiply(-1, 0, -1);
        return steve.position().add(dir.normalize().scale(distance));
    }

    private @Nullable BlockPos find(ServerLevel level, int r, java.util.function.Predicate<BlockPos> test) {
        BlockPos feet = steve.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-r, -2, -r), feet.offset(r, 2, r))) {
            double d = p.distSqr(feet);
            if (d < bestDist && test.test(p)) {
                bestDist = d;
                best = p.immutable();
            }
        }
        return best;
    }

    // ---- ladders, vines, hatches ------------------------------------------------------------

    private void climb(ServerLevel level, SteveMemory memory) {
        if (!steve.onClimbable()) return;
        boolean up = false;
        Path path = steve.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            int i = path.getNextNodeIndex();
            up = path.getNodePos(i).getY() > steve.getY() + 0.05
                    || (i + 1 < path.getNodeCount() && path.getNodePos(i + 1).getY() > steve.getY() + 0.05);
        }
        up |= steve.moves().wantsUp();
        if (!up) return;
        // A hatch over his head: open it, like you would.
        BlockPos head = BlockPos.containing(steve.getX(), steve.getEyeY() + 0.6, steve.getZ());
        BlockState hatch = level.getBlockState(head);
        if (hatch.getBlock() instanceof TrapDoorBlock && hatch.is(BlockTags.WOODEN_TRAPDOORS) && !hatch.getValue(TrapDoorBlock.OPEN)) {
            level.setBlock(head, hatch.setValue(TrapDoorBlock.OPEN, true), 3);
            level.playSound(null, head, SoundEvents.WOODEN_TRAPDOOR_OPEN, SoundSource.BLOCKS, 1f, 1f);
            steve.swingArm();
        }
        steve.getJumpControl().jump();
        if (steve.tickCount % 20 == 0) memory.observe("did_climb");
        if (memory.setFlag("steve_climbed")) {
            SteveVoice.steve(steve, memory.habit("saw_climb") > 0 ? "I watched you do this. Hands, feet. Hands, feet."
                    : "Ladders. Hands, feet. Hands, feet. Don't look down.");
        }
    }

    /** Fence gates on his way: he opens them, walks through, and closes them behind him. */
    private void gates(ServerLevel level) {
        if (openedGate != null && steve.blockPosition().distSqr(openedGate) > 2 * 2) {
            BlockState state = level.getBlockState(openedGate);
            if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN)) {
                level.setBlock(openedGate, state.setValue(FenceGateBlock.OPEN, false), 3);
                level.playSound(null, openedGate, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1f, 1f);
            }
            openedGate = null;
        }
        if (steve.tickCount % 5 != 0) return;
        Path path = steve.getNavigation().getPath();
        if (path == null || path.isDone()) return;
        for (int i = path.getNextNodeIndex(); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 2); i++) {
            BlockPos at = path.getNodePos(i);
            BlockState state = level.getBlockState(at);
            if (!(state.getBlock() instanceof FenceGateBlock) || state.getValue(FenceGateBlock.OPEN)) continue;
            if (Vec3.atCenterOf(at).distanceToSqr(steve.position()) > 2.5 * 2.5) continue;
            Direction facing = Direction.getApproximateNearest(at.getX() + 0.5 - steve.getX(), 0, at.getZ() + 0.5 - steve.getZ());
            if (facing.getAxis().isHorizontal()) state = state.setValue(FenceGateBlock.FACING, facing);
            level.setBlock(at, state.setValue(FenceGateBlock.OPEN, true), 3);
            level.playSound(null, at, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1f, 1f);
            steve.swingArm();
            openedGate = at.immutable();
            return;
        }
    }

    // ---- water ------------------------------------------------------------------------------

    private void swim(ServerLevel level, SteveMemory memory) {
        if (!steve.isInWater()) {
            diving = false;
            if (steve.isSwimming()) steve.setSwimming(false);
            if (steve.getPose() == Pose.SWIMMING) steve.setPose(Pose.STANDING);
            return;
        }
        if (memory.setFlag("steve_swam")) {
            SteveVoice.steve(steve, memory.habit("saw_swim") > 0 ? "Like you. Kick, and keep your head up."
                    : "I don't know how to swim. I'm doing it anyway.");
        }
        if (steve.tickCount % 20 == 0) memory.observe("did_swim");

        // After you, under the water, if he has the air for it.
        ServerPlayer under = diveTarget(level);
        boolean wasDiving = diving;
        diving = under != null && danger == null && steve.getAirSupply() > steve.getMaxAirSupply() / 2;
        if (diving && !wasDiving) Reactions.remark(steve, "dive", 60, "Going under. Wait for me.", "Down there? Okay. Okay. Deep breath.");
        if (diving) {
            Vec3 to = under.getEyePosition().subtract(steve.getEyePosition());
            if (to.lengthSqr() > 4.0) steve.setDeltaMovement(steve.getDeltaMovement().add(to.normalize().scale(0.045)));
            steve.getLookControl().setLookAt(under, 30f, 30f);
        }

        boolean moving = diving || danger != null || !steve.getNavigation().isDone();
        boolean under2 = steve.isUnderWater();
        steve.setSprinting(moving);
        steve.setSwimming(moving && under2);
        Pose pose = moving && under2 ? Pose.SWIMMING : Pose.STANDING;
        if (steve.getPose() != pose) steve.setPose(pose);
        if (moving) {
            // He swims like you do, more or less. Better once he has watched you and had some practice.
            float boost = 0.99f + 0.05f * skill(memory, "swim");
            Vec3 m = steve.getDeltaMovement();
            steve.setDeltaMovement(m.x * boost, m.y, m.z * boost);
        }
    }

    private @Nullable ServerPlayer diveTarget(ServerLevel level) {
        java.util.UUID id = steve.followingId();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.distanceToSqr(steve) > 16 * 16) continue;
            if (id != null && !id.equals(p.getUUID())) continue;
            if (p.isUnderWater() && p.getY() < steve.getY() - 1.5) return p;
        }
        return null;
    }

    // ---- falling, healing, eating ------------------------------------------------------------

    private void falls(SteveMemory memory) {
        if (!steve.onGround() && !steve.isInWater() && !steve.onClimbable()) {
            if (Double.isNaN(fallFrom)) fallFrom = steve.getY();
            return;
        }
        if (!Double.isNaN(fallFrom)) {
            double fell = fallFrom - steve.getY();
            fallFrom = Double.NaN;
            if (fell > 4.5 && !steve.isInWater()) {
                Reactions.remark(steve, "fell", 30, "Ow. That was higher than it looked.", "I'm fine. My legs aren't.",
                        "I meant to do that. I didn't mean to do that.");
            }
        }
    }

    private void heal(ServerLevel level, SteveMemory memory) {
        float health = steve.getHealth();
        if (lastHealth >= 0 && health < lastHealth) sinceHurt = 0;
        else sinceHurt++;
        lastHealth = health;
        if (eatCooldown > 0) eatCooldown--;
        // Hurt: he eats something, if he has anything.
        if (health < steve.getMaxHealth() * 0.5f && eatCooldown == 0 && danger == null) {
            ItemStack food = steve.work().takeFood();
            if (!food.isEmpty()) {
                eatCooldown = 200;
                level.playSound(null, steve.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 1f, 1f);
                level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food.getItem()), steve.getX(), steve.getEyeY() - 0.2, steve.getZ(), 8, 0.15, 0.1, 0.15, 0.05);
                net.minecraft.world.food.FoodProperties props = food.get(net.minecraft.core.component.DataComponents.FOOD);
                steve.heal(props == null ? 4f : props.nutrition() * 2f);
                Reactions.remark(steve, "eat", 120, "Hold on. I need to eat something.", "Food. It always helps. Mostly.");
            }
        }
        // Rest heals, slowly, like it does for you.
        if (sinceHurt > 200 && health < steve.getMaxHealth() && steve.tickCount % 60 == 0) steve.heal(1f);
    }

    /** He was hurt by something other than a person: he says what. */
    void hurtBy(net.minecraft.world.damagesource.DamageSource source) {
        sinceHurt = 0;
        String type = source.getMsgId();
        if (source.getEntity() instanceof Enemy) {
            Reactions.remark(steve, "hurt_mob", 20, "It got me!", "Ow! Get off!", "I'm hit. I'm okay. I'm hit.");
        } else if (type.contains("cactus") || type.contains("sweetBerry")) {
            Reactions.remark(steve, "hurt_cactus", 60, "Cactus. Of course it's cactus.", "Prickly. Everything here is prickly.");
        } else if (type.contains("explosion")) {
            Reactions.remark(steve, "hurt_boom", 20, "My ears. I can't hear. I CAN'T HEAR.");
        }
    }

    /** For the status command. */
    public String describe() {
        return danger == null ? (diving ? "diving" : steve.isInWater() ? "swimming" : steve.onClimbable() ? "climbing" : "fine")
                : danger.name().toLowerCase(java.util.Locale.ROOT);
    }

}
