package dev.leftbehind.entity;

import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.story.Journal;
import dev.leftbehind.story.Reactions;
import dev.leftbehind.story.SteveVoice;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The world around him, used the way a player uses it. He sleeps in a spare bed when you sleep, puts what he doesn't
 * need in your chest, picks ripe crops and plants the seeds again, reads signs out loud, plays the tune he half
 * remembers on a note block, picks a flower for you, and goes to look at anything he hasn't seen before: pulls the
 * lever, rings the bell, opens the door and closes it again, has a bite of the cake.
 */
public final class SteveChores {
    private final OriginalSteve steve;
    private final SteveWork work;
    private final SteveBuild build;
    private int next = 200;

    /** The first two lines of the tune, as note block notes (C is 6). */
    private static final int[] TUNE = {13, 6, 9, 8, -1, 13, 6, 11, 9, -1, 14, 13, 9, 8, 6, 5, 6, -1};

    SteveChores(OriginalSteve steve, SteveWork work) {
        this.steve = steve;
        this.work = work;
        this.build = new SteveBuild(steve, work);
    }

    public SteveBuild build() {
        return build;
    }

    @Nullable SteveWork.Job choose(ServerLevel level, SteveMemory memory, ServerPlayer anchor) {
        SteveWork.Job building = build.next(level, memory, anchor);
        if (building != null) return building;
        SteveWork.Job bed = sleep(level, anchor);
        if (bed != null) return bed;
        if (steve.tickCount < next) return null;
        next = steve.tickCount + 160 + steve.getRandom().nextInt(200);
        SteveWork.Job job = store(level, anchor);
        if (job == null) job = harvest(level, memory, anchor);
        if (job == null) job = readSign(level, memory);
        if (job == null) job = flower(level, memory, anchor);
        if (job == null) job = tune(level, memory, anchor);
        if (job == null) job = curious(level, memory);
        return job;
    }

    // ---- sleep ------------------------------------------------------------------------------

    private SteveWork.@Nullable Job sleep(ServerLevel level, ServerPlayer anchor) {
        if (!anchor.isSleeping() || steve.isSleeping()) return null;
        BlockPos bed = find(level, anchor.blockPosition(), 16, 4, st -> st.is(BlockTags.BEDS)
                && st.getValue(AbstractBedBlock.PART) == BedPart.HEAD && !st.getValue(AbstractBedBlock.OCCUPIED));
        if (bed == null) {
            Reactions.remark(steve, "no_bed", 900, "Sleep. I'll watch the door.", "No bed for me. That's fine. I don't really sleep.");
            return null;
        }
        return work.new Task("sleep", bed, 600, (l, m, t) -> {
            if (!anchor.isSleeping()) return true;
            BlockState st = l.getBlockState(bed);
            if (!st.is(BlockTags.BEDS) || st.getValue(AbstractBedBlock.OCCUPIED)) return true;
            if (!t.walk(bed, 2.2, 1.0)) return false;
            steve.startSleeping(bed);
            m.observe("did_sleep");
            Reactions.remark(steve, "sleep_bed", 300, "Goodnight. I'll take this one.", "A bed. I haven't slept in a bed since... I don't know since.");
            return true;
        });
    }

    // ---- chests -----------------------------------------------------------------------------

    /** How much of something he keeps on him. The rest goes in a chest. */
    private static int keep(ItemStack s) {
        if (s.isDamageableItem() || s.is(Items.TORCH) || s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE)) return 9999;
        if (s.is(ItemTags.LOGS)) return 8;
        if (s.is(ItemTags.PLANKS)) return 16;
        if (s.is(Items.STICK)) return 8;
        if (s.is(ItemTags.COALS)) return 16;
        if (s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)) return 32;
        if (s.is(Items.DIRT)) return 16;
        if (s.is(Items.RAW_IRON) || s.is(Items.IRON_INGOT)) return 6;
        if (s.has(net.minecraft.core.component.DataComponents.FOOD)) return 8;
        return 0;
    }

    private int extra() {
        Map<Item, Integer> totals = new java.util.HashMap<>();
        Map<Item, ItemStack> sample = new java.util.HashMap<>();
        for (int i = 0; i < work.stash.getContainerSize(); i++) {
            ItemStack s = work.stash.getItem(i);
            if (s.isEmpty() || SteveWork.RARE.contains(s.getItem())) continue;
            totals.merge(s.getItem(), s.getCount(), Integer::sum);
            sample.putIfAbsent(s.getItem(), s);
        }
        int n = 0;
        for (var e : totals.entrySet()) n += Math.max(0, e.getValue() - keep(sample.get(e.getKey())));
        return n;
    }

    private SteveWork.@Nullable Job store(ServerLevel level, ServerPlayer anchor) {
        if (extra() < 24) return null;
        BlockPos chest = find(level, anchor.blockPosition(), 16, 4, st -> st.is(Blocks.CHEST) || st.is(Blocks.BARREL) || st.is(Blocks.TRAPPED_CHEST));
        if (chest == null) return null;
        return work.new Task("store", chest, 500, (l, m, t) -> {
            if (!t.walk(chest, 2.6, 1.0)) return false;
            steve.getLookControl().setLookAt(Vec3.atCenterOf(chest));
            if (t.stage == 0) {
                lid(l, chest, true);
                steve.swingArm();
                t.stage = 1;
                t.counter = t.ticks();
            }
            if (t.ticks() - t.counter < 14) return false;
            if (t.stage == 1) {
                int moved = l.getBlockEntity(chest) instanceof Container box ? deposit(box) : 0;
                SteveVoice.steve(steve, moved > 0 ? pick("I put the extra in the chest. I kept what I need.",
                        "There. The chest has the rest. I'm lighter now.") : "It's full. You need another chest. Everyone always needs another chest.");
                t.stage = 2;
                t.counter = t.ticks();
            }
            if (t.ticks() - t.counter < 16) return false;
            lid(l, chest, false);
            return true;
        });
    }

    private int deposit(Container box) {
        Map<Item, Integer> kept = new java.util.HashMap<>();
        int moved = 0;
        for (int i = 0; i < work.stash.getContainerSize(); i++) {
            ItemStack s = work.stash.getItem(i);
            if (s.isEmpty() || SteveWork.RARE.contains(s.getItem())) continue;
            int may = keep(s) - kept.getOrDefault(s.getItem(), 0);
            int stay = Math.max(0, Math.min(may, s.getCount()));
            kept.merge(s.getItem(), stay, Integer::sum);
            int go = s.getCount() - stay;
            if (go <= 0) continue;
            ItemStack out = s.split(go);
            ItemStack left = HopperBlockEntity.addItem(null, box, out, null);
            moved += go - left.getCount();
            if (!left.isEmpty()) s.grow(left.getCount());
        }
        work.stash.setChanged();
        return moved;
    }

    private static void lid(ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BarrelBlock) {
            level.setBlock(pos, state.setValue(BarrelBlock.OPEN, open), 3);
            level.playSound(null, pos, open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.6f, 1f);
        } else {
            level.blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);
            level.playSound(null, pos, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.6f, 1f);
        }
    }

    // ---- crops ------------------------------------------------------------------------------

    private static boolean ripe(BlockState st) {
        return st.getBlock() instanceof CropBlock crop && crop.isMaxAge(st);
    }

    private SteveWork.@Nullable Job harvest(ServerLevel level, SteveMemory memory, ServerPlayer anchor) {
        if (!dev.leftbehind.story.StoryDirector.griefingAllowed(level)) return null;
        BlockPos first = find(level, anchor.blockPosition(), 12, 3, SteveChores::ripe);
        if (first == null) return null;
        if (memory.setFlag("steve_harvest")) SteveVoice.steve(steve, "These are ready. I'll pick them and put the seeds back. That's how it works, right?");
        return work.new Task("harvest", first, 900, (l, m, t) -> {
            BlockPos p = t.target();
            if (p == null) return true;
            BlockState st = l.getBlockState(p);
            if (!ripe(st)) {
                BlockPos more = find(l, p, 4, 1, SteveChores::ripe);
                if (more == null || t.counter >= 24) {
                    if (t.counter > 0) Reactions.remark(steve, "harvested", 300, "All picked. The seeds are back in.", "Done. They'll grow again. Things here grow back.");
                    return true;
                }
                t.target(more);
                return false;
            }
            if (!t.walk(p, 3.0, 0.9)) return false;
            steve.getLookControl().setLookAt(Vec3.atCenterOf(p));
            if (t.ticks() % 8 != 0) return false;
            steve.swingArm();
            for (ItemStack drop : Block.getDrops(st, l, p, null, steve, ItemStack.EMPTY)) work.give(drop);
            l.destroyBlock(p, false, steve);
            Item seed = st.getBlock().asItem();
            if (seed != Items.AIR && work.count(seed) > 0) {
                work.take(s -> s.is(seed), 1);
                l.setBlock(p, ((CropBlock) st.getBlock()).getStateForAge(0), 3);
                l.playSound(null, p, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1f, 1f);
            }
            t.counter++;
            m.observe("did_harvest");
            return false;
        });
    }

    // ---- signs ------------------------------------------------------------------------------

    private static String text(SignBlockEntity sign, SignTextSlot slot) {
        StringBuilder out = new StringBuilder();
        for (Component line : sign.getText(slot).getMessages(false)) {
            String s = line.getString().trim();
            if (s.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(s);
        }
        return out.toString();
    }

    private SteveWork.@Nullable Job readSign(ServerLevel level, SteveMemory memory) {
        BlockPos sign = find(level, steve.blockPosition(), 8, 3, st -> st.is(BlockTags.ALL_SIGNS));
        if (sign == null || memory.habit("read:" + sign.asLong()) > 0) return null;
        if (!(level.getBlockEntity(sign) instanceof SignBlockEntity be)) return null;
        String words = text(be, SignTextSlot.FRONT);
        if (words.isEmpty()) words = text(be, SignTextSlot.BACK);
        if (words.isEmpty()) return null;
        String said = words;
        return work.new Task("read", sign, 300, (l, m, t) -> {
            if (!t.walk(sign, 3.0, 0.9)) return false;
            steve.getLookControl().setLookAt(Vec3.atCenterOf(sign));
            if (t.ticks() < 30 && t.stage == 0) return false;
            m.observe("read:" + sign.asLong());
            SteveVoice.steve(steve, "It says: \"" + said + "\"");
            String low = said.toLowerCase(Locale.ROOT);
            if (low.contains("steve")) {
                Reactions.remark(steve, "sign_name", 60, "That's my name. Who wrote my name here?");
            } else if (low.contains("herobrine")) {
                m.addSanity(-1f);
                Reactions.remark(steve, "sign_him", 60, "Don't write his name. Writing it is like calling him.");
            } else if (low.contains("home") || low.contains("house")) {
                Reactions.remark(steve, "sign_home", 300, "Home. I like that word. I don't have one. I have this.");
            }
            return true;
        });
    }

    // ---- flowers ----------------------------------------------------------------------------

    private SteveWork.@Nullable Job flower(ServerLevel level, SteveMemory memory, ServerPlayer anchor) {
        if (memory.trust(anchor.getUUID()) < 40f || !memory.cooldown("flower_gift", level.getGameTime(), 24000)) return null;
        BlockPos flower = find(level, steve.blockPosition(), 10, 2, st -> st.is(BlockTags.SMALL_FLOWERS) && !st.is(Blocks.WITHER_ROSE));
        if (flower == null) return null;
        return work.new Task("flower", flower, 700, (l, m, t) -> {
            if (t.stage == 0) {
                BlockState st = l.getBlockState(flower);
                if (!st.is(BlockTags.SMALL_FLOWERS)) return true;
                if (!t.walk(flower, 2.5, 0.9)) return false;
                steve.swingArm();
                l.destroyBlock(flower, false, steve);
                l.playSound(null, flower, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5f, 1.2f);
                t.counter = st.getBlock().asItem() == Items.AIR ? 0 : 1;
                work.give(new ItemStack(st.getBlock().asItem()));
                t.stage = 1;
                return false;
            }
            if (!anchor.isAlive()) return true;
            if (steve.distanceToSqr(anchor) > 3 * 3) {
                t.walk(anchor.blockPosition(), 2.5, 1.0);
                return false;
            }
            for (ItemStack s : work.take(s -> s.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock().defaultBlockState().is(BlockTags.SMALL_FLOWERS), 1)) toss(l, anchor, s);
            SteveVoice.steve(steve, steve.getRandom(), "For you. Rana liked these. She was my friend.", "Here. I picked it for you. I don't know why. It felt right.");
            m.relationship(anchor.getUUID()).addTrust(1f);
            return true;
        });
    }

    void toss(ServerLevel level, ServerPlayer to, ItemStack stack) {
        steve.getLookControl().setLookAt(to, 30f, 30f);
        Vec3 dir = to.position().subtract(steve.position()).normalize().scale(0.25);
        ItemEntity drop = new ItemEntity(level, steve.getX(), steve.getEyeY() - 0.3, steve.getZ(), stack, dir.x, 0.2, dir.z);
        drop.setPickUpDelay(10);
        level.addFreshEntity(drop);
        steve.swingArm();
    }

    // ---- the tune ---------------------------------------------------------------------------

    private SteveWork.@Nullable Job tune(ServerLevel level, SteveMemory memory, ServerPlayer anchor) {
        if (memory.trust(anchor.getUUID()) < 25f || !memory.cooldown("note_tune", level.getGameTime(), 18000)) return null;
        BlockPos note = find(level, steve.blockPosition(), 10, 3, st -> st.getBlock() instanceof NoteBlock);
        if (note == null) {
            memory.setCounter("cd_note_tune", 0);
            return null;
        }
        return work.new Task("tune", note, 600, (l, m, t) -> {
            BlockState st = l.getBlockState(note);
            if (!(st.getBlock() instanceof NoteBlock)) return true;
            if (!t.walk(note, 2.5, 0.9)) return false;
            steve.getLookControl().setLookAt(Vec3.atCenterOf(note));
            if (t.stage == 0) {
                SteveVoice.steve(steve, "Listen. Someone taught me this one. I don't remember who.");
                t.stage = 1;
                t.counter = t.ticks() + 70;
                return false;
            }
            if (t.ticks() < t.counter) return false;
            int i = t.stage - 1;
            if (i >= TUNE.length) {
                SteveVoice.steve(steve, steve.getRandom(), "That's all of it I remember.", "There's more. I can't find the rest of it.");
                m.addSanity(3f);
                if (m.setFlag("steve_tune")) Journal.unlock(l.getServer(), "note_tune");
                dev.leftbehind.story.StoryLog.beat(l.getServer(), null, "tune");
                return true;
            }
            if (TUNE[i] >= 0) {
                int was = st.getValue(NoteBlock.NOTE);
                l.setBlock(note, st.setValue(NoteBlock.NOTE, TUNE[i]), 2);
                l.blockEvent(note, st.getBlock(), 0, 0);
                l.setBlock(note, st.setValue(NoteBlock.NOTE, was), 2);
                steve.swingArm();
            }
            t.stage++;
            t.counter = t.ticks() + 7;
            return false;
        });
    }

    // ---- anything new ------------------------------------------------------------------------

    /** What he says the first time he goes up to a block. Blocks not listed here only get a look. */
    private static String lineFor(BlockState st) {
        Block b = st.getBlock();
        if (b == Blocks.CRAFTING_TABLE) return "Everything starts with one of those.";
        if (b == Blocks.FURNACE || b == Blocks.SMOKER || b == Blocks.BLAST_FURNACE) return "Still warm. Somebody was cooking.";
        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) return "I won't take anything. I just like knowing what's in them.";
        if (b == Blocks.BARREL) return "A barrel. Everything in barrels smells like fish.";
        if (st.is(BlockTags.BEDS)) return "Is this yours? It's a good bed.";
        if (st.is(BlockTags.DOORS)) return "Doors. Open, close. They always forget to close them.";
        if (st.is(BlockTags.TRAPDOORS)) return "A little door in the floor. Where does it go?";
        if (b instanceof FenceGateBlock) return "A gate. For the animals. They never stay in anyway.";
        if (b instanceof LeverBlock) return "What does this do? ...nothing happened. Did something happen?";
        if (b instanceof ButtonBlock) return "I pressed it. Was I supposed to press it?";
        if (b == Blocks.BELL) return "Listen. Everybody used to come when it rang.";
        if (b instanceof NoteBlock) return "It sings. Every block should sing.";
        if (b instanceof CakeBlock) return "Is this for anyone? I'll just have a little.";
        if (st.is(BlockTags.CAMPFIRES)) return "Warm. Can we sit here for a while?";
        if (st.is(BlockTags.ANVIL)) return "Heavy. Don't stand under one of these.";
        if (b == Blocks.ENCHANTING_TABLE) return "The book reads you back. I don't like that.";
        if (b == Blocks.BOOKSHELF || b == Blocks.CHISELED_BOOKSHELF) return "Books. I could read, once. I think I still can.";
        if (b == Blocks.LECTERN) return "Somebody left a book open. You shouldn't read other people's books.";
        if (b == Blocks.JUKEBOX) return "It plays the old songs. I know all of them. I don't know how.";
        if (b == Blocks.BREWING_STAND) return "Bottles, bubbling. Don't drink anything you didn't make.";
        if (st.is(BlockTags.CAULDRONS)) return "Empty. Or full. I can't tell from here.";
        if (b == Blocks.COMPOSTER) return "Old things go in, new things come out. I wish it worked for people.";
        if (st.is(BlockTags.FLOWER_POTS)) return "Someone keeps a flower here. That's nice.";
        if (b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_CLUSTER) return "It hums. Can you hear it?";
        if (b == Blocks.SCULK || b == Blocks.SCULK_SENSOR || b == Blocks.SCULK_SHRIEKER || b == Blocks.SCULK_CATALYST) return "Quiet. It listens. It's listening right now.";
        if (b == Blocks.OBSIDIAN) return "That's the black stone. Like the middle.";
        if (b == Blocks.CRYING_OBSIDIAN) return "It's crying. Stone doesn't cry. This one does.";
        if (b == Blocks.TNT) return "Don't. Whatever you're thinking. Don't.";
        if (b == Blocks.CARVED_PUMPKIN || b == Blocks.JACK_O_LANTERN) return "It has a face. Why did you give it a face?";
        if (b == Blocks.HAY_BLOCK) return "Hay. It smells like the farm. There was a farm.";
        if (b == Blocks.SPAWNER || b == Blocks.TRIAL_SPAWNER) return "Something comes out of this. Don't stand near it.";
        if (b == Blocks.END_PORTAL_FRAME) return "I know this frame. I don't want to know it.";
        if (b == Blocks.RESPAWN_ANCHOR) return "You come back here when you die? Like me?";
        if (b == Blocks.LODESTONE) return "It points somewhere. Everything points somewhere.";
        if (b == Blocks.LOOM || b == Blocks.STONECUTTER || b == Blocks.GRINDSTONE || b == Blocks.SMITHING_TABLE
                || b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.FLETCHING_TABLE) return "Somebody's workbench. Neat. Nobody's that neat.";
        if (st.is(BlockTags.BEEHIVES)) return "Bees. Leave them be. They were here first.";
        if (b == Blocks.SKELETON_SKULL || b == Blocks.WITHER_SKELETON_SKULL || b == Blocks.ZOMBIE_HEAD || b == Blocks.PLAYER_HEAD) return "Whose is this? ...don't tell me.";
        if (b == Blocks.SLIME_BLOCK || b == Blocks.HONEY_BLOCK) return "It wobbles. I don't trust things that wobble.";
        if (b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN || b == Blocks.SHROOMLIGHT) return "Light that doesn't burn. I want one.";
        if (b == Blocks.BEACON) return "It points at the sky. Like it's calling something.";
        if (b == Blocks.DRAGON_EGG) return "That's not an egg. That's a promise.";
        if (b == Blocks.HOPPER) return "It takes things and passes them on. Like me.";
        if (b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.OBSERVER || b == Blocks.DISPENSER || b == Blocks.DROPPER) {
            return "Redstone things. They move when nobody touches them.";
        }
        if (b == Blocks.DECORATED_POT) return "Old pottery. Somebody drew on it. I think it's a story.";
        if (b == Blocks.TARGET) return "A target. Please don't practise on me.";
        if (b == Blocks.CONDUIT) return "It breathes. Underwater. I can hear it breathe.";
        return st.hasBlockEntity() ? "What's this one for?" : "";
    }

    private static boolean interesting(BlockState st) {
        if (st.isAir() || st.is(BlockTags.ALL_SIGNS) || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) return false;
        return !lineFor(st).isEmpty();
    }

    private static String id(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    }

    private SteveWork.@Nullable Job curious(ServerLevel level, SteveMemory memory) {
        if (!memory.cooldown("curious", level.getGameTime(), 1200)) return null;
        BlockPos thing = find(level, steve.blockPosition(), 7, 3, st -> interesting(st) && memory.habit("met:" + id(st)) == 0);
        if (thing == null) {
            memory.setCounter("cd_curious", 0);
            return null;
        }
        return work.new Task("look", thing, 400, (l, m, t) -> {
            BlockState st = l.getBlockState(thing);
            if (!interesting(st)) return true;
            if (!t.walk(thing, 2.6, 0.8)) return false;
            steve.getLookControl().setLookAt(Vec3.atCenterOf(thing));
            if (t.stage == 0) {
                if (t.counter++ < 16) return false;
                m.observe("met:" + id(st));
                SteveVoice.steve(steve, lineFor(st));
                boolean undo = touch(l, thing, st);
                t.stage = undo ? 1 : 2;
                t.counter = 0;
                return !undo;
            }
            // Leave it how he found it.
            if (t.counter++ < 40) return false;
            BlockState now = l.getBlockState(thing);
            if (now.is(Blocks.CHEST) || now.is(Blocks.TRAPPED_CHEST) || now.is(Blocks.BARREL)) lid(l, thing, false);
            else touch(l, thing, now);
            return true;
        });
    }

    /** Uses a block the way you would. Returns true if it should be put back the way it was afterwards. */
    private boolean touch(ServerLevel level, BlockPos pos, BlockState st) {
        Block b = st.getBlock();
        steve.swingArm();
        if (b instanceof LeverBlock lever) {
            lever.pull(st, level, pos, null);
            return true;
        }
        if (b instanceof ButtonBlock button && !st.getValue(ButtonBlock.POWERED)) {
            button.press(st, level, pos, null);
            return false;
        }
        if (b instanceof DoorBlock door && st.is(BlockTags.WOODEN_DOORS)) {
            door.setOpen(steve, level, st, pos, !st.getValue(DoorBlock.OPEN));
            return true;
        }
        if (b instanceof TrapDoorBlock && st.is(BlockTags.WOODEN_TRAPDOORS)) {
            boolean open = !st.getValue(TrapDoorBlock.OPEN);
            level.setBlock(pos, st.setValue(TrapDoorBlock.OPEN, open), 3);
            level.playSound(null, pos, open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1f, 1f);
            return true;
        }
        if (b instanceof FenceGateBlock) {
            boolean open = !st.getValue(FenceGateBlock.OPEN);
            level.setBlock(pos, st.setValue(FenceGateBlock.OPEN, open), 3);
            level.playSound(null, pos, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1f, 1f);
            return true;
        }
        if (b == Blocks.BELL) {
            Direction side = Direction.getApproximateNearest(steve.getX() - pos.getX() - 0.5, 0, steve.getZ() - pos.getZ() - 0.5);
            ((net.minecraft.world.level.block.BellBlock) b).attemptToRing(steve, level, pos, side.getAxis().isHorizontal() ? side : null);
            return false;
        }
        if (b instanceof NoteBlock) {
            level.blockEvent(pos, b, 0, 0);
            return false;
        }
        if (b instanceof CakeBlock && st.getValue(CakeBlock.BITES) < CakeBlock.MAX_BITES) {
            level.setBlock(pos, st.setValue(CakeBlock.BITES, st.getValue(CakeBlock.BITES) + 1), 3);
            level.playSound(null, pos, SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 1f, 1f);
            steve.heal(2f);
            return false;
        }
        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL) {
            lid(level, pos, true);
            return true;
        }
        return false;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private @Nullable BlockPos find(ServerLevel level, BlockPos center, int r, int dy, Predicate<BlockState> test) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -dy, -r), center.offset(r, dy, r))) {
            double d = p.distSqr(steve.blockPosition());
            if (d >= bestDist) continue;
            if (test.test(level.getBlockState(p))) {
                best = p.immutable();
                bestDist = d;
            }
        }
        return best;
    }

    private String pick(String... lines) {
        return lines[steve.getRandom().nextInt(lines.length)];
    }
}
