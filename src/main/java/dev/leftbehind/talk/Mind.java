package dev.leftbehind.talk;

import dev.leftbehind.entity.SteveWork;
import dev.leftbehind.story.SteveVoice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The part of Steve that works out what a sentence means, instead of matching it against a list.
 *
 * <p>It reads the sentence for what kind of thing it is (a request, a question about what he has, what he can make,
 * where something is, how to make something, a yes or a no to his offer, something you like), and what it's about
 * (any item or block in the game, by its name, a plural, or a common word like "wood" or "iron", with a number if you
 * gave one). Then he does it, or answers from what he actually knows: his pockets, his recipes, the blocks around you.
 * When he can't follow, he asks about the part he didn't get instead of saying he doesn't understand.
 *
 * <p>No AI service: this is all game code. Lines with a recording are spoken; lines built from your words are typed.
 */
final class Mind {
    private Mind() {}

    // ---- what kind of sentence ------------------------------------------------------------

    private static final Pattern STOP = Pattern.compile(" (stop|never ?mind|forget it|forget about it|cancel|leave it|do not bother|no need) ");
    private static final Pattern STOP_WORK = Pattern.compile(" (stop|quit|do not keep|do not) (working|mining|chopping|digging|breaking|cutting|gathering|wrecking)");
    private static final Pattern HAVE = Pattern.compile(
            " (what do you have|what have you got|whats in your|what is in your|show me your|your inventory|your pockets|what are you carrying|what you got|what do you got) ");
    private static final Pattern DO_YOU_HAVE = Pattern.compile(" (do you have|have you got|got any|you have any|do you got) ");
    private static final Pattern CAN_MAKE = Pattern.compile(" (can you (make|craft|build)|do you know how to (make|craft)|could you (make|craft)|are you able to (make|craft)) ");
    private static final Pattern HOW_MAKE = Pattern.compile(" (how (do|can|should|would) (i|you|we|one) (make|craft|get)|how is .* made|how to (make|craft)|recipe for|what is the recipe|what do i need for|what do you need for|what does it take to make) ");
    private static final Pattern WHERE = Pattern.compile(" (where (is|are|can i find|do i find|to find|do you find|can we find|could i find|would i find)|have you seen (any|some)?|seen any|is there any|are there any) ");
    private static final Pattern MAKE = Pattern.compile(" (make|craft|build|smelt|cook|forge) (me |us |a |an |some |more |another )*");
    private static final Pattern GET = Pattern.compile(
            " (get|bring|fetch|collect|gather|find|grab|mine|chop|dig|cut|give|hand|pass) (me |us |up |out |a |an |some |more |the |any |your )*");
    private static final Pattern NEED = Pattern.compile(" (i need|i want|i would like|can i have|could i have|can i get|could i get|i could use|we need|we want) ");
    private static final Pattern DOING = Pattern.compile(" (what are you doing|what you doing|whatcha doing|what are you up to|what is your plan|what is the plan|what are you making|what are you working on) ");
    private static final Pattern LIKE = Pattern.compile(" i (really )?(like|love|enjoy|adore) ([a-z ]+?) ");
    private static final Pattern HATE = Pattern.compile(" i (really )?(hate|do not like|can not stand) ([a-z ]+?) ");
    private static final Pattern HELP_WITH = Pattern.compile(" help (me )?(with )?(mine|mining|chop|chopping|gather|gathering|dig|digging|wood|stone|ore|ores) ");
    private static final Pattern LIGHT = Pattern.compile(" (light (it |this |the place |the area )?up|place (some )?torches|put (some )?torches|it is (too |so |really )?dark|make it (bright|lighter)) ");
    private static final Pattern NUMBER = Pattern.compile(" (\\d{1,3}) ");
    private static final Pattern TOLD = Pattern.compile(" (i|we) (just )?(found|got|have|made|crafted|mined|picked up|dug up|smelted) ");

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
            Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("twelve", 12), Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("twenty", 20),
            Map.entry("few", 3), Map.entry("couple", 2), Map.entry("lot", 16), Map.entry("lots", 16), Map.entry("stack", 64),
            Map.entry("dozen", 12), Map.entry("bunch", 8));

    /** Handles the sentence if it's one of the kinds above. Returns false to let the rest of the talk system try. */
    static boolean understand(Talk.Ctx c, String text, String raw) {
        SteveWork work = c.steve.work();
        ServerLevel level = c.level;

        // A yes or no to something he offered.
        SteveWork.Offer offer = work.offer(c.player.getUUID(), c.now);
        if (offer != null) {
            int yes = Words.yesNo(text);
            if (yes == 1 || text.contains(" please ") || text.contains(" go ahead ") || text.contains(" do it ")) {
                work.clearOffer();
                work.ask(new SteveWork.Request(c.player.getUUID(), offer.item(), offer.count(), offer.craft()));
                speak(c, "Alright. Give me a minute.");
                return true;
            }
            if (yes == 0) {
                work.clearOffer();
                speak(c, pick(c, "Alright. Another time.", "Okay. I'll keep them, then."));
                return true;
            }
        }
        if (STOP_WORK.matcher(text).find()) {
            work.pause(c.now, 6000);
            speak(c, "Alright. I'll stop. I'll just stand here, then.");
            return true;
        }
        if (STOP.matcher(text).find() && (work.request() != null || work.busy()) && !text.contains(" follow") && Words.count(text) <= 6) {
            work.cancel();
            speak(c, "Okay. Forget it.");
            return true;
        }
        if (DOING.matcher(text).find() && doing(c, work)) return true;
        if (HAVE.matcher(text).find()) {
            if (work.isEmpty()) speak(c, "Nothing. Empty pockets. I'll find something.");
            else speak(c, "I have " + work.describe() + ".", "Let me look. Here's what I've got.");
            return true;
        }
        Thing thing = thing(text);
        int count = number(text);

        if (thing != null && DO_YOU_HAVE.matcher(text).find()) {
            int have = work.countFor(thing.item);
            if (have > 0) {
                speak(c, cap(thing.name) + "? I have " + have + ". Do you want them?", "Yes. I have some. Do you want them?");
                work.offer(c.player.getUUID(), thing.item, have, false, c.now);
            } else {
                speak(c, "No " + thing.name + ". Do you want me to find some?", "No. Do you want me to find some?");
                work.offer(c.player.getUUID(), thing.item, Math.max(count, 4), false, c.now);
            }
            return true;
        }
        if (thing != null && HOW_MAKE.matcher(text).find()) {
            howToMake(c, thing);
            return true;
        }
        if (thing != null && CAN_MAKE.matcher(text).find()) {
            canMake(c, work, thing, count);
            return true;
        }
        if (thing != null && WHERE.matcher(text).find()) {
            where(c, thing);
            return true;
        }
        if (LIGHT.matcher(text).find()) {
            work.ask(new SteveWork.Request(c.player.getUUID(), Items.TORCH, 0, false));
            speak(c, work.countFor(Items.TORCH) > 0 ? "I have torches. Hold on." : "I'll make some torches. I need coal.");
            return true;
        }
        Matcher help = HELP_WITH.matcher(text);
        if (help.find()) {
            String what = help.group(3);
            Item item = what.startsWith("chop") || what.equals("wood") ? Items.OAK_LOG : what.startsWith("ore") ? Items.COAL : Items.COBBLESTONE;
            work.ask(new SteveWork.Request(c.player.getUUID(), item, 8, false));
            speak(c, "Together, then. Show me where.");
            return true;
        }
        boolean make = MAKE.matcher(text).find();
        boolean get = GET.matcher(text).find() || NEED.matcher(text).find();
        if (thing != null && (make || get)) {
            request(c, work, thing, count, make);
            return true;
        }
        // "I like diamonds." He keeps it, and brings you some when he finds them.
        Matcher hate = HATE.matcher(text);
        Thing hated = hate.find() ? thing(" " + hate.group(3) + " ") : null;
        if (hated != null) {
            c.rel.notes.put("hates_item", BuiltInRegistries.ITEM.getKey(hated.item).toString());
            c.memory.setDirty();
            speak(c, "Noted. No " + hated.name + " for you, then.", "Noted. I won't bring you that.");
            return true;
        }
        Matcher like = LIKE.matcher(text);
        Thing liked = like.find() ? thing(" " + like.group(3) + " ") : null;
        if (liked != null) {
            c.rel.notes.put("likes_item", BuiltInRegistries.ITEM.getKey(liked.item).toString());
            c.memory.setDirty();
            speak(c, cap(liked.name) + ". I'll remember. If I find any, it's yours.", "I'll remember. If I find any, it's yours.");
            return true;
        }
        return false;
    }

    /**
     * The last try before he gives up: ask about the part he did get.
     * Returns false if there was nothing to hold on to.
     */
    static boolean clarify(Talk.Ctx c, String text) {
        Thing thing = thing(text);
        Matcher told = TOLD.matcher(text);
        if (thing != null && told.find()) {
            // "I found diamonds." Not a request: he reacts to it.
            boolean rare = thing.item == Items.DIAMOND || thing.item == Items.EMERALD || thing.item == Items.GOLD_INGOT
                    || thing.item == Items.LAPIS_LAZULI || thing.item == Items.REDSTONE;
            if (rare) speak(c, cap(thing.name) + "? Keep them somewhere safe. Somewhere he doesn't go.", "Keep them somewhere safe. Somewhere he doesn't go.");
            else speak(c, cap(thing.name) + ". Good. We'll need that.", "Good. We'll need that.");
            return true;
        }
        if (thing != null && (Words.startsLikeQuestion(text) || GET.matcher(text).find() || NEED.matcher(text).find() || MAKE.matcher(text).find()
                || Words.count(text) <= 3)) {
            speak(c, cap(thing.name) + "? Do you want me to get you some, or make one?", "Do you want me to get you some, or make one?");
            c.steve.work().offer(c.player.getUUID(), thing.item, 4, false, c.now);
            return true;
        }
        if (GET.matcher(text).find() || NEED.matcher(text).find()) {
            speak(c, "Get what? Wood? Stone? Tell me what you need.");
            return true;
        }
        if (MAKE.matcher(text).find()) {
            speak(c, "Make what? Say what it's called. If I've seen it made, I can do it.");
            return true;
        }
        return false;
    }

    // ---- answers ------------------------------------------------------------------------

    /** "What are you doing?" from what he is really doing. False when he's idle, so his usual answers can have it. */
    private static boolean doing(Talk.Ctx c, SteveWork work) {
        String job = work.jobName();
        SteveWork.Request r = work.request();
        if (r != null) {
            speak(c, "Getting you " + nameOf(r.item()) + ". I haven't forgotten.", "I haven't forgotten what you asked.");
        } else if (job == null) {
            if (!c.player.getUUID().equals(c.steve.followingId())) return false;
            speak(c, "Following you. You walk fast, you know.");
        } else if (job.startsWith("gather")) {
            speak(c, "Getting what we need. Wood, stone. Whatever's next.");
        } else if (job.startsWith("craft")) {
            speak(c, "Making " + nameOf(job.substring(6)) + ".", "Making something. Watch, if you want.");
        } else if (job.startsWith("place")) {
            speak(c, "Putting it down where we can use it.");
        } else if (job.equals("torch")) {
            speak(c, "Lights. He doesn't like light.");
        } else if (job.equals("smelt")) {
            speak(c, "Smelting iron. It takes a while. Everything good takes a while.");
        } else if (job.equals("hand")) {
            speak(c, "Bringing you something.");
        } else {
            speak(c, "Working. Someone has to.");
        }
        return true;
    }

    private static void request(Talk.Ctx c, SteveWork work, Thing thing, int count, boolean make) {
        Item item = thing.item;
        int n = count > 0 ? count : make ? 1 : (isTool(item) ? 1 : 8);
        if (work.countFor(item) >= n) {
            work.ask(new SteveWork.Request(c.player.getUUID(), item, n, false));
            speak(c, "I have some. Here.");
            return;
        }
        if (!work.canGather(item) && !SteveWork.craftable(c.level, item)) {
            speak(c, "I don't know where " + thing.name + " comes from. If I see some, I'll bring it.", "I don't know where to get that. If I see some, I'll bring it.");
            return;
        }
        if (make || !work.canGather(item)) {
            if (!SteveWork.knows(c.memory, item)) {
                speak(c, "I've never made " + thing.name + ". Make one where I can see, and I'll learn.",
                        "I've never made that. Make one where I can see, and I'll learn.");
                return;
            }
            work.ask(new SteveWork.Request(c.player.getUUID(), item, n, true));
            String missing = work.missingFor(c.level, item);
            if (missing == null) speak(c, "Alright. Give me a minute.");
            else speak(c, "I know how. I need " + missing + " first. I'll get it.", "I know how. I just need a few things first.");
            return;
        }
        work.ask(new SteveWork.Request(c.player.getUUID(), item, n, false));
        speak(c, cap(thing.name) + ". Alright. Give me a minute.", "Alright. Give me a minute.");
    }

    private static void canMake(Talk.Ctx c, SteveWork work, Thing thing, int count) {
        if (!SteveWork.knows(c.memory, thing.item)) {
            speak(c, "Not yet. Make " + thing.name + " where I can see you do it. I learn by watching.",
                    "Not yet. Make one where I can see you do it. I learn by watching.");
            return;
        }
        String missing = work.missingFor(c.level, thing.item);
        if (missing == null) {
            speak(c, "Yes. I have everything for it. Do you want one?", "Yes. I have everything for it. Do you want one?");
        } else {
            speak(c, "Yes, I know how. I'd need " + missing + ". Do you want me to?", "Yes, I know how. Do you want me to?");
        }
        work.offer(c.player.getUUID(), thing.item, Math.max(1, count), true, c.now);
    }

    /** How you get the things you don't craft. All recorded. */
    private static final Map<Item, String> HOW_TO_GET = Map.ofEntries(
            Map.entry(Items.OAK_LOG, "Wood comes from trees. Punch the trunk, or use an axe. It's faster with an axe."),
            Map.entry(Items.COBBLESTONE, "Stone. Dig it with a pickaxe, even a wooden one. With your hands you get nothing."),
            Map.entry(Items.COAL, "Coal is in the stone, black spots. Any pickaxe will do."),
            Map.entry(Items.IRON_INGOT, "Iron ore is stone with orange spots. A stone pickaxe, then smelt the raw iron in a furnace."),
            Map.entry(Items.RAW_IRON, "Iron ore is stone with orange spots. A stone pickaxe, then smelt the raw iron in a furnace."),
            Map.entry(Items.GOLD_INGOT, "Gold is deeper down. You need an iron pickaxe, then a furnace."),
            Map.entry(Items.COPPER_INGOT, "Copper is everywhere in the stone, green and orange. Stone pickaxe, then the furnace."),
            Map.entry(Items.DIAMOND, "Diamonds are deep, near the bottom. Only an iron pickaxe gets them out. Watch for lava."),
            Map.entry(Items.EMERALD, "Emeralds hide in the mountains. Or the villagers trade them. I never trusted that."),
            Map.entry(Items.LAPIS_LAZULI, "Lapis is blue, in the stone. A stone pickaxe is enough."),
            Map.entry(Items.REDSTONE, "Redstone is deep, red dust in the rock. You need iron to get it."),
            Map.entry(Items.OBSIDIAN, "Water on lava. Then a diamond pickaxe, and a lot of patience."),
            Map.entry(Items.STRING, "Spiders drop it. Or cobwebs, if you cut them."),
            Map.entry(Items.LEATHER, "Cows. I don't like that part."),
            Map.entry(Items.FLINT, "Dig gravel. Sometimes it gives you flint instead."));

    private static void howToMake(Talk.Ctx c, Thing thing) {
        String how = HOW_TO_GET.get(thing.item);
        if (how != null) {
            speak(c, how);
            return;
        }
        String recipe = SteveWork.describeRecipe(c.level, thing.item);
        if (recipe == null) {
            speak(c, "I don't think you make " + thing.name + ". I think you find it.", "I don't think you make that. I think you find it.");
        } else {
            speak(c, cap(thing.name) + ": " + recipe + ".", null);
        }
    }

    /** "Where is iron?" He looks around you for real. */
    private static void where(Talk.Ctx c, Thing thing) {
        BlockMatch match = matcherFor(thing.item);
        if (match == null) {
            speak(c, "I don't know where " + thing.name + " comes from. Not around here.", "I don't know. Not around here.");
            return;
        }
        BlockPos center = c.player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-24, -16, -24), center.offset(24, 12, 24))) {
            double d = pos.distSqr(center);
            if (d >= bestD) continue;
            BlockState state = c.level.getBlockState(pos);
            if (!match.test(state)) continue;
            best = pos.immutable();
            bestD = d;
        }
        if (best == null) {
            speak(c, "None near here. Deeper, maybe. Or further out.", "None near here. Deeper, maybe. Or further out.");
            return;
        }
        int dx = best.getX() - center.getX();
        int dy = best.getY() - center.getY();
        int dz = best.getZ() - center.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        String way = direction(dx, dz);
        String depth = dy < -4 ? ", down below us" : dy > 4 ? ", up above" : "";
        c.steve.getLookControl().setLookAt(best.getX() + 0.5, best.getY() + 0.5, best.getZ() + 0.5);
        speak(c, dist < 3 ? "Right here. Under your feet, nearly" + depth + "." : "There's " + thing.name + " about " + dist + " blocks " + way + depth + ".",
                null);
    }

    private static String direction(int dx, int dz) {
        String ns = dz < -Math.abs(dx) / 2 ? "north" : dz > Math.abs(dx) / 2 ? "south" : "";
        String ew = dx > Math.abs(dz) / 2 ? "east" : dx < -Math.abs(dz) / 2 ? "west" : "";
        if (!ns.isEmpty() && !ew.isEmpty()) return ns + "-" + ew;
        return ns.isEmpty() ? ew : ns;
    }

    // ---- the things in a sentence ---------------------------------------------------------

    /** An item someone talked about, and the name they used for it. */
    record Thing(Item item, String name) {}

    private static @Nullable Map<String, Item> lexicon;

    /** Plain words people use for things, before the game's own names. */
    private static final Map<String, Item> ALIASES = new LinkedHashMap<>();

    static {
        for (String w : new String[]{"wood", "log", "logs", "tree", "trees", "timber", "lumber"}) ALIASES.put(w, Items.OAK_LOG);
        for (String w : new String[]{"plank", "board", "planks", "boards"}) ALIASES.put(w, Items.OAK_PLANKS);
        for (String w : new String[]{"stone", "cobble", "cobblestone", "rock", "rocks", "stones"}) ALIASES.put(w, Items.COBBLESTONE);
        for (String w : new String[]{"iron", "iron ingot", "raw iron", "iron ore"}) ALIASES.put(w, Items.IRON_INGOT);
        for (String w : new String[]{"gold", "gold ingot", "raw gold", "gold ore"}) ALIASES.put(w, Items.GOLD_INGOT);
        for (String w : new String[]{"copper", "copper ingot", "raw copper", "copper ore"}) ALIASES.put(w, Items.COPPER_INGOT);
        for (String w : new String[]{"diamond", "diamonds", "diamond ore", "dia", "dias"}) ALIASES.put(w, Items.DIAMOND);
        for (String w : new String[]{"coal", "coal ore", "fuel"}) ALIASES.put(w, Items.COAL);
        for (String w : new String[]{"lapis", "lapis lazuli"}) ALIASES.put(w, Items.LAPIS_LAZULI);
        for (String w : new String[]{"redstone", "red stone", "redstone dust"}) ALIASES.put(w, Items.REDSTONE);
        for (String w : new String[]{"emerald", "emeralds"}) ALIASES.put(w, Items.EMERALD);
        for (String w : new String[]{"torch", "torches", "light", "lights"}) ALIASES.put(w, Items.TORCH);
        for (String w : new String[]{"table", "crafting table", "workbench", "craft table", "crafting bench"}) ALIASES.put(w, Items.CRAFTING_TABLE);
        for (String w : new String[]{"pickaxe", "pick", "pickax"}) ALIASES.put(w, Items.STONE_PICKAXE);
        ALIASES.put("axe", Items.STONE_AXE);
        ALIASES.put("sword", Items.STONE_SWORD);
        ALIASES.put("stick", Items.STICK);
        ALIASES.put("chest", Items.CHEST);
        ALIASES.put("furnace", Items.FURNACE);
    }

    private static final Set<String> NOT_THINGS = Set.of("air", "light", "barrier", "structure void", "jigsaw", "debug stick", "me", "you",
            "it", "him", "her", "them", "some", "more", "any", "a", "an", "the", "one", "please", "steve", "herobrine", "time", "name",
            "map", "book", "spawn", "bed", "home", "fire", "string", "egg", "bowl", "music", "clock");

    private static Map<String, Item> lexicon() {
        if (lexicon != null) return lexicon;
        Map<String, Item> map = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            String words = id.getPath().replace('_', ' ');
            if (NOT_THINGS.contains(words) || id.getPath().contains("spawn_egg") || id.getPath().contains("command")) continue;
            map.putIfAbsent(words, item);
        }
        map.putAll(ALIASES);
        lexicon = map;
        return map;
    }

    /** Finds the longest run of words that names an item, plurals and all. */
    static @Nullable Thing thing(String text) {
        List<String> tokens = new ArrayList<>();
        for (String t : Words.tokens(text)) tokens.add(t);
        Map<String, Item> lex = lexicon();
        for (int len = 3; len >= 1; len--) {
            for (int i = 0; i + len <= tokens.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < len; k++) {
                    if (k > 0) sb.append(' ');
                    String w = tokens.get(i + k);
                    sb.append(k == len - 1 ? Words.singular(w) : w);
                }
                String phrase = sb.toString();
                if (NOT_THINGS.contains(phrase)) continue;
                Item item = lex.get(phrase);
                if (item == null) {
                    // "planks" -> "plank" is handled; try the raw last word too ("glass" stays "glass").
                    String rawPhrase = String.join(" ", tokens.subList(i, i + len));
                    item = lex.get(rawPhrase);
                    if (item != null) phrase = rawPhrase;
                }
                if (item != null && item != Items.AIR) return new Thing(item, phrase);
            }
        }
        return null;
    }

    static int number(String text) {
        Matcher m = NUMBER.matcher(text);
        if (m.find()) return Math.min(256, Integer.parseInt(m.group(1)));
        for (String t : Words.tokens(text)) {
            Integer n = NUMBER_WORDS.get(t);
            if (n != null) return n;
        }
        return 0;
    }

    private static boolean isTool(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        return path.endsWith("pickaxe") || path.endsWith("_axe") || path.endsWith("sword") || path.endsWith("shovel") || path.endsWith("hoe")
                || item == Items.CRAFTING_TABLE || item == Items.FURNACE || item == Items.CHEST || path.endsWith("bucket");
    }

    interface BlockMatch {
        boolean test(BlockState state);
    }

    private static @Nullable BlockMatch matcherFor(Item item) {
        TagKey<Block> tag = item == Items.COAL ? SteveWork.COAL
                : item == Items.IRON_INGOT || item == Items.RAW_IRON ? SteveWork.IRON
                : item == Items.COPPER_INGOT || item == Items.RAW_COPPER ? SteveWork.COPPER
                : item == Items.GOLD_INGOT || item == Items.RAW_GOLD ? SteveWork.GOLD
                : item == Items.DIAMOND ? SteveWork.DIAMOND
                : item == Items.EMERALD ? SteveWork.EMERALD
                : item == Items.LAPIS_LAZULI ? SteveWork.LAPIS
                : item == Items.REDSTONE ? SteveWork.REDSTONE
                : item == Items.OAK_LOG ? BlockTags.LOGS
                : null;
        if (tag != null) return state -> state.is(tag);
        if (item == Items.COBBLESTONE) return state -> state.is(BlockTags.BASE_STONE_OVERWORLD);
        if (item instanceof BlockItem block) return state -> state.is(block.getBlock());
        return null;
    }

    // ---- speaking -----------------------------------------------------------------------

    static String nameOf(Item item) {
        return new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
    }

    private static String nameOf(String path) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
        return item == Items.AIR ? path.replace('_', ' ') : nameOf(item);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String pick(Talk.Ctx c, String... lines) {
        return lines[c.random.nextInt(lines.length)];
    }

    /** A line with a recording, spoken as written. */
    private static void speak(Talk.Ctx c, String line) {
        SteveVoice.steve(c.steve, line, line);
    }

    /**
     * A line built from your words: typed as written, and if part of it was recorded ahead of time,
     * that part is spoken.
     */
    private static void speak(Talk.Ctx c, String text, @Nullable String voiced) {
        SteveVoice.steve(c.steve, text, voiced);
    }
}
