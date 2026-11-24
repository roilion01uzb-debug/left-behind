package dev.leftbehind.story;

import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.entity.Remnant;
import dev.leftbehind.memory.SteveMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Things in the world that nobody built: the first house, a stripped tree, a pyramid with no inside,
 * black stone at the middle of the map, a pillar of glass. They are all things the game once had and took away.
 */
public final class Relics {
    private Relics() {}

    private record Relic(String id, int act, long delay, String memory, @Nullable String page) {}

    private static final Relic[] RELICS = {
            new Relic("tree", 2, 3000, "", "page_5"),
            new Relic("house", 2, 9000, "mem_house", null),
            new Relic("pyramid", 3, 6000, "mem_pyramid", null),
            new Relic("zero", 3, 0, "mem_zero", null),
            new Relic("pillar", 4, 3000, "", null),
    };

    public static void tick(ServerLevel level, SteveMemory memory, long time) {
        if (!StoryDirector.griefingAllowed(level)) return;
        int act = memory.act();
        long inAct = time - memory.counter("act_start");
        for (Relic relic : RELICS) {
            String placed = "relic_" + relic.id;
            if (!memory.flag(placed)) {
                if (act < relic.act || (act == relic.act && inAct < relic.delay)) continue;
                if (level.getRandom().nextInt(3) != 0) continue;
                BlockPos at = place(level, relic.id);
                if (at != null) {
                    memory.setFlag(placed);
                    memory.setCounter(placed + "_x", at.getX());
                    memory.setCounter(placed + "_y", at.getY());
                    memory.setCounter(placed + "_z", at.getZ());
                }
                continue;
            }
            if (memory.flag("found_" + relic.id)) {
                // Steve only remembers the place once he has stood in front of it again.
                if (!relic.memory.isEmpty() && !memory.journal.contains(relic.memory)) {
                    BlockPos at = position(memory, relic.id);
                    OriginalSteve steve = StoryDirector.findSteve(level);
                    if (steve != null && !steve.inEpisode() && steve.blockPosition().distSqr(at) < 24 * 24) {
                        steve.getLookControl().setLookAt(at.getX(), at.getY() + 1, at.getZ());
                        SteveVoice.steve(steve, switch (relic.id) {
                            case "house" -> "That's... that was mine. I didn't build it. It was just there when I woke up. It was always just there.";
                            case "pyramid" -> "They used to be bigger than mountains. Nothing inside. I checked. I checked all of them.";
                            default -> "This is the middle. Everything is counted from here. Don't stand on it.";
                        });
                        memory.addSanity(-4f);
                        Journal.unlock(level.getServer(), relic.memory);
                    }
                }
                continue;
            }
            BlockPos at = position(memory, relic.id);
            for (ServerPlayer player : level.players()) {
                if (player.blockPosition().distSqr(at) < 9 * 9) {
                    memory.setFlag("found_" + relic.id);
                    if (memory.setFlag("relic_seen")) Journal.unlock(level.getServer(), "note_signs");
                    if (relic.page != null) Journal.unlock(level.getServer(), relic.page);
                    Progress.grant(player, "relic");
                    if (relic.id.equals("zero")) Progress.grant(player, "zero");
                    break;
                }
            }
        }
        // A mark Herobrine left where he stood.
        if (memory.flag("mark_pending")) {
            BlockPos at = position(memory, "mark");
            for (ServerPlayer player : level.players()) {
                if (player.blockPosition().distSqr(at) < 6 * 6) {
                    memory.clearFlag("mark_pending");
                    if (memory.setFlag("relic_seen")) Journal.unlock(level.getServer(), "note_signs");
                    if (memory.flag("mark_is_sign")) Journal.unlock(level.getServer(), "page_5");
                    break;
                }
            }
        }
    }

    private static BlockPos position(SteveMemory memory, String id) {
        String key = "relic_" + id;
        return new BlockPos((int) memory.counter(key + "_x"), (int) memory.counter(key + "_y"), (int) memory.counter(key + "_z"));
    }

    public static @Nullable BlockPos place(ServerLevel level, String id) {
        ServerPlayer player = StoryDirector.randomPlayer(level);
        if (player == null) return null;
        return switch (id) {
            case "tree" -> stripTree(level, player);
            case "house" -> {
                BlockPos at = flatGround(level, player.blockPosition(), 44, 80, 3);
                if (at != null) buildHouse(level, at);
                yield at;
            }
            case "pyramid" -> {
                BlockPos at = flatGround(level, player.blockPosition(), 56, 96, 4);
                if (at != null) buildPyramid(level, at);
                yield at;
            }
            case "zero" -> {
                if (player.blockPosition().distSqr(new BlockPos(0, player.getBlockY(), 0)) > 72 * 72 || !level.hasChunkAt(BlockPos.ZERO)) yield null;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
                BlockPos at = new BlockPos(0, y, 0);
                buildZero(level, at);
                yield at;
            }
            case "pillar" -> {
                BlockPos at = flatGround(level, player.blockPosition(), 40, 72, 1);
                if (at != null) buildPillar(level, at);
                yield at;
            }
            default -> null;
        };
    }

    /** Finds natural, dry, fairly level ground away from players and beds. */
    static @Nullable BlockPos flatGround(ServerLevel level, BlockPos center, int min, int max, int radius) {
        for (int attempt = 0; attempt < 8; attempt++) {
            BlockPos pos = StoryDirector.surfaceNear(level, center, min, max);
            if (pos == null) continue;
            BlockState ground = level.getBlockState(pos.below());
            if (!(ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(BlockTags.BASE_STONE_OVERWORLD) || ground.is(Blocks.GRASS_BLOCK)
                    || ground.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.GRAVEL))) continue;
            boolean ok = true;
            for (int dx = -radius; dx <= radius && ok; dx += radius) {
                for (int dz = -radius; dz <= radius && ok; dz += radius) {
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX() + dx, pos.getZ() + dz);
                    if (Math.abs(y - pos.getY()) > 2 || !level.getBlockState(new BlockPos(pos.getX() + dx, y - 1, pos.getZ() + dz)).getFluidState().isEmpty()) ok = false;
                }
            }
            for (ServerPlayer player : level.players()) {
                if (player.blockPosition().distSqr(pos) < 32 * 32) ok = false;
                BlockPos home = StoryDirector.homeOf(player, level);
                if (home != null && home.distSqr(pos) < 40 * 40) ok = false;
            }
            if (ok) return pos;
        }
        return null;
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 3);
    }

    /** The Indev starting house: planks, mossy cobblestone, two torches, a chest. Seven by seven. */
    private static void buildHouse(ServerLevel level, BlockPos at) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                set(level, at.offset(dx, -1, dz), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                // Something to stand on, if the ground falls away underneath.
                for (int dy = -2; dy >= -4; dy--) {
                    BlockPos under = at.offset(dx, dy, dz);
                    if (level.getBlockState(under).canBeReplaced()) set(level, under, Blocks.COBBLESTONE.defaultBlockState());
                }
                for (int dy = 0; dy < 3; dy++) {
                    set(level, at.offset(dx, dy, dz), wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
                set(level, at.offset(dx, 3, dz), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        // The doorway. There was never a door.
        set(level, at.offset(0, 0, -3), Blocks.AIR.defaultBlockState());
        set(level, at.offset(0, 1, -3), Blocks.AIR.defaultBlockState());
        set(level, at.offset(-2, 0, 2), Blocks.TORCH.defaultBlockState());
        set(level, at.offset(2, 0, 2), Blocks.TORCH.defaultBlockState());
        BlockPos chestPos = at.offset(0, 0, 2);
        set(level, chestPos, Blocks.CHEST.defaultBlockState());
        if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            chest.setItem(4, Journal.tornPage("page_4"));
            chest.setItem(10, new ItemStack(Items.OAK_PLANKS, 16));
            chest.setItem(11, new ItemStack(Items.TORCH, 8));
            chest.setItem(12, new ItemStack(Items.BREAD, 3));
            chest.setItem(13, Remnant.rose());
            chest.setItem(14, new ItemStack(Items.WOODEN_PICKAXE));
            chest.setItem(15, new ItemStack(Items.PAPER, 4));
            chest.setItem(16, new ItemStack(Items.BRICKS, 8));
        }
    }

    /** Brick, solid all the way through. The old ones were bigger than mountains. This is what is left of the idea. */
    private static void buildPyramid(ServerLevel level, BlockPos at) {
        for (int layer = 0; layer < 5; layer++) {
            int r = 4 - layer;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    set(level, at.offset(dx, layer, dz), Blocks.BRICKS.defaultBlockState());
                    if (layer == 0) {
                        for (int dy = -1; dy >= -3; dy--) {
                            BlockPos under = at.offset(dx, dy, dz);
                            if (level.getBlockState(under).canBeReplaced()) set(level, under, Blocks.BRICKS.defaultBlockState());
                        }
                    }
                }
            }
        }
    }

    /** Obsidian walls used to run out from zero in every direction. Four short arms are still here. */
    private static void buildZero(ServerLevel level, BlockPos at) {
        for (int i = -4; i <= 4; i++) {
            for (BlockPos column : new BlockPos[]{new BlockPos(i, 0, 0), new BlockPos(0, 0, i)}) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
                int height = Math.abs(i) <= 1 ? 3 : Math.abs(i) <= 3 ? 2 : 1;
                for (int dy = -1; dy < height; dy++) set(level, new BlockPos(column.getX(), y + dy, column.getZ()), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        BlockPos chestPos = new BlockPos(1, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 1, 1), 1);
        set(level, chestPos, Blocks.CHEST.defaultBlockState());
        if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) chest.setItem(13, Journal.tornPage("page_7"));
    }

    /** A pillar of glass. They used to mark the way to somewhere else. */
    private static void buildPillar(ServerLevel level, BlockPos at) {
        for (int dy = 0; dy < 14; dy++) set(level, at.above(dy), Blocks.GLASS.defaultBlockState());
        BlockPos chestPos = at.north();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, chestPos.getX(), chestPos.getZ());
        chestPos = new BlockPos(chestPos.getX(), y, chestPos.getZ());
        set(level, chestPos, Blocks.CHEST.defaultBlockState());
        if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) chest.setItem(13, Journal.tornPage("page_8"));
    }

    /** A tree with every leaf taken off, and one word at the bottom of it. */
    private static @Nullable BlockPos stripTree(ServerLevel level, ServerPlayer player) {
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = 18 + random.nextInt(22);
            int x = player.getBlockX() + (int) (Math.cos(angle) * dist);
            int z = player.getBlockZ() + (int) (Math.sin(angle) * dist);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (!level.getBlockState(new BlockPos(x, top - 1, z)).is(BlockTags.LEAVES)) continue;
            BlockPos crown = new BlockPos(x, top - 2, z);
            BlockPos trunk = null;
            for (BlockPos pos : BlockPos.betweenClosed(crown.offset(-3, -6, -3), crown.offset(3, 1, 3))) {
                if (level.getBlockState(pos).is(BlockTags.LOGS) && (trunk == null || pos.getY() < trunk.getY())) trunk = pos.immutable();
            }
            if (trunk == null) continue;
            for (BlockPos pos : BlockPos.betweenClosed(trunk.offset(-4, 0, -4), trunk.offset(4, 12, 4))) {
                if (level.getBlockState(pos).is(BlockTags.LEAVES)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, trunk.getX() + 1, trunk.getZ());
            BlockPos signPos = new BlockPos(trunk.getX() + 1, groundY, trunk.getZ());
            placeSign(level, signPos, "stop");
            return signPos;
        }
        return null;
    }

    static boolean placeSign(ServerLevel level, BlockPos pos, String word) {
        if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.below()).isSolid()) return false;
        level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            sign.updateText(text -> withLine(text, word), SignTextSlot.FRONT);
            sign.updateText(text -> withLine(text, word), SignTextSlot.BACK);
            sign.setWaxed(true);
        }
        return true;
    }

    private static SignText withLine(SignText text, String word) {
        return text.asMutable().setLine(1, Component.literal(word)).asImmutable();
    }

    /** Something Herobrine leaves where he was standing. */
    public static void leaveMark(ServerLevel level, BlockPos pos) {
        if (!StoryDirector.griefingAllowed(level)) return;
        SteveMemory memory = SteveMemory.get(level.getServer());
        if (memory.flag("mark_pending")) return;
        if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.below()).isSolid()) return;
        boolean sign = !memory.journal.contains("page_5") && level.getRandom().nextBoolean();
        if (sign) {
            if (!placeSign(level, pos, "stop")) return;
            memory.setFlag("mark_is_sign");
        } else {
            level.setBlock(pos, Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
            memory.clearFlag("mark_is_sign");
        }
        memory.setFlag("mark_pending");
        memory.setCounter("relic_mark_x", pos.getX());
        memory.setCounter("relic_mark_y", pos.getY());
        memory.setCounter("relic_mark_z", pos.getZ());
    }
}
