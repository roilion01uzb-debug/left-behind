package dev.leftbehind.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * How Steve finds his way, like a player does: up and down ladders and vines, through doors and gates, across water
 * by swimming, and around running water that would carry him off an edge.
 */
public final class SteveNavigation extends GroundPathNavigation {
    /** Path type used for running water. Steve gives it a high cost: he would rather walk around. */
    public static final PathType CURRENT = PathType.BREACH;

    public SteveNavigation(Mob mob, Level level) {
        super(mob, level);
        setCanOpenDoors(true);
        this.nodeEvaluator.setCanPassDoors(true);
        setCanFloat(true);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new Evaluator();
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    @Override
    protected boolean canUpdatePath() {
        // On a ladder he is neither on the ground nor swimming, but he is still on his way.
        return super.canUpdatePath() || mob.onClimbable();
    }

    static boolean climbable(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    static boolean passable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.CLIMBABLE)) return true;
        // A wooden hatch he can open on his way up.
        if (state.getBlock() instanceof TrapDoorBlock && state.is(BlockTags.WOODEN_TRAPDOORS)) return true;
        return state.getCollisionShape(level, pos).isEmpty() && !state.getFluidState().is(FluidTags.LAVA);
    }

    /** Running water over a drop: the kind that takes you down into a cave. */
    static boolean currentOverDrop(BlockGetter level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        if (!fluid.is(FluidTags.WATER) || fluid.isSource()) return false;
        int open = 0;
        for (int i = 1; i <= 4; i++) {
            BlockState below = level.getBlockState(pos.below(i));
            boolean empty = below.isAir() || (below.getFluidState().is(FluidTags.WATER) && !below.getFluidState().isSource());
            if (!empty) break;
            open++;
        }
        return open >= 2;
    }

    /** Ladders and vines become paths, and running water becomes something to think twice about. */
    static final class Evaluator extends WalkNodeEvaluator {
        @Override
        public int getNeighbors(Node[] neighbors, Node pos) {
            int n = super.getNeighbors(neighbors, pos);
            BlockGetter level = currentContext.level();
            BlockPos here = new BlockPos(pos.x, pos.y, pos.z);
            if (n < neighbors.length && climbable(level, here) && passable(level, here.above()) && passable(level, here.above(2))) {
                Node up = climbNode(pos.x, pos.y + 1, pos.z);
                if (up != null) neighbors[n++] = up;
            }
            if (n < neighbors.length && climbable(level, here.below())) {
                Node down = climbNode(pos.x, pos.y - 1, pos.z);
                if (down != null) neighbors[n++] = down;
            }
            return n;
        }

        private @Nullable Node climbNode(int x, int y, int z) {
            Node node = getNode(x, y, z);
            if (node.closed) return null;
            node.type = PathType.WALKABLE;
            node.costMalus = Math.max(node.costMalus, 0.5f);
            return node;
        }

        @Override
        public PathType getPathType(PathfindingContext context, int x, int y, int z) {
            PathType type = super.getPathType(context, x, y, z);
            if (type == PathType.BLOCKED || type == PathType.LAVA) return type;
            BlockPos pos = new BlockPos(x, y, z);
            if (type == PathType.FENCE) {
                // A closed gate is a door to him: he opens it on the way (SteveBody#gates).
                BlockState state = context.level().getBlockState(pos);
                if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) return PathType.DOOR_WOOD_CLOSED;
                return type;
            }
            FluidState fluid = context.level().getFluidState(pos);
            if (fluid.is(FluidTags.WATER) && !fluid.isSource()) {
                return currentOverDrop(context.level(), pos) ? PathType.BLOCKED : CURRENT;
            }
            return type;
        }
    }
}
