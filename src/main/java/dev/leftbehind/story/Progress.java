package dev.leftbehind.story;


import dev.leftbehind.LeftBehind;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModSounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

/**
 * How far the story has got: the four acts, and the advancements that mark the way.
 * Nothing here is random. The acts only move forward when the players have earned it.
 */
public final class Progress {
    private static final String[] ACT_NAMES = {"", "The One Who Stayed", "Familiar Things", "The Observer", "What Is Left"};
    private static final String[] ACT_NUMERALS = {"", "I", "II", "III", "IV"};
    /** One Minecraft day. */
    private static final long DAY = 24000L;

    private Progress() {}

    // ---- advancements ---------------------------------------------------------------

    public static void grant(ServerPlayer player, String id) {
        MinecraftServer server = player.level().getServer();
        AdvancementHolder holder = server.getAdvancements().get(Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "story/" + id));
        if (holder != null) player.getAdvancements().award(holder, "done");
    }

    public static void grantAll(MinecraftServer server, String id) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) grant(player, id);
    }

    /** Grants to everyone within a distance of a point in the overworld. */
    public static void grantNear(ServerLevel level, net.minecraft.world.phys.Vec3 at, double distance, String id) {
        for (ServerPlayer player : level.players()) {
            if (player.position().distanceToSqr(at) < distance * distance) grant(player, id);
        }
    }

    public static void onJournal(ServerPlayer player, SteveMemory memory, Journal.Entry entry) {
        if (entry.section() == Journal.Section.PAGE) {
            grant(player, "page");
            if (memory.count("page_") >= Journal.total(Journal.Section.PAGE)) grant(player, "all_pages");
        }
        if (entry.section() == Journal.Section.MEMORY && memory.count("mem_") >= Journal.total(Journal.Section.MEMORY)) {
            grant(player, "memories");
        }
    }

    /** Things a player who joins late should still get. */
    public static void catchUp(ServerPlayer player, SteveMemory memory) {
        if (memory.steveId != null) grant(player, "root");
        for (int act = 2; act <= memory.act(); act++) grant(player, "act_" + act);
    }

    // ---- acts -----------------------------------------------------------------------

    public static String actName(int act) {
        return "Act " + ACT_NUMERALS[act] + ": " + ACT_NAMES[act];
    }

    public static void setAct(MinecraftServer server, SteveMemory memory, int act, boolean announce) {
        act = Math.max(1, Math.min(4, act));
        memory.setCounter("act", act);
        memory.setCounter("act_start", server.overworld().getGameTime());
        for (int a = 1; a <= act; a++) {
            if (a < act || !announce) {
                if (!memory.journal.contains("act_" + a)) memory.journal.add("act_" + a);
            }
        }
        memory.setDirty();
        if (!announce) return;
        Journal.unlock(server, "act_" + act);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Fx.title(player,
                    Component.literal("ACT " + ACT_NUMERALS[act]).withStyle(ChatFormatting.WHITE),
                    Component.literal(ACT_NAMES[act]).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), 30, 90, 40);
            Score.play(player, Score.forAct(act));
            if (act > 1) {
                Fx.staticFlash(player, 12);
                grant(player, "act_" + act);
            } else {
                grant(player, "root");
            }
        }
        Component why = Component.literal(StoryLog.actMeaning(act)).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        Component hint = Component.literal("Open the journal to see where things stand (/steve journal).").withStyle(ChatFormatting.DARK_GRAY);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal(actName(act)).withStyle(ChatFormatting.GOLD));
            player.sendSystemMessage(why);
            player.sendSystemMessage(hint);
        }
        LeftBehind.LOGGER.info("The story moves on: {}", actName(act));
    }

    /** What is still missing before the story moves on, in plain words, for the journal. */
    public static List<String> missing(MinecraftServer server, SteveMemory memory) {
        List<String> out = new ArrayList<>();
        int act = memory.act();
        long inAct = server.overworld().getGameTime() - memory.counter("act_start");
        int memories = memory.count("mem_");
        int talks = 0;
        for (Relationship r : memory.relationships.values()) talks += r.talks;
        switch (act) {
            case 1 -> {
                out.add(check(memory.highestTrust() >= 35f, "He trusts you (" + Math.round(memory.highestTrust()) + " of 35)"));
                out.add(check(talks >= 3, "You have talked to him (" + Math.min(talks, 3) + " of 3 times)"));
                out.add(check(inAct > DAY * 3 / 4, "Most of a day has passed together"));
            }
            case 2 -> {
                out.add(check(memories >= 3, "Memories he got back (" + Math.min(memories, 3) + " of 3)"));
                out.add(check(memory.flag("ghost_seen"), "One of the others has been seen"));
                out.add(check(memory.breaches >= 1, "He has looked Herobrine in the eye"));
            }
            case 3 -> {
                out.add(check(memory.breaches >= 3, "Lost nights (" + Math.min(memory.breaches, 3) + " of 3)"));
                out.add(check(memories >= 7, "Memories he got back (" + Math.min(memories, 7) + " of 7)"));
            }
            default -> {}
        }
        return out;
    }

    private static String check(boolean done, String what) {
        return (done ? "[x] " : "[ ] ") + what;
    }

    /** Called every few seconds. Moves the story on when its conditions are met. */
    public static void checkActs(MinecraftServer server, SteveMemory memory) {
        if (memory.steveId == null || memory.mood().inEpisode()) return;
        int act = memory.act();
        long inAct = server.overworld().getGameTime() - memory.counter("act_start");
        int memories = memory.count("mem_");
        int talks = 0;
        for (Relationship r : memory.relationships.values()) talks += r.talks;
        boolean advance = switch (act) {
            // He has to know you a little first.
            case 1 -> (inAct > DAY * 3 / 4 && memory.highestTrust() >= 35f && talks >= 3) || inAct > DAY * 3;
            // The others have been seen, he has started to remember, and he has looked Herobrine in the eye once.
            case 2 -> (inAct > DAY && memories >= 3 && memory.flag("ghost_seen") && memory.breaches >= 1) || (inAct > DAY * 4 && memory.breaches >= 1);
            // It has happened enough times that nobody can pretend it isn't happening.
            case 3 -> (inAct > DAY && memory.breaches >= 3 && memories >= 7) || (inAct > DAY * 6 && memory.breaches >= 3);
            default -> false;
        };
        if (advance) setAct(server, memory, act + 1, true);
    }
}
