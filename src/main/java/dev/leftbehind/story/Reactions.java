package dev.leftbehind.story;

import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.entity.Remnant;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.AngerStage;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SanityStage;
import dev.leftbehind.memory.SteveMemory;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Steve's feedback on the world: what he says about the things you hand him, the things you say,
 * and the things he watches you do.
 */
public final class Reactions {
    private static final Map<UUID, Vec3> LAST_POSITION = new HashMap<>();
    private static final Map<UUID, Integer> STILL_SECONDS = new HashMap<>();

    private Reactions() {}

    // ---- remarks --------------------------------------------------------------------

    /**
     * Says one of the lines, unless he said something a moment ago or has used this remark recently.
     * Returns true if he spoke.
     */
    public static boolean remark(OriginalSteve steve, String key, int cooldownSeconds, String... lines) {
        SteveMemory memory = steve.memory();
        if (memory == null) return false;
        long time = steve.level().getGameTime();
        if (time < memory.counter("cd_remark_any")) return false;
        if (dev.leftbehind.talk.Talk.inConversation(time)) return false;
        if (!memory.cooldown("remark_" + key, time, cooldownSeconds * 20L)) return false;
        memory.setCounter("cd_remark_any", time + 70);
        SteveVoice.steve(steve, steve.getRandom(), lines);
        return true;
    }

    public static void idleThought(OriginalSteve steve, SteveMemory memory, ServerLevel level) {
        boolean near = false;
        for (ServerPlayer p : level.players()) if (p.distanceToSqr(steve) < 24 * 24) near = true;
        if (!near) return;
        int act = memory.act();
        SanityStage stage = memory.sanityStage();
        if (stage.atMost(SanityStage.FRAYING)) {
            remark(steve, "idle", 60, "Who said that? ...you didn't say that. Right.", "There are two of you. No. One. One of you.",
                    "I keep forgetting something important.", "Was that tree there yesterday?", "He was standing right there. A moment ago.");
        } else if (stage == SanityStage.UNEASY) {
            // Uneasy: he counts, and repeats himself, and wants the lights on.
            if (level.isDarkOutside() && level.getMaxLocalRawBrightness(steve.blockPosition()) < 8
                    && remark(steve, "lights", 300, "Can you put a torch down? Here. Right here.", "It's too dark here. He likes it dark.",
                            "Leave the torches on tonight. All of them.")) {
                return;
            }
            remark(steve, "idle", 60, "One, two, three, four. One, two, three... four. Still four.", "This place feels familiar.",
                    "Has this world always been here?", "I said that already, didn't I?", "I said that already, didn't I?");
        } else if (act >= 3) {
            remark(steve, "idle", 60, "He's not here right now. I'd know.", "I counted the chickens. One is missing. One is always missing.",
                    "If I stand very still, the day lasts longer.");
        } else if (act == 2) {
            remark(steve, "idle", 60, "I keep nearly remembering something.", "*hums something old*", "There used to be more people. I think.",
                    "Do you hear that? No. Never mind.");
        } else {
            remark(steve, "idle", 60, "*hums something old*", "Huh.", "I like it here.", "Nice clouds. They're all square. Have you noticed?");
        }
    }

    /** Once a second: things he notices about players who are close to him. */
    public static void watchPlayers(ServerLevel level, OriginalSteve steve, SteveMemory memory) {
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive() || player.distanceToSqr(steve) > 12 * 12 || !steve.hasLineOfSight(player)) continue;
            if (memory.anger(player.getUUID()).atLeast(AngerStage.RESENTMENT)) continue;
            UUID id = player.getUUID();
            Vec3 last = LAST_POSITION.put(id, player.position());
            int still = last != null && last.distanceToSqr(player.position()) < 0.01 ? STILL_SECONDS.getOrDefault(id, 0) + 1 : 0;
            STILL_SECONDS.put(id, still);
            // He learns how you move by watching you do it.
            if (player.isSwimming() || (player.isInWater() && player.isSprinting())) {
                learnMove(steve, memory, "swim", "So that's how you swim. Kick, and keep your head up.");
            } else if (player.onClimbable() && !player.onGround()) {
                learnMove(steve, memory, "climb", "Hands, feet. Hands, feet. I see how you do it.");
            }
            if (player.isOnFire()) {
                remark(steve, "fire", 20, "You're on fire! Water! WATER!", "Roll! No, wait. Water!");
            } else if (player.getHealth() < 7f && !player.isCreative()) {
                remark(steve, "hurt", 60, "You're hurt. Eat something. Please.", "You look bad. Sit down. Do you sit down?",
                        "Don't die. I mean, you'd come back. But don't.");
            } else if (still == 90) {
                remark(steve, "afk", 300, "Are you still in there?", "Hello? ...they do this sometimes. They just stop.", "I'll wait.");
            } else if (player.isShiftKeyDown() && still < 2 && level.getRandom().nextInt(40) == 0) {
                remark(steve, "sneak", 240, "Why are you walking like that? Are we hiding?", "Should I be quiet too?");
            } else if (player.isSprinting() && level.getRandom().nextInt(60) == 0) {
                remark(steve, "sprint", 300, "Where's the hurry?", "Is something behind you? ...is something behind ME?");
            } else {
                inTheMoment(level, steve, memory, player, still);
            }
        }
    }

    /** He saw you move in a way he didn't know. The first time, he says so. See SteveBody#skill. */
    public static void learnMove(OriginalSteve steve, SteveMemory memory, String move, String line) {
        if (memory.observe("saw_" + move) == 1) SteveVoice.steve(steve, line);
    }

    /** What is happening to you right now, that he can see: what's near you, where you are, what you're doing. */
    private static void inTheMoment(ServerLevel level, OriginalSteve steve, SteveMemory memory, ServerPlayer player, int still) {
        if (level.getRandom().nextInt(3) != 0) return;
        net.minecraft.world.entity.monster.Monster threat = null;
        double best = 14 * 14;
        for (net.minecraft.world.entity.monster.Monster m : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                player.getBoundingBox().inflate(14))) {
            double d = m.distanceToSqr(player);
            if (d < best && m.hasLineOfSight(player)) {
                best = d;
                threat = m;
            }
        }
        if (threat instanceof net.minecraft.world.entity.monster.Creeper) {
            remark(steve, "creeper", 40, "Green one! Behind you! Don't let it get close!", "That one hisses. Move. MOVE.",
                    "Not the green one. Hit it and step back. Hit and step back.");
            return;
        }
        if (threat instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) {
            remark(steve, "skeleton", 60, "Arrows! Get behind something!", "The bony one. Don't stand in the open.");
            return;
        }
        if (threat instanceof net.minecraft.world.entity.monster.zombie.Zombie) {
            remark(steve, "zombie", 60, "One of the slow ones. It won't stop, though. They never stop.", "Zombie. Hit it before it hits you.");
            return;
        }
        if (threat instanceof net.minecraft.world.entity.monster.spider.Spider) {
            remark(steve, "spider", 60, "Spider. They climb. Walls don't help.", "I hate the ones with too many legs.");
            return;
        }
        if (threat instanceof net.minecraft.world.entity.monster.Enderman) {
            remark(steve, "enderman", 120, "Don't look at the tall one. Same rule as him. Don't look.",
                    "The tall one. Look at your feet. Look at your feet.");
            return;
        }
        BlockPos at = player.blockPosition();
        boolean lavaNear = false;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-3, -2, -3), at.offset(3, 1, 3))) {
            if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) {
                lavaNear = true;
                break;
            }
        }
        if (lavaNear) {
            remark(steve, "lava", 90, "Lava. Careful. Everything you carry goes with you.", "Slowly. Slowly near the orange.");
        } else if (player.isInWater() && player.getAirSupply() < player.getMaxAirSupply() / 2) {
            remark(steve, "drowning", 20, "Up! Go up! You need air!", "Swim up! Now!");
        } else if (player.fallDistance > 6 && !player.isCreative()) {
            remark(steve, "falling", 20, "Watch out!", "No, no, no!");
        } else if (player.getFoodData().getFoodLevel() < 6 && !player.isCreative()) {
            remark(steve, "hungry", 300, "You're hungry. I can tell. Eat something.", "When did you last eat? Really eat?");
        } else if (player.getXRot() > 70f && player.getMainHandItem().is(net.minecraft.tags.ItemTags.PICKAXES) && still < 1
                && !level.canSeeSky(at.above()) && level.getRandom().nextInt(6) == 0) {
            remark(steve, "dig_down", 600, "Don't dig straight down. Everyone knows that. I learned it the hard way.",
                    "Straight down? Really? There could be anything under there.");
        } else if (level.isDarkOutside() && level.canSeeSky(at.above()) && player.getArmorValue() == 0 && !player.isCreative()
                && level.getRandom().nextInt(4) == 0) {
            remark(steve, "no_armor", 600, "No armour, at night, out here? You're braver than me.", "We should go in. It's not safe in the open.");
        } else if (player.getY() > level.getSeaLevel() + 50 && level.canSeeSky(at.above())) {
            remark(steve, "high", 600, "You can see everything from up here. Everything can see you, too.", "Don't stand at the edge.");
        } else if (level.isThundering()) {
            remark(steve, "thunder", 600, "I don't like thunder. Something always comes with it.", "Stay away from the tall trees.");
        } else if (player.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_PICKAXE)) {
            remark(steve, "diamond_pick", 1200, "A diamond pickaxe. You've come a long way since the first night.");
        } else if (player.getMainHandItem().is(net.minecraft.world.item.Items.BOW) && player.isUsingItem()) {
            remark(steve, "bow", 300, "Breathe out, then let go. That's what I was told.", "Careful where you point that.");
        } else if (memory.act() >= 2 && level.getMaxLocalRawBrightness(at) < 4 && !level.canSeeSky(at.above())) {
            remark(steve, "pitch_dark", 300, "I can't see you. Say something. Say anything.", "Light. Please. I need to see your face.");
        }
    }

    // ---- items ----------------------------------------------------------------------

    /** Reacts to an item he is shown. Returns false if he has nothing to say about it. */
    public static boolean item(OriginalSteve steve, ServerPlayer player, SteveMemory memory, Relationship r, ItemStack stack) {
        ServerLevel level = player.level();
        long time = level.getGameTime();
        if (r.stage().atLeast(AngerStage.PROVOCATION) && !stack.has(DataComponents.FOOD)) return false;

        // The morning after: give him an axe back and he takes it like nothing happened.
        if (stack.is(ItemTags.AXES) && steve.getMainHandItem().isEmpty()) {
            steve.setItemSlot(EquipmentSlot.MAINHAND, stack.split(1));
            r.addTrust(4f);
            SteveVoice.steve(steve, "Where did mine go? I had it yesterday.");
            return true;
        }
        if (Remnant.isRose(stack) || (stack.is(Items.POPPY) && memory.flag("seen_0"))) {
            if (!memory.journal.contains("mem_rose")) {
                stack.shrink(1);
                SteveVoice.steve(steve, "A rose. They were called roses. Rana put them on everything she wanted to keep. ...why did I forget that?");
                Journal.unlock(level.getServer(), "mem_rose");
                Progress.grant(player, "rose");
                Fx.memoryDust(steve, 20);
                memory.addSanity(6f);
                r.addTrust(6f);
                return true;
            }
        }
        if (stack.is(Items.ROTTEN_FLESH)) return say(steve, memory, r, time, "rotten", "No. I know what that used to be.");
        if (stack.has(DataComponents.FOOD)) {
            stack.shrink(1);
            steve.heal(6f);
            r.gifts++;
            r.addTrust(r.gifts <= 3 ? 8f : 2f);
            r.addAnger(-15f);
            memory.addSanity(3f);
            memory.setDirty();
            Progress.grant(player, "gift");
            level.playSound(null, steve.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.8f, 1.0f);
            Fx.particles(level, ParticleTypes.HEART, steve.getEyePosition().add(0, 0.5, 0), 3, 0.3, 0.02);
            if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                memory.addSanity(15f);
                SteveVoice.steve(steve, "It's warm. I can feel it in the bits of me that are missing.");
            } else if (stack.is(Items.CAKE)) {
                SteveVoice.steve(steve, "Is it somebody's birthday? I don't have one. Can I have this one?");
            } else if (stack.is(Items.COOKIE)) {
                SteveVoice.steve(steve, "Small. Sweet. I'd forgotten sweet.");
            } else {
                SteveVoice.steve(steve, steve.getRandom(), "Thanks.", "For me? ...thank you.", "You're kind. People used to be kind.",
                        "I remember this taste. I don't remember where from.");
            }
            return true;
        }
        String id = Journal.readTag(stack);
        if ("journal".equals(id)) return say(steve, memory, r, time, "journal", "Keep writing it down. I forget, so you can't.");
        if (id != null) return say(steve, memory, r, time, "page", "That's not my handwriting. Someone else was watching me.");
        if (stack.is(ItemTags.BEDS)) {
            return say(steve, memory, r, time, "bed", "I don't sleep. I tried. When I close my eyes it all starts again from the beginning.");
        }
        if (stack.is(Items.MUSIC_DISC_13)) {
            memory.addSanity(-3f);
            return say(steve, memory, r, time, "disc13", "Turn it off. Please. That's what the dark sounds like from inside.");
        }
        if (stack.is(Items.MUSIC_DISC_11)) {
            memory.addSanity(-6f);
            return say(steve, memory, r, time, "disc11", "Where did you find that? Who was running? Who was RUNNING?");
        }
        if (stack.has(DataComponents.JUKEBOX_PLAYABLE)) return say(steve, memory, r, time, "disc", "I like this one. It sounds like before.");
        if (stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE)) {
            return say(steve, memory, r, time, "fire", "Careful. I've seen what that does to a house.");
        }
        if (stack.is(Items.TNT)) {
            memory.addSanity(-2f);
            return say(steve, memory, r, time, "tnt", "No. No, no. Put it away. Nothing that goes off like that.");
        }
        if (stack.is(Items.COMPASS)) {
            return say(steve, memory, r, time, "compass", "It points to the middle. Zero and zero. Everything is counted from there. I don't go.");
        }
        if (stack.is(Items.CLOCK)) return say(steve, memory, r, time, "clock", "It says it's today. It always says it's today.");
        if (stack.is(Items.BRICKS) || stack.is(Items.BRICK)) {
            return say(steve, memory, r, time, "bricks", "They built with these. Big. Bigger than mountains. Nothing inside.");
        }
        if (stack.is(Items.MOSSY_COBBLESTONE)) return say(steve, memory, r, time, "mossy", "My floor was like that. Green, in the cracks.");
        if (stack.is(Items.OBSIDIAN)) return say(steve, memory, r, time, "obsidian", "The black stone. The walls in the middle were made of it.");
        if (stack.is(Items.GLASS)) return say(steve, memory, r, time, "glass", "There used to be pillars of this. You could see them from far away. They meant: here.");
        if (stack.is(Items.PAINTING)) return say(steve, memory, r, time, "painting", "Don't hang that. Sometimes the pictures change when you're not looking.");
        if (stack.is(ItemTags.SIGNS)) return say(steve, memory, r, time, "sign", "Don't write 'stop'. He already wrote that.");
        if (stack.is(Items.SPYGLASS)) return say(steve, memory, r, time, "spyglass", "Don't look for him with that. It works both ways.");
        if (stack.is(Items.TORCH) || stack.is(Items.LANTERN)) return say(steve, memory, r, time, "torch", "Light. Good. Put them everywhere.");
        if (stack.is(Items.DIAMOND)) return say(steve, memory, r, time, "diamond", "Shiny. People used to do terrible things for those.");
        if (stack.is(ItemTags.SWORDS)) return say(steve, memory, r, time, "sword", "You won't need that for me. ...will you?");
        if (stack.is(Items.TOTEM_OF_UNDYING)) return say(steve, memory, r, time, "totem", "I don't need one of those. That's the problem.");
        if (stack.is(Items.ENDER_PEARL) || stack.is(Items.ENDER_EYE)) {
            return say(steve, memory, r, time, "ender", "That's from somewhere that wasn't there yet, when I started.");
        }
        if (stack.is(Items.MILK_BUCKET)) return say(steve, memory, r, time, "milk", "It doesn't fix this. I tried.");
        if (stack.is(Items.CARVED_PUMPKIN) || stack.is(Items.LEATHER_HELMET)) {
            return say(steve, memory, r, time, "mask", "If you wear that, he still sees you.");
        }
        if (stack.is(Items.DIRT)) {
            if (r.trust >= 30f) Journal.unlock(level.getServer(), "mem_shelter");
            return say(steve, memory, r, time, "dirt", "Dirt is enough for one night. That's how I did my first one.");
        }
        if (stack.is(Items.OAK_SAPLING) || stack.is(ItemTags.SAPLINGS)) return say(steve, memory, r, time, "sapling", "You put them back? I didn't know you could put them back.");
        if (stack.is(Items.CRAFTING_TABLE)) return say(steve, memory, r, time, "table", "Everything starts with one of those. I remember that much.");
        if (stack.is(Items.WRITABLE_BOOK) || stack.is(Items.BOOK)) return say(steve, memory, r, time, "book", "Write down that I was here. In case.");
        if (stack.is(Items.MAP) || stack.is(Items.FILLED_MAP)) return say(steve, memory, r, time, "map", "It has edges. The world didn't use to have edges. Then it did. Then it didn't again.");
        if (stack.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item())) {
            if (memory.cooldown("flower_" + player.getUUID(), time, 2400)) r.addTrust(3f);
            return say(steve, memory, r, time, "flower", "For me? I'll... hold it. I'm not sure where to put it.");
        }
        return false;
    }

    private static boolean say(OriginalSteve steve, SteveMemory memory, Relationship r, long time, String key, String line) {
        if (!memory.cooldown("item_" + key, time, 200)) return false;
        if (r.firstTime("item_" + key)) r.addTrust(1f);
        steve.getNavigation().stop();
        SteveVoice.steve(steve, line);
        memory.setDirty();
        return true;
    }

    // ---- chat -----------------------------------------------------------------------

    private static boolean has(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
