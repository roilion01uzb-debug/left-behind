package dev.leftbehind.story;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModEntities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Caves get their own slow build-up: small wrong details first, then sounds, then something that is really there.
 *
 * <ol>
 *   <li>Torches already placed ahead of you, from a world before this one.</li>
 *   <li>Mining inside the wall next to you, that stops when you stop.</li>
 *   <li>A torch behind you is gone when you turn round.</li>
 *   <li>Footsteps that match yours, one step late.</li>
 *   <li>A sign deep down: "turn back / he counts / them".</li>
 *   <li>Herobrine at the far end of your torchlight. Then the torch goes out.</li>
 * </ol>
 *
 * Only underground, only after a while down there, and never two at once. Act I only ever has the torches.
 */
public final class Caves {
    private static final int TORCHES = 0;
    private static final int MINING = 1;
    private static final int TORCH_GONE = 2;
    private static final int FOOTSTEPS = 3;
    private static final int SIGN = 4;
    private static final int AT_THE_END = 5;

    private static final class Visit {
        long since;
        long next;
        int events;
        boolean torches;
        // the running sound tricks
        int miningLeft;
        int miningNext;
        @Nullable BlockPos wall;
        int footstepsLeft;
        @Nullable Vec3 lastStep;
        final List<long[]> pendingSteps = new ArrayList<>();
        @Nullable Herobrine waiting;
        int waitingTicks;
        @Nullable Vec3 prevPos;
    }

    private static final Map<UUID, Visit> VISITS = new HashMap<>();

    private Caves() {}

    public static void resetTransient() {
        VISITS.clear();
    }

    public static boolean underground(ServerLevel level, ServerPlayer player) {
        return player.getY() < 50 && !level.canSeeSky(player.blockPosition().above());
    }

    /** Once a second, from the story director. */
    public static void second(ServerLevel level, SteveMemory memory, long time) {
        RandomSource random = level.getRandom();
        int act = memory.act();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            UUID id = player.getUUID();
            if (!underground(level, player)) {
                Visit visit = VISITS.get(id);
                // A few seconds in the open ends a visit. Dipping out of a cave mouth doesn't.
                if (visit != null && level.canSeeSky(player.blockPosition().above()) && player.getY() > 55) VISITS.remove(id);
                continue;
            }
            Visit visit = VISITS.computeIfAbsent(id, k -> {
                Visit v = new Visit();
                v.since = time;
                v.next = time + 900 + random.nextInt(900);
                return v;
            });
            if (time < visit.next || busy(visit) || StoryDirector.isHunted(player)) continue;
            int gap = act <= 1 ? 6000 : act == 2 ? 2400 : 1600;
            visit.next = time + gap + random.nextInt(gap);
            int event = choose(level, memory, player, visit, act, random);
            if (event < 0) continue;
            if (run(level, memory, player, visit, event)) {
                visit.events++;
                LeftBehind.LOGGER.info("Cave: {} for {}", event, player.getGameProfile().name());
            }
        }
    }

    /** For testing: one cave event now, by name. Returns false if there was nowhere to stage it. */
    public static boolean force(ServerLevel level, ServerPlayer player, String name) {
        int event = switch (name) {
            case "torches" -> TORCHES;
            case "mining" -> MINING;
            case "torch" -> TORCH_GONE;
            case "footsteps" -> FOOTSTEPS;
            case "sign" -> SIGN;
            case "end" -> AT_THE_END;
            default -> -1;
        };
        if (event < 0) return false;
        Visit visit = VISITS.computeIfAbsent(player.getUUID(), k -> new Visit());
        visit.next = level.getGameTime() + 1200;
        return run(level, SteveMemory.get(level.getServer()), player, visit, event);
    }

    private static int choose(ServerLevel level, SteveMemory memory, ServerPlayer player, Visit visit, int act, RandomSource random) {
        if (act <= 1) return visit.torches || memory.counter("cave_torch_day") == day(level) ? -1 : TORCHES;
        List<Integer> pool = new ArrayList<>();
        // The first thing on a visit is always a small one.
        if (!visit.torches) pool.add(TORCHES);
        pool.add(MINING);
        if (visit.events >= 1) {
            pool.add(FOOTSTEPS);
            pool.add(TORCH_GONE);
            if (player.getY() < 20 && !memory.flag("cave_sign")) pool.add(SIGN);
        }
        if (visit.events >= 2 && StoryDirector.activeHerobrine() == null
                && (act >= 3 || memory.counter("herobrine_day") != day(level))) {
            pool.add(AT_THE_END);
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private static boolean run(ServerLevel level, SteveMemory memory, ServerPlayer player, Visit visit, int event) {
        return switch (event) {
            case TORCHES -> torchesAhead(level, memory, player, visit);
            case MINING -> {
                BlockPos wall = wallBeside(level, player);
                if (wall == null) yield false;
                visit.wall = wall;
                visit.miningLeft = 10 + level.getRandom().nextInt(8);
                visit.miningNext = 0;
                yield true;
            }
            case TORCH_GONE -> torchBehind(level, player);
            case FOOTSTEPS -> {
                visit.footstepsLeft = 8 + level.getRandom().nextInt(6);
                visit.lastStep = player.position();
                yield true;
            }
            case SIGN -> sign(level, memory, player);
            case AT_THE_END -> atTheEnd(level, memory, player, visit);
            default -> false;
        };
    }

    private static boolean busy(Visit visit) {
        return visit.miningLeft > 0 || visit.footstepsLeft > 0 || !visit.pendingSteps.isEmpty() || visit.waiting != null;
    }

    // ---- every tick: the tricks that take a while --------------------------------------------

    public static void tick(MinecraftServer server) {
        if (VISITS.isEmpty()) return;
        ServerLevel level = server.overworld();
        long time = level.getGameTime();
        Iterator<Map.Entry<UUID, Visit>> it = VISITS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Visit> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.level() != level) {
                it.remove();
                continue;
            }
            Visit visit = entry.getValue();
            Vec3 now = player.position();
            boolean moving = visit.prevPos != null && now.distanceToSqr(visit.prevPos) > 0.0016;
            visit.prevPos = now;
            if (visit.miningLeft > 0 && visit.wall != null && --visit.miningNext <= 0) {
                if (!moving) {
                    visit.miningLeft = 0; // it stops when you stop
                } else {
                    BlockState stone = level.getBlockState(visit.wall);
                    SoundEvent hit = stone.getSoundType().getHitSound();
                    level.playSound(null, visit.wall, hit, SoundSource.BLOCKS, 0.7f, 0.5f + level.getRandom().nextFloat() * 0.2f);
                    visit.miningLeft--;
                    visit.miningNext = 5 + level.getRandom().nextInt(3);
                    if (visit.miningLeft == 0) {
                        level.playSound(null, visit.wall, stone.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.6f, 0.6f);
                    }
                }
            }
            if (visit.footstepsLeft > 0 && visit.lastStep != null && player.onGround()
                    && player.position().distanceToSqr(visit.lastStep) > 1.6 * 1.6) {
                visit.lastStep = player.position();
                visit.pendingSteps.add(new long[]{time + 7});
                visit.footstepsLeft--;
            }
            if (!visit.pendingSteps.isEmpty() && time >= visit.pendingSteps.get(0)[0]) {
                visit.pendingSteps.remove(0);
                Vec3 behind = Fx.behind(player, 2.2);
                BlockState floor = level.getBlockState(player.blockPosition().below());
                level.playSound(null, behind.x, player.getY(), behind.z, floor.getSoundType().getStepSound(), SoundSource.HOSTILE,
                        0.35f, floor.getSoundType().getPitch() * 0.9f);
            }
            if (visit.waiting != null) waitingTick(level, player, visit);
        }
    }

    // ---- the events --------------------------------------------------------------------

    /** Torches already burning ahead of you, a little too evenly spaced. */
    private static boolean torchesAhead(ServerLevel level, SteveMemory memory, ServerPlayer player, Visit visit) {
        Vec3 dir = flatLook(player);
        int placed = 0;
        for (int d = 10; d <= 34 && placed < 4; d += 7) {
            BlockPos around = BlockPos.containing(player.position().add(dir.scale(d)));
            BlockPos spot = darkWallSpot(level, around, 3);
            if (spot == null) continue;
            BlockState torch = wallTorch(level, spot);
            if (torch == null) continue;
            level.setBlock(spot, torch, 3);
            placed++;
        }
        if (placed == 0) return false;
        visit.torches = true;
        memory.setCounter("cave_torch_day", day(level));
        memory.setDirty();
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve != null && !steve.inEpisode() && steve.distanceToSqr(player) < 16 * 16) {
            Reactions.remark(steve, "cave_torches", 1200, "Those are mine. From before. I don't remember putting them there.",
                    "Somebody lit the way. ...it was me. I think it was me. A long time ago.");
        }
        return true;
    }

    /** A torch behind you, out of sight, goes out. */
    private static boolean torchBehind(ServerLevel level, ServerPlayer player) {
        if (!StoryDirector.griefingAllowed(level)) return false;
        Vec3 view = player.getViewVector(1f);
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-14, -4, -14), center.offset(14, 4, 14))) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.TORCH) && !state.is(Blocks.WALL_TORCH)) continue;
            Vec3 to = Vec3.atCenterOf(pos).subtract(player.getEyePosition());
            double dist = to.length();
            if (dist < 6 || dist > 16 || view.dot(to.normalize()) > -0.3) continue;
            level.removeBlock(pos, false);
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.25f, 1.4f);
            return true;
        }
        return false;
    }

    /** Deep down, in a hand you'll learn to recognise. */
    private static boolean sign(ServerLevel level, SteveMemory memory, ServerPlayer player) {
        Vec3 dir = flatLook(player);
        for (int d = 8; d <= 16; d += 2) {
            BlockPos around = BlockPos.containing(player.position().add(dir.scale(d)));
            for (BlockPos pos : BlockPos.betweenClosed(around.offset(-2, -2, -2), around.offset(2, 2, 2))) {
                if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.below()).isSolid()) continue;
                if (level.getMaxLocalRawBrightness(pos) > 6) continue;
                BlockPos at = pos.immutable();
                level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState(), 3);
                if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
                    sign.updateText(Caves::turnBack, SignTextSlot.FRONT);
                    sign.updateText(Caves::turnBack, SignTextSlot.BACK);
                    sign.setWaxed(true);
                }
                memory.setFlag("cave_sign");
                return true;
            }
        }
        return false;
    }

    private static SignText turnBack(SignText text) {
        return text.asMutable().setLine(0, Component.literal("turn back")).setLine(1, Component.literal("he counts"))
                .setLine(2, Component.literal("them")).asImmutable();
    }

    /** At the far end of your light, someone is standing. You look, and the torch beside you goes out. */
    private static boolean atTheEnd(ServerLevel level, SteveMemory memory, ServerPlayer player, Visit visit) {
        Vec3 dir = flatLook(player);
        Herobrine h = ModEntities.HEROBRINE.create(level, EntitySpawnReason.EVENT);
        if (h == null) return false;
        for (int d = 22; d >= 12; d -= 2) {
            BlockPos around = BlockPos.containing(player.position().add(dir.scale(d)));
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos feet = around.above(dy);
                if (level.getBlockState(feet.below()).isSolid() && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                        && standsIn(h, feet) && player.hasLineOfSight(h)) {
                    h.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
                    h.guide(240);
                    level.addFreshEntity(h);
                    StoryDirector.adopt(h);
                    visit.waiting = h;
                    visit.waitingTicks = 0;
                    return true;
                }
            }
        }
        h.discard();
        return false;
    }

    private static void waitingTick(ServerLevel level, ServerPlayer player, Visit visit) {
        Herobrine h = visit.waiting;
        if (h == null || h.isRemoved()) {
            visit.waiting = null;
            return;
        }
        visit.waitingTicks++;
        h.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
        // He lets you see him for a moment first.
        boolean looking = visit.waitingTicks > 25 && h.isLookedAtBy(player);
        if (looking || visit.waitingTicks > 200 || player.distanceToSqr(h) < 8 * 8) {
            // The light nearest you goes out, and when your eyes adjust he isn't there.
            BlockPos center = player.blockPosition();
            BlockPos nearest = null;
            double best = 9 * 9;
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-8, -3, -8), center.offset(8, 3, 8))) {
                BlockState state = level.getBlockState(pos);
                if (!state.is(Blocks.TORCH) && !state.is(Blocks.WALL_TORCH)) continue;
                double d = pos.distSqr(center);
                if (d < best) {
                    best = d;
                    nearest = pos.immutable();
                }
            }
            if (nearest != null && StoryDirector.griefingAllowed(level)) {
                level.removeBlock(nearest, false);
                level.playSound(null, nearest, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.4f, 1.2f);
            }
            if (looking) {
                Fx.blackout(player, 6);
                SteveMemory memory = SteveMemory.get(level.getServer());
                if (memory.act() <= 2) Herobrine.whisper(player, "not yet");
            }
            h.discard();
            visit.waiting = null;
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private static boolean standsIn(Herobrine h, BlockPos feet) {
        h.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0f, 0f);
        return true;
    }

    private static long day(ServerLevel level) {
        return level.getOverworldClockTime() / 24000L + 1;
    }

    private static Vec3 flatLook(ServerPlayer player) {
        Vec3 look = player.getViewVector(1f);
        Vec3 flat = new Vec3(look.x, 0, look.z);
        return flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize();
    }

    /** Air next to a stone wall, in the dark, near a point. */
    private static @Nullable BlockPos darkWallSpot(ServerLevel level, BlockPos around, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(around.offset(-radius, -2, -radius), around.offset(radius, 2, radius))) {
            if (!level.getBlockState(pos).isAir() || level.getMaxLocalRawBrightness(pos) > 3 || level.canSeeSky(pos)) continue;
            if (wallTorch(level, pos) != null) return pos.immutable();
        }
        return null;
    }

    private static @Nullable BlockState wallTorch(ServerLevel level, BlockPos pos) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState torch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, facing);
            if (torch.canSurvive(level, pos)) return torch;
        }
        return null;
    }

    /** A solid block in the wall a few blocks to your side, with more rock behind it. */
    private static @Nullable BlockPos wallBeside(ServerLevel level, ServerPlayer player) {
        Vec3 side = flatLook(player).cross(new Vec3(0, 1, 0));
        for (int sign : new int[]{1, -1}) {
            for (int d = 2; d <= 5; d++) {
                BlockPos pos = BlockPos.containing(player.getEyePosition().add(side.scale(sign * d)));
                BlockState state = level.getBlockState(pos);
                if (state.isSolid() && level.getBlockState(pos.offset(BlockPos.containing(side.scale(sign)))).isSolid()) return pos;
            }
        }
        return null;
    }
}
