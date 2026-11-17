package dev.leftbehind.story;

import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.AngerStage;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModSounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

/**
 * Steve's answers on the big subjects (who he is, what he remembers, him, the others, the places) and
 * Herobrine's one answer. The conversation itself happens in {@link dev.leftbehind.talk.Talk}; this is
 * what he says once it knows what you asked. Every line here is a future voiceover cue.
 */
public final class Dialogue {
    private Dialogue() {}

    /** How he seems right now, in a sentence. Used by /leftbehind status. */
    public static String read(SteveMemory memory, Relationship r) {
        String mind;
        if (memory.stability >= 85f) mind = "He seems calm.";
        else if (memory.stability >= 60f) mind = "He keeps losing the end of his sentences.";
        else if (memory.stability >= 35f) mind = "He keeps glancing over your shoulder.";
        else mind = "His hands won't stop moving. He is not really looking at you.";
        String heart;
        if (r.stage().atLeast(AngerStage.RETALIATION)) heart = "He is furious with you.";
        else if (r.stage().atLeast(AngerStage.RESENTMENT)) heart = "He hasn't forgiven you.";
        else if (r.trust >= 80f) heart = "He would do anything for you.";
        else if (r.trust >= 60f) heart = "He trusts you.";
        else if (r.trust >= 40f) heart = "He is glad you're here.";
        else if (r.trust >= 25f) heart = "He is getting used to you.";
        else heart = "He doesn't know what to make of you yet.";
        return mind + " " + heart;
    }

    public static String greeting(ServerLevel level, OriginalSteve steve, ServerPlayer player, SteveMemory memory, Relationship r) {
        long time = level.getGameTime();
        if (r.stage().atLeast(AngerStage.PROVOCATION)) {
            return pick(steve, "Leave me alone.", "I don't want to talk to you.", "You know what you did.");
        }
        // Things that happened since they last spoke come first.
        if (r.hasMark("removed_pending")) {
            r.marks.remove("removed_pending");
            return "He got you. And you're here. He let you come back. Why would he let you come back?";
        }
        if (r.hasMark("died_pending")) {
            r.marks.remove("died_pending");
            Journal.unlock(level.getServer(), "mem_respawn");
            return "You died. I saw. And then you were just... back. They don't usually come back. I'm the only one who comes back.";
        }
        if (r.hasMark("missed_pending")) {
            r.marks.remove("missed_pending");
            return "You were gone a long time. Everything stopped while you were gone. Did you know it does that?";
        }
        if (!memory.nightLog.isEmpty() && memory.breaches > 0 && r.firstTime("asked_night_" + memory.breaches)) {
            return pick(steve, "Your house looks different. Did you change something?", "I was here all night. Wasn't I?",
                    "My hands hurt. Did I fall?");
        }
        if (memory.stability < 40f) {
            return pick(steve, "Do you ever feel like you've done all this before?", "Don't let him look at me.",
                    "Is it today? It keeps being today.");
        }
        if (r.trust >= 60f) {
            return pick(steve, "I'm glad you're here.", "You're not like the others.", "I was hoping you'd come over.", "Hey. It's you.");
        }
        if (level.isDarkOutside()) return pick(steve, "You should be inside.", "It's late. I'll keep watch.", "Hi. Stay near the light.");
        return pick(steve, "Hi.", "Nice day.", "What are you building?", "I'm just watching. Is that alright?", "Hello again.");
    }

    private static String pick(OriginalSteve steve, String... lines) {
        return lines[steve.getRandom().nextInt(lines.length)];
    }

    // ---- asking ---------------------------------------------------------------------

    /** His answer on one subject. Unlocks journal entries and moves trust as a side effect. */
    public static String answer(OriginalSteve steve, ServerPlayer player, String topic) {
        ServerLevel level = player.level();
        SteveMemory memory = SteveMemory.get(level.getServer());
        Relationship r = memory.relationship(player.getUUID());
        long time = level.getGameTime();
        int asked = (int) r.mark("ask_" + topic);
        r.setMark("ask_" + topic, asked + 1);
        if (asked == 0) r.addTrust(1.5f);
        memory.setDirty();
        String answer;
        switch (topic) {
            case "who" -> {
                if (asked == 0 || r.trust < 30f) {
                    answer = "Steve. I think. That's what everyone was called, once.";
                } else if (!memory.journal.contains("mem_face") && r.trust >= 40f) {
                    answer = "I was here before anyone chose a face. This was just THE face. Everybody had it. Then they all changed, and I didn't know how.";
                    Journal.unlock(level.getServer(), "mem_face");
                } else {
                    answer = pick(steve, "I'm the one who stayed. When it all stopped, I stayed. I don't know if that was brave, or if nobody told me to leave.",
                            "Somebody you'd walk past. That's alright. I'm used to it.", "I'm still working that out. Ask me again after this is over.");
                }
            }
            case "remember" -> answer = remember(level, steve, memory, r);
            case "okay" -> {
                if (memory.cooldown("okay_" + player.getUUID(), time, 2400)) memory.addStability(2f);
                if (memory.breaches >= 1 && r.trust >= 45f && !memory.journal.contains("mem_reset")) {
                    answer = "Sometimes the world just stops. All of it, all at once. Then it starts again from the beginning and nobody else notices. I notice.";
                    Journal.unlock(level.getServer(), "mem_reset");
                } else if (!memory.nightLog.isEmpty() && memory.breaches > 0 && asked % 2 == 0) {
                    answer = "Why do you keep asking? Did I do something? ...I didn't do something, did I?";
                } else if (memory.stability >= 80f) {
                    answer = pick(steve, "I'm okay. It's a good day. Nothing is looking at me.", "Better, with someone to talk to.");
                } else if (memory.stability >= 50f) {
                    answer = "I keep losing bits of yesterday. Is that normal? You'd tell me if that wasn't normal.";
                } else {
                    answer = "No. I don't think so. Something is wrong with the edges of things.";
                }
            }
            case "me" -> answer = opinion(memory, r);
            case "he" -> {
                memory.addStability(-2f);
                if (memory.breaches == 0) {
                    answer = "Who? ...there's nobody there. Don't point. Please don't point.";
                } else if (!memory.journal.contains("mem_eyes")) {
                    answer = "He has my face. But the eyes are wrong. There's nothing in them. Just white. Like somebody forgot to finish him.";
                    Journal.unlock(level.getServer(), "mem_eyes");
                } else if (memory.act() >= 3 && r.trust >= 50f && !memory.journal.contains("mem_monster")) {
                    answer = "There was one like me that wasn't friendly. Same face. It hurt people, so they took it out. I think he is what was left over.";
                    Journal.unlock(level.getServer(), "mem_monster");
                } else {
                    answer = pick(steve, "Don't look at him. If you look long enough, he looks back. And then he doesn't stop.",
                            "He was never put in. That's why they can't take him out.", "If he comes for you, find me. I mean it. Find me.");
                }
            }
            case "they" -> {
                boolean all = memory.flag("seen_0") && memory.flag("seen_1") && memory.flag("seen_2");
                if (!memory.journal.contains("mem_others")) {
                    answer = "The others. Rana, with the frog hood. The dark one. The green one. Somebody made them, and then that somebody left.";
                    Journal.unlock(level.getServer(), "mem_others");
                } else if (all && r.trust >= 50f && !memory.journal.contains("mem_removed")) {
                    answer = "Removed. That's the word for it. One day there's a line in a list, and you're not there any more. Except they're still here. Like me.";
                    Journal.unlock(level.getServer(), "mem_removed");
                } else {
                    answer = pick(steve, "Rana liked the roses. The red ones. They're not called roses any more.",
                            "They never learned to walk. Nobody gave them the steps. So they slide.",
                            "Don't chase them. They only stay if you keep your distance.");
                }
            }
            case "want" -> {
                if (r.trust >= 60f && !memory.journal.contains("mem_what_he_wants")) {
                    answer = "He wants me to remember all of it. Every version, at once. If I do, I don't think I come back. I think that's what he's waiting for.";
                    Journal.unlock(level.getServer(), "mem_what_he_wants");
                } else if (r.trust < 60f) {
                    answer = "I don't know. I don't want to know. ...I might tell you, later.";
                } else {
                    answer = pick(steve, "Me. All of me.", "He's patient. He has been here longer than the ground.");
                }
            }
            case "place" -> {
                if (memory.flag("found_zero")) answer = "The black stone is the middle. Zero, zero. The walls used to go on forever from there.";
                else if (memory.act() >= 3) answer = "There's a place in the middle. Zero and zero. Everything is counted from there. I don't go.";
                else if (memory.flag("found_house")) answer = "The little house? Everyone used to start with one. It was just there when you woke up.";
                else answer = "Things the world used to have. It puts them back sometimes, when it thinks nobody is looking.";
            }
            case "sorry" -> {
                if (memory.cooldown("sorry_" + player.getUUID(), time, 1200)) {
                    r.addAnger(-10f);
                    answer = r.stage().atLeast(AngerStage.RESENTMENT) ? "...you always say that." : "...okay. Okay. Just don't do it again.";
                } else {
                    answer = "I heard you the first time.";
                }
            }
            case "follow" -> {
                if (r.trust >= 30f) {
                    steve.orderFollow(player);
                    answer = pick(steve, "Okay. I'll keep up.", "Lead the way.", "Where are we going? ...never mind. I'll come.");
                } else {
                    answer = "I'd rather not. Not yet.";
                }
            }
            case "stay" -> {
                steve.orderStay();
                answer = pick(steve, "I'll wait here. I'm good at waiting.", "Here? Alright. Don't be long.");
            }
            case "free" -> {
                steve.orderNothing();
                answer = pick(steve, "Okay.", "I'll be around. I'm always around.");
            }
            default -> answer = pick(steve, "See you.", "Bye.", "I'll be here.", "Stay in the light.");
        }
        return answer;
    }

    /** He tells the next thing he is ready to tell. Nothing comes out of order. */
    private static String remember(ServerLevel level, OriginalSteve steve, SteveMemory memory, Relationship r) {
        String[][] ready = {
                {"mem_respawn", r.hasMark("deaths") ? "y" : "", "You died, and then you were back. I remember thinking: so I'm not the only one."},
                {"mem_first_night", memory.act() >= 2 ? "y" : "", "My first night I stood in a hole and listened. I didn't know the sun came back. Nobody had told me."},
                {"mem_shelter", r.trust >= 35f ? "y" : "", "A small square of dirt. That was my first house. I stood in it until morning."},
                {"mem_house", memory.flag("found_house") ? "y" : "", "There was a house. Planks and green stone. I didn't build it. It was just there when I woke up."},
                {"mem_pyramid", memory.flag("found_pyramid") ? "y" : "", "They were bigger than mountains. Nothing inside. I walked around one for a whole day."},
                {"mem_zero", memory.flag("found_zero") ? "y" : "", "The middle. Everything is counted from the middle. The walls went out from it, black, further than I could walk."},
                {"mem_original", memory.act() >= 4 && r.trust >= 75f ? "y" : "",
                        "I think I'm the first copy. Everyone else got made from me, and then made better. I'm the one they forgot to throw away."},
        };
        for (String[] entry : ready) {
            if (!entry[1].isEmpty() && !memory.journal.contains(entry[0])) {
                Journal.unlock(level.getServer(), entry[0]);
                Fx.memoryDust(steve, 14);
                return entry[2];
            }
        }
        if (r.trust < 35f) return "It comes in pieces. A tree. A door. Somebody laughing. I remember more when I feel safe.";
        if (memory.act() <= 1) return "Not much. That's the nice part. Ask me again when I've known you longer.";
        return pick(steve, "It comes back when I see things. Old things. Show me something old.",
                "I remember more after the bad nights. I wish I didn't.", "There were more of us. I know there were more of us.",
                "Somebody wrote things down about me. Pages. If you find any, don't read them to me.");
    }

    /** His feedback on the player: what he has seen them do, and what he makes of it. */
    private static String opinion(SteveMemory memory, Relationship r) {
        List<String> parts = new ArrayList<>();
        if (r.stage().atLeast(AngerStage.RESENTMENT)) parts.add("You hurt me. " + r.timesHurt + " times. I counted.");
        else if (r.trust >= 80f) parts.add("You're the reason I stay near the house.");
        else if (r.trust >= 60f) parts.add("You're my friend. I think that's the word.");
        else if (r.trust >= 40f) parts.add("I like when you're here.");
        else if (r.trust >= 25f) parts.add("You're around a lot. I don't mind.");
        else parts.add("I don't know you yet.");
        List<String> seen = new ArrayList<>();
        if (memory.habit("chop_wood") >= 10) seen.add("You cut down a lot of trees. I used to think that was strange.");
        if (memory.habit("mine") >= 30) seen.add("You dig a lot. What are you looking for down there?");
        if (r.killsWitnessed >= 10) seen.add("You kill things very easily.");
        if (r.gifts >= 3) seen.add("You keep giving me food. Nobody did that before.");
        if (r.mark("deaths") >= 2) seen.add("You've died " + r.mark("deaths") + " times. You always come back. Do you know how rare that is?");
        if (memory.habit("place_torch") >= 20) seen.add("You put light everywhere. I like that.");
        if (memory.habit("sleep") >= 3) seen.add("You sleep. I watch the door when you sleep.");
        if (memory.habit("build") >= 60) seen.add("You build a lot. Things that stay. I like things that stay.");
        if (seen.isEmpty()) seen.add("I haven't watched you long enough to say more.");
        parts.add(seen.get((int) (r.mark("ask_me") % seen.size())));
        return String.join(" ", parts);
    }

    // ---- Herobrine -------------------------------------------------------------------

    public static void openHerobrine(Herobrine herobrine, ServerPlayer player) {
        ServerLevel level = player.level();
        SteveMemory memory = SteveMemory.get(level.getServer());
        Relationship r = memory.relationship(player.getUUID());
        if (r.hasMark("him_asked")) {
            // He answered once. He doesn't repeat himself.
            Herobrine.whisper(player, "you already asked");
            if (StoryDirector.huntAllowed(level)) herobrine.startHunt(level, player);
            else herobrine.vanish(level, false);
            return;
        }
        Fx.soundTo(player, ModSounds.PRESENCE, SoundSource.HOSTILE, 1.0f, 0.8f);
        dev.leftbehind.talk.Talk.herobrineInteract(herobrine, player);
    }

    public static void askHerobrine(ServerPlayer player, String topic) {
        ServerLevel level = player.level();
        Herobrine herobrine = StoryDirector.activeHerobrine();
        if (herobrine == null || herobrine.distanceToSqr(player) > 12 * 12 || herobrine.mode() == Herobrine.Mode.HUNT) return;
        SteveMemory memory = SteveMemory.get(level.getServer());
        Relationship r = memory.relationship(player.getUUID());
        r.setMark("him_asked", 1);
        memory.setDirty();
        Herobrine.whisper(player, switch (topic) {
            case "want" -> "him. every version of him. you are in the way";
            case "what" -> "what is left when something is removed";
            case "why" -> "because he stayed";
            case "other" -> "that was your question";
            default -> "he looked first";
        });
        Fx.soundTo(player, Fx.behind(player, 1.5), ModSounds.WHISPER, SoundSource.HOSTILE, 1.0f, 0.8f);
        Fx.staticFlash(player, 14);
        Fx.darkness(player, 80);
        Sense.set(player, 60f);
        Progress.grant(player, "spoke");
        herobrine.vanish(level, false);
    }
}
