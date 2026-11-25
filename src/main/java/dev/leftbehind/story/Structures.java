package dev.leftbehind.story;

import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModSounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Places nobody built, which show up near you as the story goes on, each with something that happens there.
 *
 * <ul>
 *   <li><b>The Pillars</b> (from day 2): seven stone pillars in a ring. Stand in the middle and look away, and there are eight.</li>
 *   <li><b>The Hallway</b> (from day 3): stairs going down into a long straight hallway with words on the walls, ending in
 *   a small room someone lived in. When you reach the room, the lights behind you go out one by one.</li>
 *   <li><b>The Obsidian Box</b> (from day 4): a closed cube of obsidian with no way in. Something inside knocks.</li>
 * </ul>
 *
 * Positions are saved in the story memory, like the relics.
 */
public final class Structures {
    private Structures() {}

    public enum Kind { PILLARS, HALLWAY, BOX }

    private static final int HALL_DEPTH = 9;
    private static final int HALL_LENGTH = 34;
    private static final String[] HALL_WORDS = {"you are", "not the", "first one", "he", "followed"};

    /** Torches still to put out in the hallway, nearest the entrance first. Not saved: it happens while you're there. */
    private static final List<BlockPos> TORCHES_OUT = new ArrayList<>();
    private static int knockCooldown;

    public static void resetTransient() {
        TORCHES_OUT.clear();
        knockCooldown = 0;
    }

    /** Every five seconds, from the story director. */
    static void tick(ServerLevel level, SteveMemory memory, long time) {
        long day = level.getOverworldClockTime() / 24000L;
        if (StoryDirector.griefingAllowed(level) && time % 100 == 0) {
            if (day >= 1 && !memory.flag("st_pillars")) tryPlace(level, memory, Kind.PILLARS);
            else if (day >= 2 && !memory.flag("st_hallway")) tryPlace(level, memory, Kind.HALLWAY);
            else if ((day >= 3 || memory.act() >= 2) && !memory.flag("st_box")) tryPlace(level, memory, Kind.BOX);
        }
    }

    /** Every second, from the story director: what happens when you're there. */
    static void second(ServerLevel level, SteveMemory memory) {
        if (memory.flag("st_pillars")) pillarsSecond(level, memory);
        if (memory.flag("st_hallway")) hallwaySecond(level, memory);
        if (memory.flag("st_box")) boxSecond(level, memory);
    }

    /** Puts out one hallway torch every half second while it's happening. Called every tick. */
    public static void serverTick(ServerLevel level) {
        if (TORCHES_OUT.isEmpty() || level.getGameTime() % 10 != 0) return;
        BlockPos pos = TORCHES_OUT.removeFirst();
        if (level.getBlockState(pos).is(Blocks.REDSTONE_WALL_TORCH)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            for (ServerPlayer player : level.players()) {
                if (player.blockPosition().distSqr(pos) < 48 * 48) {
                    Fx.soundTo(player, Vec3.atCenterOf(pos), ModSounds.TORCH_OUT, SoundSource.BLOCKS, 0.8f, 0.9f + level.getRandom().nextFloat() * 0.2f);
                }
            }
        }
    }

    // ---- placing ---------------------------------------------------------------------

    private static void tryPlace(ServerLevel level, SteveMemory memory, Kind kind) {
        if (level.getRandom().nextInt(4) != 0) return;
        ServerPlayer player = StoryDirector.randomPlayer(level);
        if (player == null || player.getY() < level.getSeaLevel() - 8) return;
        BlockPos at = switch (kind) {
            case PILLARS -> Relics.flatGround(level, player.blockPosition(), 48, 80, 5);
            case HALLWAY -> Relics.flatGround(level, player.blockPosition(), 40, 70, 3);
            case BOX -> Relics.flatGround(level, player.blockPosition(), 44, 76, 3);
        };
        if (at == null) return;
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(level.getRandom());
        if (kind == Kind.HALLWAY && !hallwayFits(level, at, dir)) return;
        build(level, memory, kind, at, dir);
    }

    /** Builds one now (also used by the test command). */
    public static BlockPos build(ServerLevel level, SteveMemory memory, Kind kind, BlockPos at, Direction dir) {
        String key = key(kind);
        switch (kind) {
            case PILLARS -> buildPillars(level, memory, at);
            case HALLWAY -> buildHallway(level, at, dir);
            case BOX -> buildBox(level, at);
        }
        memory.setFlag(key);
        memory.clearFlag(key + "_found");
        memory.clearFlag(key + "_done");
        memory.setCounter(key + "_x", at.getX());
        memory.setCounter(key + "_y", at.getY());
        memory.setCounter(key + "_z", at.getZ());
        memory.setCounter(key + "_dir", dir.get2DDataValue());
        memory.setDirty();
        dev.leftbehind.LeftBehind.LOGGER.info("Placed {} at {}", kind, at.toShortString());
        return at;
    }

    private static String key(Kind kind) {
        return switch (kind) {
            case PILLARS -> "st_pillars";
            case HALLWAY -> "st_hallway";
            case BOX -> "st_box";
        };
    }

    private static BlockPos pos(SteveMemory memory, Kind kind) {
        String key = key(kind);
        return new BlockPos((int) memory.counter(key + "_x"), (int) memory.counter(key + "_y"), (int) memory.counter(key + "_z"));
    }

    private static Direction dir(SteveMemory memory, Kind kind) {
        return Direction.from2DDataValue((int) memory.counter(key(kind) + "_dir"));
    }

    /** Somewhere for the test command to put one: in front of the player, on the ground. */
    public static @Nullable BlockPos spotFor(ServerLevel level, ServerPlayer player, Kind kind) {
        Direction facing = player.getDirection();
        int ahead = kind == Kind.PILLARS ? 14 : 8;
        BlockPos base = player.blockPosition().relative(facing, ahead);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ());
        return new BlockPos(base.getX(), y, base.getZ());
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 3);
    }

    private static BlockState oldStone(RandomSource random) {
        int r = random.nextInt(10);
        return r < 5 ? Blocks.STONE_BRICKS.defaultBlockState() : r < 8 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                : Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    }

    private static void sign(ServerLevel level, BlockPos pos, Direction facing, String... lines) {
        set(level, pos, Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing));
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            sign.updateText(text -> {
                var m = text.asMutable();
                for (int i = 0; i < lines.length && i < 4; i++) m.setLine(i + (lines.length == 1 ? 1 : 0), Component.literal(lines[i]));
                return m.asImmutable();
            }, SignTextSlot.FRONT);
            sign.setWaxed(true);
        }
    }

    // ---- the pillars -------------------------------------------------------------------

    private static BlockPos pillarSpot(BlockPos center, int i) {
        double a = Math.PI * 2 * i / 8;
        return center.offset((int) Math.round(Math.cos(a) * 7), 0, (int) Math.round(Math.sin(a) * 7));
    }

    private static void buildPillars(ServerLevel level, SteveMemory memory, BlockPos center) {
        RandomSource random = level.getRandom();
        int gap = random.nextInt(8);
        memory.setCounter("st_pillars_gap", gap);
        for (int i = 0; i < 8; i++) if (i != gap) buildPillar(level, pillarSpot(center, i), random);
        // The middle: bare gravel, and one sign.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 5) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX() + dx, center.getZ() + dz);
                BlockPos top = new BlockPos(center.getX() + dx, y - 1, center.getZ() + dz);
                set(level, top, Blocks.GRAVEL.defaultBlockState());
                for (int up = 1; up < 3; up++) if (!level.getBlockState(top.above(up)).isAir()) set(level, top.above(up), Blocks.AIR.defaultBlockState());
            }
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        BlockPos signPos = new BlockPos(center.getX(), y, center.getZ());
        Relics.placeSign(level, signPos, "count them");
    }

    private static void buildPillar(ServerLevel level, BlockPos spot, RandomSource random) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ());
        BlockPos base = new BlockPos(spot.getX(), y, spot.getZ());
        int height = 5 + random.nextInt(4);
        for (int dy = -3; dy < height; dy++) {
            BlockState s = random.nextInt(3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            if (dy < 0 && !level.getBlockState(base.above(dy)).canBeReplaced()) continue;
            set(level, base.above(dy), s);
        }
        set(level, base.above(height), Blocks.REDSTONE_TORCH.defaultBlockState());
    }

    private static void pillarsSecond(ServerLevel level, SteveMemory memory) {
        if (memory.flag("st_pillars_done")) return;
        BlockPos center = pos(memory, Kind.PILLARS);
        for (ServerPlayer player : level.players()) {
            double d = horizontalDistSqr(player.blockPosition(), center);
            if (d > 12 * 12) continue;
            if (memory.setFlag("st_pillars_found")) {
                Journal.unlock(level.getServer(), "note_pillars");
                StoryLog.beat(level.getServer(), player, "pillars");
                Progress.grant(player, "relic");
                OriginalSteve steve = nearSteve(level, player);
                if (steve != null) Reactions.remark(steve, "pillars", 60, "Don't count them. He likes it when you count them.",
                        "Seven. There's always seven. Until there isn't.");
            }
            if (d > 6 * 6) continue;
            // Standing in the ring with his back to the gap: the gap fills in.
            int gap = (int) memory.counter("st_pillars_gap");
            BlockPos gapPos = pillarSpot(center, gap);
            Vec3 toGap = Vec3.atCenterOf(gapPos).subtract(player.position());
            Vec3 view = player.getViewVector(1f);
            boolean lookingAtGap = new Vec3(view.x, 0, view.z).normalize().dot(new Vec3(toGap.x, 0, toGap.z).normalize()) > 0.3;
            if (lookingAtGap) {
                memory.setCounter("st_pillars_away", 0);
                continue;
            }
            if (memory.addCounter("st_pillars_away", 1) < 4) continue;
            buildPillar(level, gapPos, level.getRandom());
            memory.setFlag("st_pillars_done");
            Fx.soundTo(player, Vec3.atCenterOf(gapPos), ModSounds.STONE_GRIND, SoundSource.BLOCKS, 1.0f, 0.8f);
            Herobrine.whisper(player, "eight. count again");
            Fx.shake(player, 10);
            return;
        }
    }

    // ---- the hallway -------------------------------------------------------------------

    private static boolean hallwayFits(ServerLevel level, BlockPos at, Direction dir) {
        BlockPos end = at.relative(dir, HALL_DEPTH + HALL_LENGTH + 9);
        return level.hasChunkAt(end) && level.hasChunkAt(at.relative(dir.getClockWise(), 6)) && level.hasChunkAt(at.relative(dir.getCounterClockWise(), 6))
                && at.getY() - HALL_DEPTH - 2 > level.getMinY() + 4;
    }

    /** One slice of the tunnel, across its width: walls, floor, ceiling, and air inside. */
    private static void slice(ServerLevel level, BlockPos center, Direction dir, int halfWidth, int height, @Nullable BlockState floor, RandomSource random) {
        Direction right = dir.getClockWise();
        for (int l = -halfWidth - 1; l <= halfWidth + 1; l++) {
            BlockPos column = center.relative(right, l);
            for (int h = -1; h <= height; h++) {
                BlockPos p = column.above(h);
                boolean shell = Math.abs(l) == halfWidth + 1 || h == -1 || h == height;
                if (h == -1 && Math.abs(l) <= halfWidth && floor != null) set(level, p, floor);
                else set(level, p, shell ? oldStone(random) : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void buildHallway(ServerLevel level, BlockPos at, Direction dir) {
        RandomSource random = level.getRandom();
        Direction right = dir.getClockWise();
        BlockState stairs = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dir.getOpposite());
        // Down: one step per block.
        for (int i = 0; i < HALL_DEPTH; i++) slice(level, at.relative(dir, i).below(i + 1), dir, 1, 4, stairs, random);
        // The entrance looks like it was always there: a stone frame around the top step.
        for (int h = 0; h <= 3; h++) {
            set(level, at.relative(right, 2).above(h), oldStone(random));
            set(level, at.relative(right, -2).above(h), oldStone(random));
        }
        for (int l = -2; l <= 2; l++) set(level, at.relative(right, l).above(3), oldStone(random));
        // Along: long and straight, lit red, with words on the walls.
        BlockPos start = at.relative(dir, HALL_DEPTH).below(HALL_DEPTH);
        for (int i = 0; i < HALL_LENGTH; i++) {
            BlockPos c = start.relative(dir, i);
            slice(level, c, dir, 1, 3, null, random);
            if (i % 6 == 3) {
                set(level, c.relative(right, 1).above(1),
                        Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(RedstoneWallTorchBlock.FACING, right.getOpposite()));
            }
            int word = (i - 5) / 6;
            if (i >= 5 && (i - 5) % 6 == 0 && word < HALL_WORDS.length) {
                sign(level, c.relative(right, -1).above(1), right, HALL_WORDS[word]);
            }
        }
        // The room at the end: somebody's first night, underground.
        BlockPos room = start.relative(dir, HALL_LENGTH + 3);
        for (int f = -3; f <= 3; f++) slice(level, room.relative(dir, f), dir, 3, 4, null, random);
        for (int l = -4; l <= 4; l++) for (int h = -1; h <= 4; h++) set(level, room.relative(dir, 4).relative(right, l).above(h), oldStone(random));
        // A doorway into it from the hallway.
        for (int h = 0; h < 3; h++) set(level, room.relative(dir, -4).above(h), Blocks.AIR.defaultBlockState());
        for (int l = -1; l <= 1; l++) for (int h = 0; h < 3; h++) set(level, room.relative(dir, -4).relative(right, l).above(h), Blocks.AIR.defaultBlockState());
        BlockPos bedFoot = room.relative(dir, 2).relative(right, -2);
        BlockPos bedHead = bedFoot.relative(dir);
        set(level, bedFoot, Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, dir).setValue(BedBlock.PART, BedPart.FOOT));
        set(level, bedHead, Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, dir).setValue(BedBlock.PART, BedPart.HEAD));
        set(level, room.relative(dir, 3).relative(right, 2), Blocks.CRAFTING_TABLE.defaultBlockState());
        set(level, room.relative(dir, 3).relative(right, 1),
                Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, dir.getOpposite()));
        BlockPos chestPos = room.relative(dir, 3);
        set(level, chestPos, Blocks.CHEST.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, dir.getOpposite()));
        if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            chest.setItem(13, Journal.tornPage("page_hall"));
            chest.setItem(10, new ItemStack(Items.WOODEN_SWORD));
            chest.setItem(11, new ItemStack(Items.ROTTEN_FLESH, 3));
            chest.setItem(15, new ItemStack(Items.COAL, 5));
            chest.setItem(16, new ItemStack(Items.STRING, 2));
        }
        set(level, room.relative(dir, 3).above(2).relative(right, -1),
                Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(RedstoneWallTorchBlock.FACING, dir.getOpposite()));
        sign(level, room.relative(dir, 3).above(1).relative(right, -1), dir.getOpposite(), "this was", "my room");
    }

    private static void hallwaySecond(ServerLevel level, SteveMemory memory) {
        BlockPos at = pos(memory, Kind.HALLWAY);
        Direction dir = dir(memory, Kind.HALLWAY);
        BlockPos start = at.relative(dir, HALL_DEPTH).below(HALL_DEPTH);
        BlockPos room = start.relative(dir, HALL_LENGTH + 3);
        for (ServerPlayer player : level.players()) {
            if (!memory.flag("st_hallway_found") && player.blockPosition().distSqr(at) < 8 * 8) {
                memory.setFlag("st_hallway_found");
                Journal.unlock(level.getServer(), "note_hallway");
                StoryLog.beat(level.getServer(), player, "hallway");
                OriginalSteve steve = nearSteve(level, player);
                if (steve != null) Reactions.remark(steve, "hallway", 60, "I know where that goes. I don't want to. I know.",
                        "Stairs. Down. I dug those. Didn't I?");
            }
            if (memory.flag("st_hallway_done") || player.blockPosition().distSqr(room) > 4 * 4) continue;
            // You made it to the room. The lights go out behind you, from the far end.
            memory.setFlag("st_hallway_done");
            TORCHES_OUT.clear();
            Direction right = dir.getClockWise();
            for (int i = 0; i < HALL_LENGTH; i++) {
                if (i % 6 == 3) TORCHES_OUT.add(start.relative(dir, i).relative(right, 1).above(1));
            }
            Herobrine.whisper(player, "you walked all this way");
            Fx.soundTo(player, Vec3.atCenterOf(start), ModSounds.FAR_STEPS, SoundSource.HOSTILE, 1.0f, 1.0f);
            if (memory.highestTrust() >= 20f) Journal.unlock(level.getServer(), "mem_hallway");
            OriginalSteve steve = nearSteve(level, player);
            if (steve != null) {
                SteveVoice.steve(steve, "This was my first night. I dug down because up there, everything could see me.");
                memory.addSanity(-3f);
            }
            return;
        }
    }

    // ---- the obsidian box ---------------------------------------------------------------

    private static void buildBox(ServerLevel level, BlockPos at) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 4; dy++) {
                    boolean shell = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == -1 || dy == 3;
                    BlockPos p = at.offset(dx, dy, dz);
                    if (dy == 4) {
                        if (!level.getBlockState(p).isAir()) set(level, p, Blocks.AIR.defaultBlockState());
                        continue;
                    }
                    set(level, p, shell ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
                BlockPos under = at.offset(dx, -2, dz);
                if (level.getBlockState(under).canBeReplaced()) set(level, under, Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        // Inside, in the dark: someone kept count on the walls.
        set(level, at.offset(0, 0, 1), Blocks.CHEST.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        if (level.getBlockEntity(at.offset(0, 0, 1)) instanceof ChestBlockEntity chest) {
            chest.setItem(13, Journal.tornPage("page_box"));
            chest.setItem(4, new ItemStack(Items.FLINT_AND_STEEL));
        }
        sign(level, at.offset(1, 1, 0), Direction.WEST, "it is quiet", "in here");
        sign(level, at.offset(-1, 1, 0), Direction.EAST, "IIII IIII", "IIII IIII", "IIII IIII", "IIII II");
        set(level, at.offset(0, 2, -1), Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(RedstoneWallTorchBlock.FACING, Direction.SOUTH));
    }

    private static void boxSecond(ServerLevel level, SteveMemory memory) {
        BlockPos at = pos(memory, Kind.BOX);
        if (memory.flag("st_box_done")) return;
        boolean opened = false;
        for (int dx = -2; dx <= 2 && !opened; dx++) {
            for (int dz = -2; dz <= 2 && !opened; dz++) {
                for (int dy = -1; dy <= 3 && !opened; dy++) {
                    boolean shell = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == -1 || dy == 3;
                    if (shell && level.hasChunkAt(at) && !level.getBlockState(at.offset(dx, dy, dz)).is(Blocks.OBSIDIAN)) opened = true;
                }
            }
        }
        for (ServerPlayer player : level.players()) {
            double d = player.blockPosition().distSqr(at);
            if (d > 16 * 16) continue;
            if (memory.setFlag("st_box_found")) {
                Journal.unlock(level.getServer(), "note_box");
                StoryLog.beat(level.getServer(), player, "box");
                OriginalSteve steve = nearSteve(level, player);
                if (steve != null) Reactions.remark(steve, "box", 60, "Don't open it. Someone wanted it closed. Maybe it was me.",
                        "Leave it. Please. It's quiet when it's closed.");
            }
            if (opened) {
                memory.setFlag("st_box_done");
                StoryLog.beat(level.getServer(), player, "box_open");
                Herobrine.whisper(player, "you let it out");
                Fx.staticFlash(player, 12);
                memory.addSanity(-6f);
                OriginalSteve steve = nearSteve(level, player);
                if (steve != null) SteveVoice.steve(steve, "You opened it. ...It's empty. Why is it empty? Where did it go?");
                return;
            }
            // Something inside knocks when you come close. It stops when you stand right next to it.
            if (d < 10 * 10 && d > 3 * 3 && --knockCooldown <= 0) {
                knockCooldown = 3 + level.getRandom().nextInt(4);
                Fx.soundTo(player, Vec3.atCenterOf(at.above()), ModSounds.KNOCK, SoundSource.HOSTILE, 1.0f, 0.75f + level.getRandom().nextFloat() * 0.2f);
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    private static @Nullable OriginalSteve nearSteve(ServerLevel level, ServerPlayer player) {
        OriginalSteve steve = StoryDirector.findSteve(level);
        return steve != null && !steve.inEpisode() && steve.distanceToSqr(player) < 24 * 24 ? steve : null;
    }

    private static double horizontalDistSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
