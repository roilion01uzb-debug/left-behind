package dev.leftbehind.entity;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.story.Journal;
import dev.leftbehind.story.SteveVoice;
import dev.leftbehind.story.StoryDirector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Steve doing his part, like a player would. He chops trees, makes a crafting table, makes better tools, mines,
 * lights the dark, finds rare things and brings them to you. He crafts with the game's real recipes, and the ones he
 * knows grow with every new thing he watches you make.
 *
 * <p>He carries what he gathers in his own inventory (saved with him, dropped if he dies). Everything here is game code;
 * no AI service is involved.
 */
public final class SteveWork {
    /** Things worth bringing to someone. */
    static final Set<Item> RARE = Set.of(Items.DIAMOND, Items.EMERALD, Items.RAW_GOLD, Items.GOLD_INGOT, Items.GOLD_NUGGET,
            Items.LAPIS_LAZULI, Items.AMETHYST_SHARD, Items.REDSTONE, Items.QUARTZ, Items.ECHO_SHARD, Items.NAME_TAG);

    /** What he keeps for his own work, even if you said you like it. */
    static final Set<Item> WORK_MATERIALS = Set.of(Items.OAK_LOG, Items.OAK_PLANKS, Items.COBBLESTONE, Items.COAL, Items.STICK, Items.TORCH);

    /** What he knows how to make before he has watched anyone. */
    static final List<Item> STARTING_RECIPES = List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.PALE_OAK_PLANKS, Items.STICK,
            Items.CRAFTING_TABLE, Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SWORD,
            Items.TORCH, Items.FURNACE, Items.CHEST, Items.IRON_PICKAXE);

    public static final TagKey<Block> COAL = BlockItemTags.COAL_ORES.block();
    public static final TagKey<Block> IRON = BlockItemTags.IRON_ORES.block();
    public static final TagKey<Block> COPPER = BlockItemTags.COPPER_ORES.block();
    public static final TagKey<Block> GOLD = BlockItemTags.GOLD_ORES.block();
    public static final TagKey<Block> DIAMOND = BlockItemTags.DIAMOND_ORES.block();
    public static final TagKey<Block> EMERALD = BlockItemTags.EMERALD_ORES.block();
    public static final TagKey<Block> LAPIS = BlockItemTags.LAPIS_ORES.block();
    public static final TagKey<Block> REDSTONE = BlockItemTags.REDSTONE_ORES.block();

    private final OriginalSteve steve;
    final SimpleContainer stash = new SimpleContainer(36);
    private @Nullable Job job;
    private int thinkCooldown = 100;
    private int lastTorch;
    /** Players asked him for something: what, for whom. */
    private @Nullable Request request;
    /** Blocks he gave up on (couldn't reach). */
    private final Set<Long> unreachable = new HashSet<>();

    /** Something he offered a player ("Do you want them?"), waiting for a yes or a no. */
    private @Nullable Offer offer;
    /** Told to stop working until then (game time). */
    private long pausedUntil;
    private @Nullable String lastNote;
    /** Torches left to put down for a "light it up" request. */
    private int lightsLeft;

    /** A player asked for something. Count 0 with a torch means "light this place up". */
    public record Request(UUID player, Item item, int count, boolean craft) {}

    public record Offer(UUID player, Item item, int count, boolean craft, long until) {}

    SteveWork(OriginalSteve steve) {
        this.steve = steve;
    }

    // ---- saving -----------------------------------------------------------------------

    void save(ValueOutput output) {
        stash.storeAsItemList(output.list("stash", ItemStack.CODEC));
    }

    void load(ValueInput input) {
        input.list("stash", ItemStack.CODEC).ifPresent(list -> stash.fromItemList((ValueInput.TypedInputList<ItemStack>) list));
    }

    void dropAll(ServerLevel level) {
        for (int i = 0; i < stash.getContainerSize(); i++) {
            ItemStack stack = stash.removeItemNoUpdate(i);
            if (!stack.isEmpty()) steve.spawnAtLocation(level, stack);
        }
    }

    // ---- what he has --------------------------------------------------------------------

    public int count(Item item) {
        return stash.countItem(item);
    }

    public int count(TagKey<Item> tag) {
        return count(s -> s.is(tag));
    }

    public int count(Predicate<ItemStack> test) {
        int n = 0;
        for (int i = 0; i < stash.getContainerSize(); i++) {
            ItemStack s = stash.getItem(i);
            if (!s.isEmpty() && test.test(s)) n += s.getCount();
        }
        return n;
    }

    /** What counts as having an item when someone asks: any logs for "wood", raw iron for "iron", and so on. */
    public static Predicate<ItemStack> matchFor(Item item) {
        if (item == Items.OAK_LOG) return s -> s.is(ItemTags.LOGS);
        if (item == Items.OAK_PLANKS) return s -> s.is(ItemTags.PLANKS);
        if (item == Items.COAL) return s -> s.is(ItemTags.COALS);
        if (item == Items.COBBLESTONE) return s -> s.is(ItemTags.STONE_TOOL_MATERIALS);
        if (item == Items.IRON_INGOT) return s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON);
        if (item == Items.GOLD_INGOT) return s -> s.is(Items.GOLD_INGOT) || s.is(Items.RAW_GOLD);
        if (item == Items.COPPER_INGOT) return s -> s.is(Items.COPPER_INGOT) || s.is(Items.RAW_COPPER);
        return s -> s.is(item);
    }

    public int countFor(Item item) {
        return count(matchFor(item));
    }

    /** Takes up to n matching items out of his inventory. */
    public List<ItemStack> take(Predicate<ItemStack> test, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < stash.getContainerSize() && n > 0; i++) {
            ItemStack s = stash.getItem(i);
            if (s.isEmpty() || !test.test(s)) continue;
            int k = Math.min(n, s.getCount());
            out.add(s.split(k));
            n -= k;
        }
        stash.setChanged();
        return out;
    }

    public void give(ItemStack stack) {
        ItemStack left = stash.addItem(stack);
        if (!left.isEmpty() && steve.level() instanceof ServerLevel level) steve.spawnAtLocation(level, left);
    }

    /** A short list of what he's carrying, for "what do you have?". */
    public String describe() {
        List<String> parts = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        for (int i = 0; i < stash.getContainerSize(); i++) {
            ItemStack s = stash.getItem(i);
            if (s.isEmpty() || !seen.add(s.getItem())) continue;
            int n = count(s.getItem());
            String name = s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
            parts.add(n + " " + (n > 1 ? plural(name) : name));
            if (parts.size() >= 6) break;
        }
        if (parts.size() == 2) return parts.getFirst() + " and " + parts.getLast();
        if (parts.size() > 2) parts.set(parts.size() - 1, "and " + parts.getLast());
        return String.join(", ", parts);
    }

    public boolean isEmpty() {
        return stash.isEmpty();
    }

    public boolean busy() {
        return job != null;
    }

    public @Nullable String jobName() {
        return job == null ? null : job.name();
    }

    // ---- knowing how to make things --------------------------------------------------------

    public static boolean knows(SteveMemory memory, Item item) {
        return STARTING_RECIPES.contains(item) || memory.habit("knows:" + BuiltInRegistries.ITEM.getKey(item)) > 0;
    }

    /** He watched someone make this. Returns true if it was new to him. */
    public static boolean learn(SteveMemory memory, Item item) {
        if (knows(memory, item)) return false;
        memory.observe("knows:" + BuiltInRegistries.ITEM.getKey(item));
        return true;
    }

    public static int knownCount(SteveMemory memory) {
        int n = STARTING_RECIPES.size();
        for (String key : memory.habitKeys()) if (key.startsWith("knows:")) n++;
        return n;
    }

    /** One way to make an item from the game's recipes: the slots it needs filled, and what comes out. */
    record Plan(List<List<ItemStack>> slots, ItemStack result, boolean needsTable) {}

    static List<Plan> plans(ServerLevel level, Item item) {
        List<Plan> plans = new ArrayList<>();
        ContextMap context = SlotDisplayContext.fromLevel(level);
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
            for (RecipeDisplay display : recipe.display()) {
                List<SlotDisplay> ingredients;
                SlotDisplay result;
                boolean table;
                if (display instanceof ShapedCraftingRecipeDisplay shaped) {
                    ingredients = shaped.ingredients();
                    result = shaped.result();
                    table = shaped.width() > 2 || shaped.height() > 2;
                } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
                    ingredients = shapeless.ingredients();
                    result = shapeless.result();
                    table = ingredients.size() > 4;
                } else {
                    continue;
                }
                ItemStack out = result.resolveForFirstStack(context);
                if (out.isEmpty() || !out.is(item)) continue;
                List<List<ItemStack>> slots = new ArrayList<>();
                for (SlotDisplay slot : ingredients) {
                    List<ItemStack> options = slot.resolveForStacks(context);
                    if (!options.isEmpty()) slots.add(options);
                }
                if (!slots.isEmpty()) plans.add(new Plan(slots, out, table));
            }
        }
        return plans;
    }

    /** The first plan he has everything for, or null. */
    @Nullable Plan plan(ServerLevel level, Item item) {
        for (Plan plan : plans(level, item)) if (canFill(plan)) return plan;
        return null;
    }

    private boolean canFill(Plan plan) {
        // Count what each slot would use, greedily, without taking anything yet.
        java.util.Map<Item, Integer> used = new java.util.HashMap<>();
        for (List<ItemStack> options : plan.slots()) {
            boolean found = false;
            for (ItemStack option : options) {
                Item it = option.getItem();
                if (count(it) - used.getOrDefault(it, 0) >= 1) {
                    used.merge(it, 1, Integer::sum);
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Makes one batch. Returns what came out, or empty if he was missing something. */
    ItemStack craft(Plan plan) {
        if (!canFill(plan)) return ItemStack.EMPTY;
        for (List<ItemStack> options : plan.slots()) {
            for (ItemStack option : options) {
                if (count(option.getItem()) >= 1) {
                    take(s -> s.is(option.getItem()), 1);
                    break;
                }
            }
        }
        ItemStack out = plan.result().copy();
        give(out.copy());
        return out;
    }

    // ---- requests from players -------------------------------------------------------

    public void ask(Request r) {
        request = r;
        job = null;
        thinkCooldown = 0;
        pausedUntil = 0;
        if (r.item() == Items.TORCH && r.count() == 0) lightsLeft = 6;
    }

    public @Nullable Request request() {
        return request;
    }

    public @Nullable Offer offer(UUID player, long now) {
        Offer o = offer;
        return o != null && o.player().equals(player) && now < o.until() ? o : null;
    }

    public void offer(UUID player, Item item, int count, boolean craft, long now) {
        offer = new Offer(player, item, count, craft, now + 1200);
    }

    public void clearOffer() {
        offer = null;
    }

    /** "Stop working": he stands down for a while, unless someone asks him for something. */
    public void pause(long now, int ticks) {
        request = null;
        lightsLeft = 0;
        stop();
        pausedUntil = now + ticks;
    }

    /** "Never mind": drops what he was asked and what he was doing. */
    public void cancel() {
        request = null;
        lightsLeft = 0;
        stop();
    }

    /** Can he get this from the world himself (a tree, stone, an ore, a block)? */
    public boolean canGather(Item item) {
        return sourceOf(item) != null;
    }

    /** What he'd still need to make an item, said the way a person would ("3 planks and 2 sticks"), or null if he has it all. */
    public @Nullable String missingFor(ServerLevel level, Item item) {
        List<Plan> all = plans(level, item);
        if (all.isEmpty()) return "a way to make it";
        for (Plan p : all) if (canFill(p)) return null;
        Plan plan = all.getFirst();
        java.util.Map<Item, Integer> used = new java.util.HashMap<>();
        java.util.Map<String, Integer> missing = new java.util.LinkedHashMap<>();
        for (List<ItemStack> options : plan.slots()) {
            boolean found = false;
            for (ItemStack option : options) {
                Item it = option.getItem();
                if (count(it) - used.getOrDefault(it, 0) >= 1) {
                    used.merge(it, 1, Integer::sum);
                    found = true;
                    break;
                }
            }
            if (!found) missing.merge(describe(options), 1, Integer::sum);
        }
        return listOf(missing);
    }

    /** How to make something with the game's recipe ("3 cobblestone and 2 sticks, at a crafting table"), or null if it isn't crafted. */
    public static @Nullable String describeRecipe(ServerLevel level, Item item) {
        List<Plan> all = plans(level, item);
        if (all.isEmpty()) return null;
        Plan plan = all.getFirst();
        java.util.Map<String, Integer> parts = new java.util.LinkedHashMap<>();
        for (List<ItemStack> options : plan.slots()) parts.merge(describe(options), 1, Integer::sum);
        String out = listOf(parts);
        if (plan.result().getCount() > 1) out += ", and you get " + plan.result().getCount();
        return out + (plan.needsTable() ? ", at a crafting table" : ". You don't even need a table");
    }

    private static String describe(List<ItemStack> options) {
        ItemStack first = options.getFirst();
        if (options.size() > 1) {
            if (first.is(ItemTags.PLANKS)) return "planks";
            if (first.is(ItemTags.LOGS)) return "logs";
            if (first.is(ItemTags.COALS)) return "coal";
            if (first.is(ItemTags.STONE_TOOL_MATERIALS)) return "cobblestone";
            if (first.is(ItemTags.WOOL)) return "wool";
        }
        return first.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
    }

    private static String listOf(java.util.Map<String, Integer> parts) {
        List<String> words = new ArrayList<>();
        for (var e : parts.entrySet()) {
            String name = e.getKey();
            int n = e.getValue();
            if (n > 1) name = plural(name);
            words.add((n == 1 && !name.equals("coal") && !name.equals("cobblestone") ? (startsWithVowel(name) ? "an " : "a ") : n + " ") + name);
        }
        if (words.size() == 1) return words.getFirst();
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
    }

    /** "2 oak logs", "3 sticks", but "8 cobblestone" and "4 coal". */
    static String plural(String name) {
        if (name.endsWith("s") || name.equals("coal") || name.endsWith("cobblestone") || name.endsWith("wool") || name.endsWith("dust")
                || name.endsWith("glass") || name.endsWith("iron") || name.endsWith("gold") || name.endsWith("copper") || name.endsWith("redstone")
                || name.endsWith("lapis lazuli") || name.endsWith("dirt") || name.endsWith("sand") || name.endsWith("gravel")) {
            return name;
        }
        if (name.endsWith("y") && !name.endsWith("ey")) return name.substring(0, name.length() - 1) + "ies";
        if (name.endsWith("ch") || name.endsWith("sh") || name.endsWith("x")) return name + "es";
        return name + "s";
    }

    private static boolean startsWithVowel(String s) {
        return !s.isEmpty() && "aeiou".indexOf(s.charAt(0)) >= 0;
    }

    // ---- the loop ---------------------------------------------------------------------

    /** Can he work right now? Called by the work goal. */
    boolean wantsToWork(ServerLevel level, SteveMemory memory) {
        if (job != null) return true;
        // From before 0.6 he always held a wooden axe. It goes in his pockets now.
        ItemStack held = steve.getMainHandItem();
        if (held.is(Items.WOODEN_AXE) && count(Items.WOODEN_AXE) == 0 && !memory.flag("axe_pocketed")) {
            memory.setFlag("axe_pocketed");
            give(held.copy());
        }
        if (--thinkCooldown > 0) return false;
        thinkCooldown = 40 + steve.getRandom().nextInt(40);
        if (level.getGameTime() < pausedUntil && request == null) return false;
        Request asked = request;
        job = choose(level, memory);
        if (asked != null) {
            // What he does about what you asked for goes in the log, so a stuck request can be seen.
            String note = BuiltInRegistries.ITEM.getKey(asked.item()).getPath() + " -> " + (job == null ? "nothing" : job.name())
                    + (request == null ? " (done)" : "");
            if (!note.equals(lastNote)) LeftBehind.LOGGER.info("Steve, asked for {}", note);
            lastNote = note;
        }
        return job != null;
    }

    void tick(ServerLevel level, SteveMemory memory) {
        Job j = job;
        if (j == null) return;
        j.ticks++;
        boolean done;
        try {
            done = j.tick(level, memory);
        } catch (RuntimeException e) {
            LeftBehind.LOGGER.warn("Steve's {} went wrong", j.name(), e);
            done = true;
        }
        if (done || j.ticks > j.limit) {
            if (j.ticks > j.limit && j.target != null) unreachable.add(j.target.asLong());
            level.destroyBlockProgress(steve.getId(), j.target == null ? steve.blockPosition() : j.target, -1);
            if (job == j) job = null;
            thinkCooldown = Math.min(thinkCooldown, 20);
        }
    }

    void stop() {
        if (job != null && steve.level() instanceof ServerLevel level && job.target != null) {
            level.destroyBlockProgress(steve.getId(), job.target, -1);
        }
        job = null;
        thinkCooldown = 60;
    }

    /** Decides what to do next, the way a player would: the thing that unlocks the next thing. */
    private @Nullable Job choose(ServerLevel level, SteveMemory memory) {
        ServerPlayer anchor = anchor(level, memory);
        if (anchor == null) return null;
        boolean griefing = StoryDirector.griefingAllowed(level);

        // Someone asked.
        if (request != null) {
            Job asked = forRequest(level, memory, anchor, griefing);
            if (asked != null) return asked;
        }

        // Something rare: bring it to whoever he trusts.
        if (count(s -> RARE.contains(s.getItem())) > 0 && memory.trust(anchor.getUUID()) >= 30f && steve.distanceToSqr(anchor) < 32 * 32) {
            return new Hand(anchor, s -> RARE.contains(s.getItem()), 64, Lines.FOUND_SOMETHING);
        }

        // You told him what you like. He kept some for you.
        String liked = memory.relationship(anchor.getUUID()).note("likes_item");
        if (liked != null) {
            Identifier likedId = Identifier.tryParse(liked);
            Item li = likedId == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(likedId);
            if (li != Items.AIR && !WORK_MATERIALS.contains(li) && countFor(li) > 0 && steve.distanceToSqr(anchor) < 32 * 32
                    && memory.cooldown("liked_" + anchor.getUUID(), level.getGameTime(), 2400)) {
                return new Hand(anchor, matchFor(li), 64, Lines.YOU_LIKE);
            }
        }

        // At night, the dark around you gets smaller.
        if (level.isDarkOutside() || anchor.getY() < 50) {
            if (count(Items.TORCH) == 0 && count(ItemTags.COALS) > 0 && count(Items.STICK) > 0) return new Craft(Items.TORCH, anchor);
            BlockPos dark = darkSpotNear(level, anchor, false);
            if (dark != null && count(Items.TORCH) > 0 && steve.tickCount - lastTorch > 300) return new Light(dark);
        }

        // He does what he has seen you do. After a couple of days he remembers the rest on his own.
        boolean remembers = level.getOverworldClockTime() / 24000L >= 2;
        boolean sawChop = remembers || memory.habit("chop_wood") > 0;
        boolean sawMine = remembers || memory.habit("mine") > 0;
        if (!sawChop) return null;

        int planks = count(s -> s.is(ItemTags.PLANKS));
        int logs = count(s -> s.is(ItemTags.LOGS));
        int sticks = count(Items.STICK);
        boolean table = tableNear(level, steve.blockPosition(), 24) != null;
        boolean woodPick = count(Items.WOODEN_PICKAXE) + count(Items.STONE_PICKAXE) + count(Items.IRON_PICKAXE) > 0;
        boolean stonePick = count(Items.STONE_PICKAXE) + count(Items.IRON_PICKAXE) > 0;
        boolean ironPick = count(Items.IRON_PICKAXE) > 0;

        // Wood first. Everything starts with wood.
        if (logs + planks / 4 < 2 && griefing) {
            Job chop = gather(level, anchor, SteveWork::naturalLog, 6, pick(Lines.WOOD));
            if (chop != null) return chop;
        }
        if (planks < 8 && logs > 0) return new Craft(plankFor(), anchor);
        if (!table && count(Items.CRAFTING_TABLE) == 0 && planks >= 4) return new Craft(Items.CRAFTING_TABLE, anchor);
        if (!table && count(Items.CRAFTING_TABLE) > 0) {
            BlockPos spot = groundNear(level, anchor.blockPosition(), 3, 6);
            if (spot != null) return new Place(spot, Items.CRAFTING_TABLE, Lines.TABLE);
        }
        if (sticks < 4 && planks >= 2) return new Craft(Items.STICK, anchor);
        if (!woodPick && table && planks >= 3 && sticks >= 2) return new Craft(Items.WOODEN_PICKAXE, anchor);
        if (!griefing || !sawMine) return null;

        // Stone tools: better than wood, and he remembers that much.
        int cobble = count(s -> s.is(ItemTags.STONE_TOOL_MATERIALS));
        if (woodPick && !stonePick) {
            if (cobble >= 3 && sticks >= 2 && table) return new Craft(Items.STONE_PICKAXE, anchor);
            Job stone = gather(level, anchor, (l, p, st) -> st.is(BlockTags.BASE_STONE_OVERWORLD) && exposed(l, p), 3, pick(Lines.STONE));
            if (stone != null) return stone;
        }
        if (stonePick && count(Items.STONE_AXE) + count(Items.IRON_PICKAXE) == 0 && cobble >= 3 && sticks >= 2 && table) {
            return new Craft(Items.STONE_AXE, anchor);
        }

        // Iron: a furnace, coal, and he can make an iron pickaxe.
        if (stonePick && !ironPick) {
            int raw = count(Items.RAW_IRON) + count(Items.IRON_INGOT);
            if (count(Items.IRON_INGOT) >= 3 && sticks >= 2 && table) return new Craft(Items.IRON_PICKAXE, anchor);
            if (count(Items.RAW_IRON) > 0 && count(ItemTags.COALS) > 0) {
                BlockPos furnace = furnaceNear(level, steve.blockPosition(), 24);
                if (furnace != null) return new Smelt(furnace, Items.RAW_IRON, Items.IRON_INGOT);
                if (count(Items.FURNACE) > 0) {
                    BlockPos spot = groundNear(level, anchor.blockPosition(), 3, 6);
                    if (spot != null) return new Place(spot, Items.FURNACE, Lines.FURNACE);
                }
                if (cobble >= 8 && table) return new Craft(Items.FURNACE, anchor);
            }
            if (raw < 3) {
                Job iron = gather(level, anchor, (l, p, st) -> st.is(IRON) && exposed(l, p), 3, pick(Lines.IRON));
                if (iron != null) return iron;
            }
        }

        // Ores he can see, best first.
        if (stonePick) {
            Job ore = gather(level, anchor, (l, p, st) -> (st.is(DIAMOND) || st.is(EMERALD) || st.is(GOLD) || st.is(LAPIS) || st.is(REDSTONE))
                    && exposed(l, p) && canMine(st), 4, pick(Lines.SHINY));
            if (ore != null) return ore;
        }
        if (woodPick && count(ItemTags.COALS) < 8) {
            Job coal = gather(level, anchor, (l, p, st) -> st.is(COAL) && exposed(l, p), 4, pick(Lines.COAL));
            if (coal != null) return coal;
        }
        // Keep a little wood in stock, like anyone would.
        if (logs + planks / 4 < 6 && steve.getRandom().nextInt(3) == 0) {
            return gather(level, anchor, SteveWork::naturalLog, 4, null);
        }
        // Nothing left that he needs. He asks you instead of standing there.
        if (stonePick && steve.distanceToSqr(anchor) < 10 * 10 && memory.cooldown("idle_offer", level.getGameTime(), 12000)) {
            dev.leftbehind.story.Reactions.remark(steve, "idle_offer", 600, Lines.IDLE_OFFER);
        }
        return null;
    }

    /** What to do about the thing a player asked for. Null when it's done or can't be done (he says so). */
    private @Nullable Job forRequest(ServerLevel level, SteveMemory memory, ServerPlayer anchor, boolean griefing) {
        Request r = request;
        if (r == null) return null;
        ServerPlayer asker = level.getServer().getPlayerList().getPlayer(r.player());
        if (asker == null || asker.level() != level) {
            request = null;
            return null;
        }
        // "Light it up."
        if (r.item() == Items.TORCH && r.count() == 0) {
            if (lightsLeft > 0 && count(Items.TORCH) > 0) {
                BlockPos dark = darkSpotNear(level, asker, true);
                if (dark != null) return new Light(dark);
            }
            if (lightsLeft > 0 && count(Items.TORCH) == 0) {
                Job make = prepare(level, memory, Items.TORCH, anchor, griefing, 0);
                if (make != null) return make;
                request = null;
                lightsLeft = 0;
                SteveVoice.steve(steve, Lines.NO_COAL);
                return null;
            }
            request = null;
            lightsLeft = 0;
            SteveVoice.steve(steve, Lines.BRIGHT_ENOUGH);
            return null;
        }
        Predicate<ItemStack> match = matchFor(r.item());
        int have = count(match);
        if (have >= r.count()) {
            request = null;
            // Something he made for you: the whole batch is yours (a recipe for ladders makes three).
            return new Hand(asker, match, r.craft() ? have : r.count(), r.craft() ? Lines.MADE_IT : Lines.HERE_YOU_GO);
        }
        if (r.craft()) {
            Job make = prepare(level, memory, r.item(), anchor, griefing, 0);
            if (make != null) return make;
            request = null;
            SteveVoice.steve(steve, Lines.CANT_MAKE);
            return null;
        }
        Job fetch = fetch(level, memory, anchor, r.item(), r.count() - have, griefing);
        if (fetch != null) return fetch;
        if (knows(memory, r.item())) {
            Job make = prepare(level, memory, r.item(), anchor, griefing, 0);
            if (make != null) return make;
        }
        request = null;
        if (have > 0) return new Hand(asker, match, have, Lines.ALL_I_FOUND);
        SteveVoice.steve(steve, steve.getRandom(), "I looked. I can't find any. I'm sorry.", "There isn't any near here. I'll keep an eye out.");
        return null;
    }

    /** The next step toward making an item: craft it, or make or find what it needs first, or set up a table. */
    private @Nullable Job prepare(ServerLevel level, SteveMemory memory, Item item, ServerPlayer anchor, boolean griefing, int depth) {
        if (depth > 4) return null;
        if (item == Items.IRON_INGOT || item == Items.GOLD_INGOT || item == Items.COPPER_INGOT) {
            Job smelt = smelting(level, memory, anchor, item, griefing, depth);
            if (smelt != null) return smelt;
        }
        List<Plan> all = plans(level, item);
        if (all.isEmpty()) return null;
        for (Plan p : all) {
            if (!canFill(p)) continue;
            if (p.needsTable() && tableNear(level, steve.blockPosition(), 24) == null) return table(level, memory, anchor, griefing, depth);
            return new Craft(item, anchor);
        }
        // Try the plans he is closest to finishing first (sticks from planks before sticks from bamboo he can't find).
        List<Plan> sorted = new ArrayList<>(all);
        sorted.sort(java.util.Comparator.comparingInt(p -> missingSlots(p).size()));
        for (Plan plan : sorted) {
            List<List<ItemStack>> missing = missingSlots(plan);
            if (missing.isEmpty()) continue;
            List<ItemStack> options = missing.getFirst();
            // Something he could make right now from what he has (birch planks from birch logs).
            for (ItemStack option : options) {
                Item it = option.getItem();
                Plan p = knows(memory, it) ? plan(level, it) : null;
                if (p == null) continue;
                if (p.needsTable() && tableNear(level, steve.blockPosition(), 24) == null) return table(level, memory, anchor, griefing, depth);
                return new Craft(it, anchor);
            }
            int tried = 0;
            for (ItemStack option : options) {
                if (++tried > 4) break;
                Item it = option.getItem();
                Job deeper = knows(memory, it) ? prepare(level, memory, it, anchor, griefing, depth + 1) : null;
                if (deeper != null) return deeper;
                Job found = fetch(level, memory, anchor, it, Math.max(2, missing.size()), griefing);
                if (found != null) return found;
            }
        }
        return null;
    }

    private List<List<ItemStack>> missingSlots(Plan plan) {
        java.util.Map<Item, Integer> used = new java.util.HashMap<>();
        List<List<ItemStack>> missing = new ArrayList<>();
        for (List<ItemStack> options : plan.slots()) {
            boolean found = false;
            for (ItemStack option : options) {
                Item it = option.getItem();
                if (count(it) - used.getOrDefault(it, 0) >= 1) {
                    used.merge(it, 1, Integer::sum);
                    found = true;
                    break;
                }
            }
            if (!found) missing.add(options);
        }
        return missing;
    }

    /** He needs a crafting table close by: put his down, or make one. */
    private @Nullable Job table(ServerLevel level, SteveMemory memory, ServerPlayer anchor, boolean griefing, int depth) {
        if (count(Items.CRAFTING_TABLE) > 0) {
            BlockPos spot = groundNear(level, anchor.blockPosition(), 3, 6);
            return spot == null ? null : new Place(spot, Items.CRAFTING_TABLE, Lines.TABLE);
        }
        return prepare(level, memory, Items.CRAFTING_TABLE, anchor, griefing, depth + 1);
    }

    /** Ingots: smelt the raw ore he has, with coal, at a furnace (his own if there isn't one). */
    private @Nullable Job smelting(ServerLevel level, SteveMemory memory, ServerPlayer anchor, Item ingot, boolean griefing, int depth) {
        Item raw = ingot == Items.IRON_INGOT ? Items.RAW_IRON : ingot == Items.GOLD_INGOT ? Items.RAW_GOLD : Items.RAW_COPPER;
        if (count(raw) == 0) return fetch(level, memory, anchor, raw, 3, griefing);
        if (count(ItemTags.COALS) == 0) return fetch(level, memory, anchor, Items.COAL, 2, griefing);
        BlockPos furnace = furnaceNear(level, steve.blockPosition(), 24);
        if (furnace != null) return new Smelt(furnace, raw, ingot);
        if (count(Items.FURNACE) > 0) {
            BlockPos spot = groundNear(level, anchor.blockPosition(), 3, 6);
            if (spot != null) return new Place(spot, Items.FURNACE, Lines.FURNACE);
        }
        return prepare(level, memory, Items.FURNACE, anchor, griefing, depth + 1);
    }

    /** Goes and gets something from the world, with the right tool (he makes the tool first if he has to). */
    private @Nullable Job fetch(ServerLevel level, SteveMemory memory, ServerPlayer anchor, Item item, int need, boolean griefing) {
        if (!griefing) return null;
        BlockMatch match = sourceOf(item);
        if (match == null) return null;
        Item pick = pickaxeFor(item);
        if (pick != null && !hasPickaxe(pick)) {
            return knows(memory, pick) ? prepare(level, memory, pick, anchor, griefing, 1) : null;
        }
        return gather(level, anchor, match, Math.max(1, Math.min(need, 16)), Lines.ON_IT);
    }

    /** Where in the world an item comes from, if he can get it himself. */
    static @Nullable BlockMatch sourceOf(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL) return (l, p, st) -> st.is(COAL) && exposed(l, p);
        if (item == Items.RAW_IRON || item == Items.IRON_INGOT) return (l, p, st) -> st.is(IRON) && exposed(l, p);
        if (item == Items.RAW_COPPER || item == Items.COPPER_INGOT) return (l, p, st) -> st.is(COPPER) && exposed(l, p);
        if (item == Items.RAW_GOLD || item == Items.GOLD_INGOT) return (l, p, st) -> st.is(GOLD) && exposed(l, p);
        if (item == Items.DIAMOND) return (l, p, st) -> st.is(DIAMOND) && exposed(l, p);
        if (item == Items.EMERALD) return (l, p, st) -> st.is(EMERALD) && exposed(l, p);
        if (item == Items.LAPIS_LAZULI) return (l, p, st) -> st.is(LAPIS) && exposed(l, p);
        if (item == Items.REDSTONE) return (l, p, st) -> st.is(REDSTONE) && exposed(l, p);
        if (item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE || item == Items.BLACKSTONE) {
            return (l, p, st) -> st.is(BlockTags.BASE_STONE_OVERWORLD) && exposed(l, p);
        }
        if (new ItemStack(item).is(ItemTags.LOGS)) return SteveWork::naturalLog;
        if (item == Items.STICK) return null;
        if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
            Block target = blockItem.getBlock();
            if (!natural(target.defaultBlockState())) return null;
            return (l, p, st) -> st.is(target) && exposed(l, p);
        }
        return null;
    }

    /** Blocks that grow or lie around in the world, which he can take without wrecking anyone's build. */
    private static boolean natural(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.BASE_STONE_NETHER)
                || st.is(BlockTags.FLOWERS) || st.is(BlockTags.ICE) || st.is(BlockTags.LEAVES) || st.is(BlockTags.SNOW)
                || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY) || st.is(Blocks.OBSIDIAN) || st.is(Blocks.PUMPKIN) || st.is(Blocks.MELON)
                || st.is(Blocks.SUGAR_CANE) || st.is(Blocks.CACTUS) || st.is(Blocks.BAMBOO) || st.is(Blocks.AMETHYST_CLUSTER)
                || st.is(Blocks.GLOWSTONE) || st.is(Blocks.SOUL_SAND) || st.is(Blocks.MUD) || st.is(Blocks.DRIPSTONE_BLOCK)
                || st.is(Blocks.POINTED_DRIPSTONE) || st.is(Blocks.CALCITE) || st.is(Blocks.TUFF) || st.is(Blocks.MOSS_BLOCK);
    }

    /** The weakest pickaxe that gets the item, or null if hands will do. */
    private static @Nullable Item pickaxeFor(Item item) {
        if (item == Items.COAL || item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE || item == Items.BLACKSTONE) return Items.WOODEN_PICKAXE;
        if (item == Items.RAW_IRON || item == Items.IRON_INGOT || item == Items.RAW_COPPER || item == Items.COPPER_INGOT || item == Items.LAPIS_LAZULI) {
            return Items.STONE_PICKAXE;
        }
        if (item == Items.RAW_GOLD || item == Items.GOLD_INGOT || item == Items.DIAMOND || item == Items.EMERALD || item == Items.REDSTONE) {
            return Items.IRON_PICKAXE;
        }
        if (item == Items.OBSIDIAN) return Items.DIAMOND_PICKAXE;
        return null;
    }

    private boolean hasPickaxe(Item atLeast) {
        List<Item> order = List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
        int need = order.indexOf(atLeast);
        for (int i = Math.max(0, need); i < order.size(); i++) if (count(order.get(i)) > 0) return true;
        // Golden pickaxes are wooden-tier.
        return need == 0 && count(Items.GOLDEN_PICKAXE) > 0;
    }

    /** Can it be made with a crafting recipe at all? */
    public static boolean craftable(ServerLevel level, Item item) {
        return !plans(level, item).isEmpty();
    }

    // ---- jobs --------------------------------------------------------------------------

    abstract class Job {
        int ticks;
        int limit = 600;
        @Nullable BlockPos target;

        abstract String name();

        /** One tick. Returns true when finished. */
        abstract boolean tick(ServerLevel level, SteveMemory memory);

        boolean walkTo(BlockPos pos, double reach, double speed) {
            // Like a player: what counts is how far it is from his eyes (he can reach up a tree trunk).
            Vec3 center = Vec3.atCenterOf(pos);
            if (steve.position().distanceToSqr(center) < reach * reach || steve.getEyePosition().distanceToSqr(center) < reach * reach) {
                steve.getNavigation().stop();
                return true;
            }
            if (steve.getNavigation().isDone() || ticks % 40 == 0) steve.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
            return false;
        }
    }

    interface BlockMatch {
        boolean test(ServerLevel level, BlockPos pos, BlockState state);
    }

    /** Breaks blocks like a player: walks up, swings, the crack spreads, it breaks, and the drops go in his pockets. */
    class Gather extends Job {
        private final BlockMatch match;
        private int left;
        private final ServerPlayer anchor;
        private int progress;
        private int needed;
        private final @Nullable String line;
        private boolean spoke;

        Gather(BlockPos first, BlockMatch match, int count, ServerPlayer anchor, @Nullable String line) {
            this.target = first;
            this.match = match;
            this.left = count;
            this.anchor = anchor;
            this.line = line;
            this.limit = 1600;
        }

        @Override
        String name() {
            return "gather";
        }

        @Override
        boolean tick(ServerLevel level, SteveMemory memory) {
            BlockPos pos = target;
            if (pos == null) return true;
            BlockState state = level.getBlockState(pos);
            if (!match.test(level, pos, state)) return next(level);
            if (!spoke && line != null) {
                spoke = true;
                SteveVoice.steve(steve, line);
            }
            if (!walkTo(pos, 4.2, 1.0)) {
                if (ticks % 200 == 199 && steve.getNavigation().isDone()) {
                    unreachable.add(pos.asLong());
                    return next(level);
                }
                return false;
            }
            ItemStack tool = bestTool(state);
            steve.setItemSlot(EquipmentSlot.MAINHAND, tool.isEmpty() ? ItemStack.EMPTY : tool.copyWithCount(1));
            steve.getLookControl().setLookAt(Vec3.atCenterOf(pos));
            if (needed == 0) needed = breakTicks(level, pos, state, tool);
            progress++;
            if (progress % 5 == 0) {
                steve.swingArm();
                level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.5f, 0.8f);
            }
            level.destroyBlockProgress(steve.getId(), pos, Math.min(9, progress * 10 / Math.max(1, needed)));
            if (progress < needed) return false;
            level.destroyBlockProgress(steve.getId(), pos, -1);
            // Like a player: the wrong tool breaks it, but nothing drops.
            boolean harvest = !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
            List<ItemStack> drops = harvest ? Block.getDrops(state, level, pos, level.getBlockEntity(pos), steve, tool) : List.of();
            level.destroyBlock(pos, false, steve);
            for (ItemStack drop : drops) give(drop);
            onBroke(level, memory, state);
            left--;
            if (left > 0 && steve.getRandom().nextInt(7) == 0) SteveVoice.steve(steve, steve.getRandom(), Lines.CHATTER);
            return left <= 0 || next(level);
        }

        private boolean next(ServerLevel level) {
            progress = 0;
            needed = 0;
            BlockPos more = find(level, anchor, match, 10);
            if (more == null) return true;
            target = more;
            return false;
        }
    }

    /** Crafts something, at a table if it needs one. */
    class Craft extends Job {
        private final Item item;
        private final ServerPlayer anchor;
        private int swings;

        Craft(Item item, ServerPlayer anchor) {
            this.item = item;
            this.anchor = anchor;
            this.limit = 500;
        }

        @Override
        String name() {
            return "craft " + BuiltInRegistries.ITEM.getKey(item).getPath();
        }

        @Override
        boolean tick(ServerLevel level, SteveMemory memory) {
            Plan plan = plan(level, item);
            if (plan == null) return true;
            if (plan.needsTable()) {
                BlockPos table = tableNear(level, steve.blockPosition(), 24);
                if (table == null) return true;
                target = table;
                if (!walkTo(table, 3.0, 0.9)) return false;
                steve.getLookControl().setLookAt(Vec3.atCenterOf(table));
            }
            if (ticks % 6 == 0) steve.swingArm();
            if (++swings < 24) return false;
            ItemStack made = craft(plan);
            if (made.isEmpty()) return true;
            level.playSound(null, steve.blockPosition(), net.minecraft.sounds.SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 0.5f, 1.3f);
            onCrafted(level, memory, made, anchor);
            return true;
        }
    }

    /** Puts a block down: his crafting table, a furnace. */
    class Place extends Job {
        private final Item item;
        private final String line;

        Place(BlockPos at, Item item, String line) {
            this.target = at;
            this.item = item;
            this.line = line;
        }

        @Override
        String name() {
            return "place " + BuiltInRegistries.ITEM.getKey(item).getPath();
        }

        @Override
        boolean tick(ServerLevel level, SteveMemory memory) {
            BlockPos at = target;
            if (at == null || count(item) == 0) return true;
            if (!walkTo(at, 3.5, 0.9)) return false;
            if (!level.getBlockState(at).canBeReplaced()) return true;
            Block block = ((net.minecraft.world.item.BlockItem) item).getBlock();
            BlockState state = block.defaultBlockState();
            if (state.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
                Direction facing = Direction.getApproximateNearest(steve.getX() - at.getX(), 0, steve.getZ() - at.getZ());
                if (facing.getAxis().isHorizontal()) state = state.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing);
            }
            level.setBlock(at, state, 3);
            level.playSound(null, at, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 0.9f);
            steve.swingArm();
            take(s -> s.is(item), 1);
            SteveVoice.steve(steve, line);
            if (item == Items.CRAFTING_TABLE && memory.setFlag("steve_table")) Journal.unlock(level.getServer(), "mem_wood");
            return true;
        }
    }

    /** Lights a dark spot near you. */
    class Light extends Job {
        Light(BlockPos at) {
            this.target = at;
            this.limit = 300;
        }

        @Override
        String name() {
            return "torch";
        }

        @Override
        boolean tick(ServerLevel level, SteveMemory memory) {
            BlockPos at = target;
            if (at == null || count(Items.TORCH) == 0) return true;
            if (!walkTo(at, 3.5, 1.0)) return false;
            if (!level.getBlockState(at).isAir() || !Blocks.TORCH.defaultBlockState().canSurvive(level, at)) return true;
            steve.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TORCH));
            level.setBlock(at, Blocks.TORCH.defaultBlockState(), 3);
            level.playSound(null, at, Blocks.TORCH.defaultBlockState().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 1f);
            steve.swingArm();
            take(s -> s.is(Items.TORCH), 1);
            lastTorch = steve.tickCount;
            if (lightsLeft > 0) lightsLeft--;
            memory.addSanity(0.5f);
            if (steve.getRandom().nextInt(3) == 0) SteveVoice.steve(steve, steve.getRandom(), Lines.TORCH);
            return true;
        }
    }

    /** Smelts raw iron at a furnace (anyone's furnace: he only borrows the fire). */
    class Smelt extends Job {
        private int burning;
        private final Item raw;
        private final Item ingot;

        Smelt(BlockPos furnace, Item raw, Item ingot) {
            this.target = furnace;
            this.raw = raw;
            this.ingot = ingot;
            this.limit = 900;
        }

        @Override
        String name() {
            return "smelt";
        }

        @Override
        boolean tick(ServerLevel level, SteveMemory memory) {
            BlockPos at = target;
            if (at == null) return true;
            if (!walkTo(at, 3.0, 0.9)) return false;
            steve.getLookControl().setLookAt(Vec3.atCenterOf(at));
            if (burning == 0) {
                if (count(raw) == 0 || count(ItemTags.COALS) == 0) return true;
                take(s -> s.is(ItemTags.COALS), 1);
                steve.swingArm();
                level.playSound(null, at, net.minecraft.sounds.SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 1f, 1f);
            }
            burning++;
            if (burning % 40 == 0) level.playSound(null, at, net.minecraft.sounds.SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.8f, 1f);
            if (burning < 160) return false;
            int n = Math.min(8, count(raw));
            take(s -> s.is(raw), n);
            give(new ItemStack(ingot, n));
            SteveVoice.steve(steve, ingot == Items.IRON_INGOT ? Lines.IRON_DONE : Lines.SMELTED);
            return true;
        }
    }

    /** Brings things to a player and tosses them over. */
    class Hand extends Job {
        private final ServerPlayer to;
        private final Predicate<ItemStack> what;
        private final int amount;
        private final String[] lines;

        Hand(ServerPlayer to, Predicate<ItemStack> what, int amount, String[] lines) {
            this.to = to;
            this.what = what;
            this.amount = amount;
            this.lines = lines;
            this.limit = 900;
        }

        @Override
        String name() {
            return "hand";
        }

        @Override
        boolean tick(ServerLevel level, SteveMemory memory) {
            if (!to.isAlive() || to.level() != level) return true;
            if (steve.distanceToSqr(to) > 3.2 * 3.2) {
                if (steve.getNavigation().isDone() || ticks % 20 == 0) steve.getNavigation().moveTo(to, 1.1);
                return false;
            }
            steve.getNavigation().stop();
            steve.getLookControl().setLookAt(to, 30f, 30f);
            List<ItemStack> items = take(what, amount);
            if (items.isEmpty()) return true;
            Vec3 dir = to.position().subtract(steve.position()).normalize().scale(0.25);
            for (ItemStack stack : items) {
                ItemEntity drop = new ItemEntity(level, steve.getX(), steve.getEyeY() - 0.3, steve.getZ(), stack, dir.x, 0.2, dir.z);
                drop.setPickUpDelay(10);
                level.addFreshEntity(drop);
            }
            steve.swingArm();
            SteveVoice.steve(steve, steve.getRandom(), lines);
            memory.relationship(to.getUUID()).addTrust(1f);
            return true;
        }
    }

    // ---- reactions to his own work --------------------------------------------------------

    private void onBroke(ServerLevel level, SteveMemory memory, BlockState state) {
        if (state.is(BlockTags.LOGS) && memory.setFlag("imitated_wood")) {
            SteveVoice.steve(steve, "Like this? This is how you do it, right?");
        }
        if (state.is(DIAMOND) && memory.setFlag("steve_diamond")) {
            SteveVoice.steve(steve, "Oh. Oh, these are the blue ones. People used to fight over these.");
        }
    }

    private void onCrafted(ServerLevel level, SteveMemory memory, ItemStack made, ServerPlayer anchor) {
        Item item = made.getItem();
        Request r = request;
        if (r != null && r.item() == item) {
            // It's for you: the request hands it over next.
            thinkCooldown = 0;
            return;
        }
        String line = item == Items.CRAFTING_TABLE ? null
                : item == Items.WOODEN_PICKAXE ? Lines.WOOD_PICK
                : item == Items.STONE_PICKAXE ? Lines.STONE_PICK
                : item == Items.IRON_PICKAXE ? Lines.IRON_PICK
                : item == Items.TORCH ? Lines.TORCHES_MADE
                : item == Items.STONE_AXE ? Lines.STONE_AXE
                : item == Items.FURNACE ? Lines.FURNACE_MADE
                : item == Items.CHEST ? Lines.CHEST_MADE
                : made.is(ItemTags.SWORDS) ? Lines.SWORD_MADE
                : null;
        if (line != null) SteveVoice.steve(steve, line);
        // Hold the best thing he has.
        ItemStack best = bestHeld();
        if (!best.isEmpty()) steve.setItemSlot(EquipmentSlot.MAINHAND, best);
    }

    // ---- finding things ---------------------------------------------------------------

    /** The player he works for: whoever he trusts most nearby, or anyone close. */
    @Nullable ServerPlayer anchor(ServerLevel level, SteveMemory memory) {
        ServerPlayer best = null;
        double bestScore = -1;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) continue;
            double d = player.distanceToSqr(steve);
            if (d > 40 * 40) continue;
            if (memory.anger(player.getUUID()).atLeast(dev.leftbehind.memory.AngerStage.RESENTMENT)) continue;
            double score = memory.trust(player.getUUID()) - Math.sqrt(d);
            if (score > bestScore) {
                bestScore = score;
                best = player;
            }
        }
        return best;
    }

    private @Nullable Job gather(ServerLevel level, ServerPlayer anchor, BlockMatch match, int count, @Nullable String line) {
        BlockPos first = find(level, anchor, match, 14);
        return first == null ? null : new Gather(first, match, count, anchor, line);
    }

    /** The nearest matching block he could reach, near him and not too far from the player, away from anyone's home. */
    private @Nullable BlockPos find(ServerLevel level, ServerPlayer anchor, BlockMatch match, int radius) {
        BlockPos origin = steve.blockPosition();
        List<BlockPos> homes = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            BlockPos home = StoryDirector.homeOf(p, level);
            if (home != null) homes.add(home);
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -6, -radius), origin.offset(radius, 8, radius))) {
            double d = pos.distSqr(origin);
            if (d >= bestDist || unreachable.contains(pos.asLong())) continue;
            if (pos.distSqr(anchor.blockPosition()) > 30 * 30) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !match.test(level, pos, state)) continue;
            boolean nearHome = false;
            for (BlockPos home : homes) if (home.distSqr(pos) < 10 * 10) nearHome = true;
            if (nearHome) continue;
            best = pos.immutable();
            bestDist = d;
        }
        return best;
    }

    /** A log that is part of a tree (leaves close by), not part of somebody's house. */
    static boolean naturalLog(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.is(BlockTags.LOGS) || state.is(BlockTags.LOGS) && state.getBlock().getDescriptionId().contains("stripped")) return false;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 4, 2))) {
            if (level.getBlockState(p).is(BlockTags.LEAVES)) return true;
        }
        return false;
    }

    static boolean exposed(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (level.getBlockState(pos.relative(d)).isAir()) return true;
        }
        return false;
    }

    private @Nullable BlockPos darkSpotNear(ServerLevel level, ServerPlayer anchor, boolean asked) {
        BlockPos c = anchor.blockPosition();
        for (int attempt = 0; attempt < 12; attempt++) {
            BlockPos pos = c.offset(steve.getRandom().nextInt(13) - 6, steve.getRandom().nextInt(3) - 1, steve.getRandom().nextInt(13) - 6);
            if (pos.distSqr(c) < 3 * 3) continue;
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                    && level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos) < 5
                    && (asked || level.isDarkOutside() || !level.canSeeSky(pos))) {
                return pos;
            }
        }
        return null;
    }

    static @Nullable BlockPos tableNear(ServerLevel level, BlockPos center, int r) {
        return blockNear(level, center, r, st -> st.is(Blocks.CRAFTING_TABLE));
    }

    static @Nullable BlockPos furnaceNear(ServerLevel level, BlockPos center, int r) {
        return blockNear(level, center, r, st -> st.is(Blocks.FURNACE) || st.is(Blocks.BLAST_FURNACE));
    }

    private static @Nullable BlockPos blockNear(ServerLevel level, BlockPos center, int r, Predicate<BlockState> test) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -4, -r), center.offset(r, 4, r))) {
            if (test.test(level.getBlockState(pos))) return pos.immutable();
        }
        return null;
    }

    private @Nullable BlockPos groundNear(ServerLevel level, BlockPos center, int min, int max) {
        for (int attempt = 0; attempt < 20; attempt++) {
            int dx = steve.getRandom().nextInt(max * 2 + 1) - max;
            int dz = steve.getRandom().nextInt(max * 2 + 1) - max;
            if (dx * dx + dz * dz < min * min) continue;
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos pos = center.offset(dx, dy, dz);
                if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                        && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private Item plankFor() {
        for (int i = 0; i < stash.getContainerSize(); i++) {
            ItemStack s = stash.getItem(i);
            if (!s.is(ItemTags.LOGS)) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(s.getItem());
            String wood = id.getPath().replace("stripped_", "").replace("_log", "").replace("_wood", "").replace("_stem", "").replace("_hyphae", "");
            Item planks = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(), wood + "_planks"));
            if (planks != Items.AIR) return planks;
        }
        return Items.OAK_PLANKS;
    }

    private String pick(String[] lines) {
        return lines[steve.getRandom().nextInt(lines.length)];
    }

    // ---- tools ------------------------------------------------------------------------

    private ItemStack bestTool(BlockState state) {
        ItemStack best = ItemStack.EMPTY;
        float bestSpeed = 1f;
        for (int i = 0; i < stash.getContainerSize(); i++) {
            ItemStack s = stash.getItem(i);
            if (s.isEmpty()) continue;
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = s;
            }
        }
        return best;
    }

    private boolean canMine(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        for (int i = 0; i < stash.getContainerSize(); i++) {
            if (stash.getItem(i).isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    private static int breakTicks(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return Integer.MAX_VALUE;
        float speed = tool.isEmpty() ? 1f : Math.max(1f, tool.getDestroySpeed(state));
        boolean right = !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
        return Math.max(6, (int) Math.ceil(hardness * (right ? 30f : 100f) / speed));
    }

    /** What to hold when he's not using anything: his best pickaxe, else his axe. */
    ItemStack bestHeld() {
        for (Item item : new Item[]{Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.STONE_AXE, Items.WOODEN_PICKAXE, Items.WOODEN_AXE}) {
            if (count(item) > 0) return new ItemStack(item);
        }
        return ItemStack.EMPTY;
    }

    /** What he says while he works. All of these are recorded. */
    static final class Lines {
        static final String[] WOOD = {"I need wood. Everything starts with wood.", "Wood first. Always wood first.",
                "I'll get some wood. Trees don't mind. I asked one once.", "Let me get logs. We'll need lots."};
        static final String[] STONE = {"Stone. Stone tools are better. I remember that much.", "Time for stone. Wood breaks too fast.",
                "I'm getting cobblestone. Lots of it. You can never have enough."};
        static final String[] IRON = {"Iron. This is where it starts getting good.", "I see iron. The orange spots. Hold on.",
                "Iron ore. I'll dig it out."};
        static final String[] COAL = {"Coal. Torches. Then the dark gets smaller.", "Coal. Black spots in the stone. That's light, later.",
                "I'll take that coal. We'll need torches tonight."};
        static final String[] SHINY = {"Something's shining in there. Hold on.", "Wait. Something in the rock. Something good.",
                "Ooh. Hold on. That's not stone."};
        static final String[] CHATTER = {"Almost.", "This one's stubborn.", "There we go.", "One more.", "Nearly there.",
                "I like this part. It's quiet work.", "Like this. Swing, wait, swing."};
        static final String[] IDLE_OFFER = {"I've got my tools. Do you want me to get anything? Wood, stone, iron. Just say.",
                "I'm not doing anything. Tell me what you need. I'll find it.",
                "Do you need anything? I can make things now. Ask me."};
        static final String ON_IT = "Alright. Give me a minute.";
        static final String NO_COAL = "I can't make torches. No coal anywhere I can reach.";
        static final String BRIGHT_ENOUGH = "There. It's bright enough now. Bright enough for me.";
        static final String CANT_MAKE = "I can't make it here. Something's missing, and I can't find it.";
        static final String SMELTED = "There. It came out of the fire right.";
        static final String[] ALL_I_FOUND = {"This is all I could find. Here.", "It's not as many as you wanted. It's all there was."};
        static final String TABLE = "There. A table. Now I can make real things.";
        static final String FURNACE = "A furnace. I'll borrow its fire.";
        static final String WOOD_PICK = "A pickaxe. Wood, but it's a start.";
        static final String STONE_PICK = "Stone pickaxe. Better. Much better.";
        static final String STONE_AXE = "A stone axe. Trees, beware.";
        static final String FURNACE_MADE = "A furnace. Now we can cook. And smelt. Mostly smelt.";
        static final String CHEST_MADE = "A chest. Somewhere to keep things safe. Safer.";
        static final String SWORD_MADE = "A sword. I hope I don't need it. I hope you don't either.";
        static final String IRON_PICK = "Iron. Look at it. I made that.";
        static final String TORCHES_MADE = "Torches. Good. I don't like the dark.";
        static final String IRON_DONE = "Iron. It came out right. It doesn't always.";
        static final String[] TORCH = {"There. Better.", "Light. He doesn't like light.", "One more. You can never have one too many."};
        static final String[] YOU_LIKE = {"You said you like these. I kept them for you.", "Here. You like these. I remembered."};
        static final String[] HERE_YOU_GO = {"Here. That's what you asked for.", "Here you go. I counted them twice."};
        static final String[] MADE_IT = {"I made it. Here. Is it right?", "Here. I made it the way you did."};
        static final String[] FOUND_SOMETHING = {"I found these. I don't know what they're for. You'll know.",
                "Here. These were shining in the rock. You should have them.", "For you. I don't need shiny things. You like them."};

        private Lines() {}
    }
}
