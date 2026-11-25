package dev.leftbehind.entity;

import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.story.Reactions;
import dev.leftbehind.story.SteveVoice;
import dev.leftbehind.story.StoryDirector;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * When his feet can't take him somewhere, he does what a player does: jumps and puts a block under himself to get up,
 * digs a staircase down (never straight down), bridges a gap with blocks while crouching at the edge, and tunnels
 * through stone two blocks high. One small action at a time, so you can watch him do it.
 *
 * <p>He only digs through natural blocks away from anyone's home, never opens a block with water or lava behind it,
 * and builds with the dirt and stone he carries.
 */
public final class SteveMoves {
    private final OriginalSteve steve;
    private @Nullable BlockPos mining;
    private int progress;
    private int needed;
    private @Nullable BlockPos pillarFrom;
    private int pillarTicks;
    private @Nullable BlockPos stepTo;
    private int stepTicks;
    private boolean wantsUp;
    private int lastUsed = -100;
    /** Blocks he put under himself, so he can take them back on the way down. */
    private final Deque<BlockPos> pillar = new ArrayDeque<>();

    SteveMoves(OriginalSteve steve) {
        this.steve = steve;
    }

    /** Is he in the middle of getting somewhere the hard way? */
    public boolean active() {
        return steve.tickCount - lastUsed < 10;
    }

    boolean wantsUp() {
        return wantsUp && active();
    }

    public void reset() {
        if (mining != null && steve.level() instanceof ServerLevel level) level.destroyBlockProgress(steve.getId() + 7, mining, -1);
        mining = null;
        pillarFrom = null;
        stepTo = null;
        if (steve.getPose() == Pose.CROUCHING) steve.setPose(Pose.STANDING);
    }

    /**
     * One tick toward a place his feet can't take him. Returns false if there is nothing he can do about it
     * (he says why).
     */
    public boolean toward(ServerLevel level, SteveMemory memory, BlockPos goal) {
        lastUsed = steve.tickCount;
        steve.getNavigation().stop();
        if (mining != null) return mineTick(level, memory);
        if (pillarFrom != null) return pillarTick(level, memory);
        if (stepTo != null) {
            stepTick();
            return true;
        }
        if (steve.getPose() == Pose.CROUCHING) steve.setPose(Pose.STANDING);
        return decide(level, memory, goal);
    }

    private boolean decide(ServerLevel level, SteveMemory memory, BlockPos goal) {
        if (!steve.onGround() && !steve.isInWater() && !steve.onClimbable()) return true; // mid-jump, mid-fall
        BlockPos feet = steve.blockPosition();
        double dx = goal.getX() + 0.5 - steve.getX();
        double dz = goal.getZ() + 0.5 - steve.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        int dy = goal.getY() - feet.getY();
        wantsUp = dy >= 2;
        if (steve.onClimbable() && dy != 0) return true; // the ladder does the rest
        Direction dir = h < 0.9 ? steve.getDirection() : Direction.getApproximateNearest(dx, 0, dz);
        if (!dir.getAxis().isHorizontal()) dir = steve.getDirection();
        BlockPos front = feet.relative(dir);
        if (dy >= 2 && h < 1.6) return startPillar(level, memory, feet);
        if (dy <= -2) return stairsDown(level, memory, feet, front);
        if (dy >= 2) return stairsUp(level, memory, feet, front);
        return straight(level, memory, feet, front, dy);
    }

    // ---- up ------------------------------------------------------------------------------

    private boolean startPillar(ServerLevel level, SteveMemory memory, BlockPos feet) {
        if (!SteveNavigation.passable(level, feet.above(2))) return dig(level, memory, feet.above(2));
        if (steve.work().buildingBlock() == null) return noBlocks();
        steve.getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.6);
        steve.getJumpControl().jump();
        pillarFrom = feet.immutable();
        pillarTicks = 0;
        return true;
    }

    private boolean pillarTick(ServerLevel level, SteveMemory memory) {
        BlockPos at = pillarFrom;
        if (at == null) return true;
        steve.getLookControl().setLookAt(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 30f, 90f);
        if (++pillarTicks > 24) {
            pillarFrom = null;
            return true;
        }
        if (steve.getY() < at.getY() + 1.0 || !SteveNavigation.passable(level, at)) return true;
        if (!place(level, at)) return false;
        pillar.push(at);
        while (pillar.size() > 24) pillar.removeLast();
        pillarFrom = null;
        memory.observe("did_pillar");
        if (memory.setFlag("steve_pillared")) {
            SteveVoice.steve(steve, memory.habit("saw_pillar") > 0 ? "Jump, and put a block under you. I saw you do it."
                    : "Jump, and put a block under you. Jump, block. Jump, block.");
        }
        return true;
    }

    private boolean stairsUp(ServerLevel level, SteveMemory memory, BlockPos feet, BlockPos front) {
        if (!SteveNavigation.passable(level, feet.above(2))) return dig(level, memory, feet.above(2));
        if (SteveNavigation.passable(level, front)) {
            // Nothing to step on: he builds the step.
            return place(level, front);
        }
        if (!SteveNavigation.passable(level, front.above())) return dig(level, memory, front.above());
        if (!SteveNavigation.passable(level, front.above(2))) return dig(level, memory, front.above(2));
        stepTo = front.above().immutable();
        stepTicks = 0;
        return true;
    }

    // ---- down ----------------------------------------------------------------------------

    private boolean stairsDown(ServerLevel level, SteveMemory memory, BlockPos feet, BlockPos front) {
        // Standing on his own pillar: take it back, block by block.
        if (!pillar.isEmpty() && pillar.peek().equals(feet.below())) {
            BlockPos top = pillar.pop();
            return startMine(level, top);
        }
        // Never straight down. A staircase, one step at a time.
        for (BlockPos p : List.of(front.above(), front, front.below())) {
            if (!SteveNavigation.passable(level, p)) return dig(level, memory, p);
        }
        BlockPos floor = front.below(2);
        if (SteveNavigation.passable(level, floor) && SteveNavigation.passable(level, floor.below())) {
            // Open air under the next step: a long drop, or water, or worse. He builds the step first.
            if (level.getFluidState(floor).is(FluidTags.LAVA) || level.getFluidState(floor.below()).is(FluidTags.LAVA)) {
                return refuse("lava", "Lava down there. Not that way.");
            }
            if (!place(level, floor)) return false;
            return true;
        }
        if (memory.setFlag("steve_stairs_down")) SteveVoice.steve(steve, "Never dig straight down. Somebody taught me that. I don't remember who.");
        stepTo = front.below().immutable();
        stepTicks = 0;
        return true;
    }

    // ---- across --------------------------------------------------------------------------

    private boolean straight(ServerLevel level, SteveMemory memory, BlockPos feet, BlockPos front, int dy) {
        if (!SteveNavigation.passable(level, front.above())) return dig(level, memory, front.above());
        if (!SteveNavigation.passable(level, front)) {
            // A step he can just jump onto.
            if (dy >= 1 && SteveNavigation.passable(level, front.above(2)) && SteveNavigation.passable(level, feet.above(2))) {
                stepTo = front.above().immutable();
                stepTicks = 0;
                return true;
            }
            return dig(level, memory, front);
        }
        if (SteveNavigation.passable(level, front.below()) && !level.getFluidState(front.below()).is(FluidTags.WATER)) {
            if (SteveNavigation.passable(level, front.below(2)) || level.getFluidState(front.below(2)).is(FluidTags.LAVA)) {
                // A gap. He crouches at the edge and bridges it, the way you do.
                steve.setPose(Pose.CROUCHING);
                steve.getLookControl().setLookAt(front.getX() + 0.5, front.getY() - 1, front.getZ() + 0.5, 30f, 90f);
                if (!place(level, front.below())) return false;
                memory.observe("did_bridge");
                if (memory.setFlag("steve_bridged")) {
                    SteveVoice.steve(steve, memory.habit("saw_bridge") > 0 ? "Crouch at the edge, put the block down. Like you."
                            : "Crouch at the edge so you don't fall. Then the block. Then breathe.");
                }
                return true;
            }
        }
        stepTo = front.immutable();
        stepTicks = 0;
        return true;
    }

    private void stepTick() {
        BlockPos to = stepTo;
        if (to == null) return;
        steve.getMoveControl().setWantedPosition(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.9);
        double dx = to.getX() + 0.5 - steve.getX();
        double dz = to.getZ() + 0.5 - steve.getZ();
        if ((dx * dx + dz * dz < 0.3 * 0.3 && Math.abs(steve.getY() - to.getY()) < 0.6) || ++stepTicks > 40) stepTo = null;
    }

    // ---- breaking and placing --------------------------------------------------------------

    private boolean dig(ServerLevel level, SteveMemory memory, BlockPos p) {
        BlockState state = level.getBlockState(p);
        if (state.getDestroySpeed(level, p) < 0) return refuse("bedrock", "This doesn't break. Nothing breaks this.");
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (n.equals(steve.blockPosition()) || n.equals(steve.blockPosition().above())) continue;
            if (level.getFluidState(n).is(FluidTags.LAVA)) return refuse("lava", "Lava behind this one. Not that way.");
            if (level.getFluidState(n).is(FluidTags.WATER) && !level.getFluidState(p).is(FluidTags.WATER)) {
                return refuse("water", "Water behind that. If I open it, it all comes in.");
            }
        }
        if (!StoryDirector.griefingAllowed(level) || !SteveWork.diggable(state) || nearHome(level, p)) {
            return refuse("wall", "That's somebody's wall. I won't break it.", "Not through there. Somebody built that.");
        }
        if (state.getDestroySpeed(level, p) > 20f) return refuse("hard", "Too hard. I'd be here all night.");
        return startMine(level, p);
    }

    private boolean startMine(ServerLevel level, BlockPos p) {
        mining = p.immutable();
        progress = 0;
        BlockState state = level.getBlockState(p);
        ItemStack tool = steve.work().bestTool(state);
        needed = SteveWork.breakTicks(level, p, state, tool);
        steve.setItemSlot(EquipmentSlot.MAINHAND, tool.isEmpty() ? ItemStack.EMPTY : tool.copyWithCount(1));
        return true;
    }

    private boolean mineTick(ServerLevel level, SteveMemory memory) {
        BlockPos p = mining;
        if (p == null) return true;
        BlockState state = level.getBlockState(p);
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, p).isEmpty()) {
            mining = null;
            return true;
        }
        steve.getLookControl().setLookAt(Vec3.atCenterOf(p));
        progress++;
        if (progress % 5 == 0) {
            steve.swingArm();
            level.playSound(null, p, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.5f, 0.8f);
        }
        level.destroyBlockProgress(steve.getId() + 7, p, Math.min(9, progress * 10 / Math.max(1, needed)));
        if (progress < needed) return true;
        level.destroyBlockProgress(steve.getId() + 7, p, -1);
        ItemStack tool = steve.work().bestTool(state);
        boolean harvest = !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
        List<ItemStack> drops = harvest ? Block.getDrops(state, level, p, level.getBlockEntity(p), steve, tool) : List.of();
        level.destroyBlock(p, false, steve);
        for (ItemStack drop : drops) steve.work().give(drop);
        memory.observe("did_dig");
        mining = null;
        return true;
    }

    private boolean place(ServerLevel level, BlockPos p) {
        Item item = steve.work().buildingBlock();
        if (item == null) return noBlocks();
        BlockState state = ((BlockItem) item).getBlock().defaultBlockState();
        steve.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
        steve.getLookControl().setLookAt(Vec3.atCenterOf(p));
        level.setBlock(p, state, 3);
        level.playSound(null, p, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 0.9f);
        steve.swingArm();
        steve.work().take(s -> s.is(item), 1);
        return true;
    }

    private boolean noBlocks() {
        Reactions.remark(steve, "moves_noblocks", 40, "I need blocks to get there. Dirt, stone, anything. Throw me some?",
                "I've got nothing to build with. Give me some dirt?");
        return false;
    }

    private boolean refuse(String why, String... lines) {
        Reactions.remark(steve, "moves_" + why, 40, lines);
        reset();
        return false;
    }

    private static boolean nearHome(ServerLevel level, BlockPos p) {
        for (ServerPlayer player : level.players()) {
            BlockPos home = StoryDirector.homeOf(player, level);
            if (home != null && home.distSqr(p) < 10 * 10) return true;
        }
        return false;
    }
}
