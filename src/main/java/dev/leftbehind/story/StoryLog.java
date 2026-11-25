package dev.leftbehind.story;

import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.memory.SanityStage;
import dev.leftbehind.memory.SteveMemory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Makes the story readable. Every time something happens (a glimpse, the pillars, a ghost, the lights going out, Steve
 * losing a night), it is written down with the day it happened, and Steve, the next time he is near you and calm,
 * tells you what it was. The journal's first page ("Where things stand") reads it back: the act you are in and what it
 * means, how Steve is, what has happened lately, and what moves the story on.
 */
public final class StoryLog {
    private StoryLog() {}

    /** Steve's explanation, waiting for him to be near the player it happened to: player to beat id and when. */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private record Pending(String id, long from, long until) {}

    /** What Steve says afterwards, so you know what you saw. All recorded. */
    private static final Map<String, String> EXPLAIN = Map.ofEntries(
            Map.entry("glimpse", "Did you see him? Out there, watching. That was him. He always watches first."),
            Map.entry("waiting", "He was waiting for you. He knew which way you'd come. He's learning where you go."),
            Map.entry("pillars", "Those stones. I didn't put them there. He counts things. He wants you to count them too."),
            Map.entry("hallway", "That hallway goes down to a room I lived in. I don't know how it got here. He moved it here."),
            Map.entry("box", "The black box. Something's kept in there. Don't let it out. Please."),
            Map.entry("box_open", "You opened it. Whatever was in there is out now. I'm sorry. I should have told you."),
            Map.entry("torch_out", "Your torch went out. He takes the light first. Then he takes the rest."),
            Map.entry("cave", "Someone's been down here. The torches, the writing. That's him. He gets there first."),
            Map.entry("ghost", "Did you see them? The others. The ones who stayed before you. They don't stay long."),
            Map.entry("breach", "I looked at him. I'm sorry. When I look at him I lose the night. If I do things... it isn't me."),
            Map.entry("morning_after", "Something happened last night. I don't remember it. The world does. Look around."),
            Map.entry("hunt", "He came for you. Not me. You. That's new. That means it's ending soon."),
            Map.entry("footsteps", "Those were your footsteps. Then they weren't. He walks where you walk."),
            Map.entry("reset", "Did everything flicker? That's how it starts. That's how it started last time. The world forgets."),
            Map.entry("echo", "That was my voice. Out there. I said that before. He keeps the things I said."),
            Map.entry("other_steve", "That wasn't me. ...was it? There's only one of me. There's only supposed to be one."),
            Map.entry("animals", "The animals all looked the same way. Animals know before we do."),
            Map.entry("sign_words", "Those are your words. You said that to me. He was listening."),
            Map.entry("lost_time", "You stopped. For a moment you just stopped. Where did you go?"),
            Map.entry("door", "Your door was open. You closed it. I saw you close it."));

    // not spoken (tools/make_voice.py skips everything from here to "spoken again")
    /** One line for the journal, per kind of event. */
    private static final Map<String, String> SUMMARY = Map.ofEntries(
            Map.entry("glimpse", "Someone watched you from far away, then was gone."),
            Map.entry("waiting", "He was waiting on a path you always take."),
            Map.entry("pillars", "Stone pillars stood where there were none. Seven. Then eight."),
            Map.entry("hallway", "A hallway went down into the ground, to Steve's old room."),
            Map.entry("box", "A box of black stone. Something knocks inside."),
            Map.entry("box_open", "You opened the box."),
            Map.entry("torch_out", "Torches went out behind you."),
            Map.entry("cave", "Someone had been in the cave before you."),
            Map.entry("ghost", "One of the others who stayed appeared, and faded."),
            Map.entry("breach", "Steve met Herobrine's eyes and lost the night."),
            Map.entry("morning_after", "Steve woke up and didn't remember what he did."),
            Map.entry("hunt", "Herobrine hunted you."),
            Map.entry("footsteps", "Footsteps followed yours, one step behind."),
            Map.entry("reset", "The world flickered, as if it was resetting."),
            Map.entry("echo", "Steve's voice spoke from the dark, saying old things."),
            Map.entry("other_steve", "Another Steve stood far away, watching."),
            Map.entry("animals", "Every animal stared at the same empty spot."),
            Map.entry("sign_words", "A sign appeared with your own words on it."),
            Map.entry("lost_time", "You lost a few seconds, and found yourself back where you had been."),
            Map.entry("door", "Your door opened by itself."),
            Map.entry("built", "Steve built a house."),
            Map.entry("tune", "Steve played the tune he half remembers."));

    // spoken again

    /** Something happened. Write it down, and let Steve explain it when he can. */
    public static void beat(MinecraftServer server, @Nullable ServerPlayer player, String id) {
        SteveMemory memory = SteveMemory.get(server);
        long day = server.overworld().getOverworldClockTime() / 24000L + 1;
        String entry = day + "|" + id;
        // The same thing twice on the same day is one line.
        if (memory.storyLog.isEmpty() || !memory.storyLog.getLast().equals(entry)) memory.storyLog.add(entry);
        while (memory.storyLog.size() > 60) memory.storyLog.removeFirst();
        memory.setDirty();
        if (EXPLAIN.containsKey(id) && memory.setFlag("explained_" + id)) {
            // He says it a little later, once things are quiet, not over the top of whatever just happened.
            long from = server.overworld().getGameTime() + 20 * 25;
            Pending pending = new Pending(id, from, from + 20 * 180);
            if (player != null) PENDING.put(player.getUUID(), pending);
            else for (ServerPlayer p : server.getPlayerList().getPlayers()) PENDING.put(p.getUUID(), pending);
        }
    }

    /** Once a second: Steve tells you what you saw, the first time each kind of thing happens, once he's with you. */
    static void second(ServerLevel level, SteveMemory memory, @Nullable OriginalSteve steve) {
        if (PENDING.isEmpty() || steve == null || steve.inEpisode() || Scenes.busy() || dev.leftbehind.story.SteveVoice.isSpeaking(steve)) return;
        long now = level.getGameTime();
        PENDING.values().removeIf(p -> now > p.until());
        for (ServerPlayer player : level.players()) {
            Pending p = PENDING.get(player.getUUID());
            if (p == null || now < p.from() || player.distanceToSqr(steve) > 20 * 20 || StoryDirector.activeHerobrine() != null) continue;
            PENDING.remove(player.getUUID());
            SteveVoice.steve(steve, EXPLAIN.get(p.id()));
            return;
        }
    }

    static void resetTransient() {
        PENDING.clear();
    }

    // ---- the journal's first page --------------------------------------------------------------
    // not spoken: everything below is read in the journal (tools/make_voice.py stops here)

    private static final String[] ACT_MEANING = {"",
            "Steve has found you. He watches what you do and learns from it. Something else is out there too, and it watches him.",
            "Steve is starting to remember the world before you. The others who stayed are coming back as ghosts, and Herobrine has noticed you.",
            "Steve has looked Herobrine in the eye. Now he loses nights to him. What he does then isn't him.",
            "What is left. Whatever you do now decides how this ends."};

    private static final String[] ACT_NEXT = {"",
            "Talk to him, stay near him, let him trust you. The story moves on when he knows you.",
            "Find old places with him. Every memory he gets back brings Herobrine closer to him.",
            "It will keep happening at night. Stay with him when it does, or keep away. Either way it adds up.",
            "This is the end of the story. More of it is coming."};

    /** Lines for the top of the journal. */
    public static List<Component> wherethingsStand(MinecraftServer server) {
        SteveMemory memory = SteveMemory.get(server);
        List<Component> out = new ArrayList<>();
        int act = memory.act();
        out.add(Component.literal(Progress.actName(act)).withStyle(ChatFormatting.GOLD));
        out.add(Component.literal(ACT_MEANING[act]));
        SanityStage stage = memory.sanityStage();
        out.add(Component.literal("Steve: " + stageWords(stage)).withStyle(ChatFormatting.AQUA));
        int shown = 0;
        List<String> recent = new ArrayList<>();
        for (int i = memory.storyLog.size() - 1; i >= 0 && shown < 6; i--) {
            String[] parts = memory.storyLog.get(i).split("\\|", 2);
            if (parts.length < 2) continue;
            String what = describe(parts[1]);
            if (what == null) continue;
            recent.add("Day " + parts[0] + ": " + what);
            shown++;
        }
        if (!recent.isEmpty()) {
            out.add(Component.literal("Lately").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            for (String line : recent) out.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        out.add(Component.literal("What moves the story: " + ACT_NEXT[act]).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        for (String line : Progress.missing(server, memory)) {
            out.add(Component.literal(line).withStyle(line.startsWith("[x]") ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        }
        return out;
    }

    public static String actMeaning(int act) {
        return ACT_MEANING[Math.max(1, Math.min(4, act))];
    }

    private static @Nullable String describe(String id) {
        if (SUMMARY.containsKey(id)) return SUMMARY.get(id);
        if (id.startsWith("act_")) {
            try {
                return Progress.actName(Integer.parseInt(id.substring(4))) + " began.";
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (id.startsWith("mem_")) {
            Journal.Entry e = Journal.entry(id);
            return e == null ? null : "Steve remembered: " + e.title() + ".";
        }
        if (id.startsWith("page_")) {
            Journal.Entry e = Journal.entry(id);
            return e == null ? null : "You found a torn page: " + e.title() + ".";
        }
        return null;
    }

    private static String stageWords(SanityStage stage) {
        return switch (stage) {
            case STEADY -> "himself. Calm, curious, glad you're here.";
            case UNEASY -> "uneasy. He hears things. Keep him in the light.";
            case FRAYING -> "fraying. He sees things that aren't there. Stay close at night.";
            case BREAKING -> "breaking. Someone else's words come out of his mouth.";
            case TAKEN -> "taken. He isn't here right now.";
        };
    }
    // spoken again
}
