package dev.leftbehind.talk;

import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.AngerStage;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.story.Dialogue;
import dev.leftbehind.story.Journal;
import dev.leftbehind.story.Progress;
import dev.leftbehind.story.Reactions;
import dev.leftbehind.story.Sense;
import dev.leftbehind.story.SteveVoice;
import dev.leftbehind.story.StoryDirector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Talking with Steve by typing in chat. He hears what players near him say, thinks for a moment, and answers.
 * He also starts conversations himself: he greets you when you come back, asks you things, tells you things,
 * and remembers what you told him. Words he doesn't know, he asks about, and remembers what they mean.
 */
public final class Talk {
    /** Everything one reply needs to know. */
    public static final class Ctx {
        public final ServerPlayer player;
        public final OriginalSteve steve;
        public final ServerLevel level;
        public final SteveMemory memory;
        public final Relationship rel;
        public final Session session;
        public final RandomSource random;
        public final long now;
        @Nullable String word;
        @Nullable String def;
        @Nullable String answer;
        @Nullable String teacher;

        Ctx(ServerPlayer player, OriginalSteve steve, ServerLevel level, SteveMemory memory) {
            this.player = player;
            this.steve = steve;
            this.level = level;
            this.memory = memory;
            this.rel = memory.relationship(player.getUUID());
            this.session = session(player);
            this.random = level.getRandom();
            this.now = level.getGameTime();
        }
    }

    /** What is going on in one player's conversation right now. Not saved: a conversation doesn't survive a restart. */
    static final class Session {
        long lastExchange = -100000;
        long lastHeard = -100000;
        long lastNear = -100000;
        long nextInit;
        @Nullable String pendingQ;
        long askedAt;
        @Nullable String pendingWord;
        @Nullable String topic;
        @Nullable String lastQ;
        long quietUntil;
        long herobrineUntil;
        final Map<String, Integer> lastVariant = new HashMap<>();
    }

    private record Heard(UUID player, long at, String text, String raw) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final List<Heard> HEARD = new ArrayList<>();
    private static @Nullable Set<String> vocabulary;
    private static @Nullable Map<String, String> newThings;

    private Talk() {}

    public static void reset() {
        SESSIONS.clear();
        HEARD.clear();
    }

    /** True while someone is in the middle of a conversation with him, so his idle remarks wait. */
    public static boolean inConversation(long now) {
        for (Session s : SESSIONS.values()) if (now - s.lastHeard < 400) return true;
        return false;
    }

    static Session session(ServerPlayer player) {
        return SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
    }

    // ---- hearing -------------------------------------------------------------------

    /** Called for every chat message a player sends. */
    public static void hear(ServerPlayer player, String raw) {
        ServerLevel level = player.level();
        if (level != level.getServer().overworld()) return;
        Session session = session(player);
        long now = level.getGameTime();
        String text = Words.normalize(raw);

        // If Herobrine is waiting for your question, this is it.
        if (session.herobrineUntil > now) {
            session.herobrineUntil = 0;
            Dialogue.askHerobrine(player, herobrineTopic(text));
            return;
        }
        // Saying his name where he can hear it is a bad idea.
        if (text.contains(" herobrine ")) {
            Herobrine herobrine = StoryDirector.activeHerobrine();
            if (herobrine != null && herobrine.distanceToSqr(player) < 72 * 72 && herobrine.mode() != Herobrine.Mode.HUNT) {
                Sense.add(player, 25f);
                Herobrine.whisper(player, "yes");
            }
        }
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve == null || !steve.isAlive()) return;
        double dist = steve.distanceTo(player);
        boolean named = text.contains(" steve ");
        boolean inConversation = now - session.lastExchange < 600;
        boolean hears = (dist < 10 && steve.hasLineOfSight(player)) || (inConversation && dist < 20) || (named && dist < 40);
        if (!hears) return;
        if (!steve.inEpisode()) steve.startTalking(player);
        // A moment to think, longer for longer messages.
        HEARD.add(new Heard(player.getUUID(), now + 12 + Math.min(30, raw.length() / 3), text, raw));
    }

    public static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        if (!HEARD.isEmpty()) {
            Iterator<Heard> it = HEARD.iterator();
            while (it.hasNext()) {
                Heard heard = it.next();
                if (now < heard.at()) continue;
                it.remove();
                ServerPlayer player = server.getPlayerList().getPlayer(heard.player());
                OriginalSteve steve = StoryDirector.findSteve(level);
                if (player == null || steve == null || !steve.isAlive()) continue;
                respond(new Ctx(player, steve, level, SteveMemory.get(server)), heard.text(), heard.raw());
            }
        }
        if (now % 20 == 7) initiateTick(level, now);
    }

    // ---- answering -----------------------------------------------------------------

    private static void respond(Ctx c, String text, String raw) {
        Session s = c.session;
        s.lastExchange = c.now;
        s.lastHeard = c.now;
        countTalk(c);
        TalkBook book = TalkBook.get();

        if (c.steve.inEpisode()) {
            if (c.rel.trust >= 60f && c.steve.distanceToSqr(c.player) < 10 * 10) {
                say(c, "episode_kind", List.of(List.of("Keep talking. Your voice helps."), List.of("Is it you? Is it really you?"),
                        List.of("Don't go. Just... say things. Anything.")));
            } else {
                say(c, "episode", List.of(List.of("Stay away!"), List.of("Don't come closer!"), List.of("Who said that? WHO SAID THAT?")));
            }
            return;
        }
        TalkBook.Intent sorry = intentById("sorry");
        if (c.rel.stage().atLeast(AngerStage.PROVOCATION)) {
            if (sorry != null && score(sorry, text) > 0) {
                say(c, Dialogue.answer(c.steve, c.player, "sorry"));
            } else {
                say(c, "cold", List.of(List.of("..."), List.of("Leave me alone."), List.of("I don't want to talk to you."),
                        List.of("You know what you did.")));
            }
            return;
        }
        if (s.quietUntil > c.now) {
            if (sorry != null && score(sorry, text) > 0) s.quietUntil = 0;
            else return;
        }
        // "My name is ..." works any time.
        String name = Words.nameStatement(text, raw, false);
        if (name != null) {
            boolean changed = c.rel.note("name") != null && !name.equals(c.rel.note("name"));
            c.rel.notes.put("name", name);
            c.memory.setDirty();
            if (s.pendingQ != null && s.pendingQ.equals("q_name")) s.pendingQ = null;
            c.rel.firstTime("q_q_name");
            say(c, changed ? "Oh. {name}, then. I'll fix it in my head." : "{name}. I'll remember that. I remember everything.");
            return;
        }
        // An answer to something he asked.
        if (s.pendingQ != null && c.now - s.askedAt < 1600 && answer(c, text, raw)) return;
        s.pendingQ = null;

        // The things he understands.
        TalkBook.Intent best = null;
        TalkBook.Reply bestReply = null;
        int bestScore = 0;
        for (TalkBook.Intent intent : book.intents) {
            int score = score(intent, text);
            if (score <= bestScore) continue;
            TalkBook.Reply reply = firstReply(c, intent.replies());
            if (reply == null) continue;
            best = intent;
            bestReply = reply;
            bestScore = score;
        }
        if (best != null && bestReply != null) {
            if (best.topic() != null) s.topic = best.topic();
            run(c, best.id(), bestReply);
            return;
        }
        if (words(c, text)) return;
        TalkBook.Reply fallback = firstReply(c, book.fallback);
        if (fallback != null) run(c, "fallback", fallback);
    }

    private static void countTalk(Ctx c) {
        c.rel.talks++;
        if (c.memory.cooldown("talk_trust_" + c.player.getUUID(), c.now, 1200)) c.rel.addTrust(0.5f);
        c.memory.setDirty();
        Progress.grant(c.player, "talk");
        if (c.memory.setFlag("first_talk")) Journal.unlock(c.level.getServer(), "note_steve");
        giveJournal(c);
    }

    private static void giveJournal(Ctx c) {
        if (c.rel.firstTime("journal_given")) {
            if (!c.player.getInventory().add(Journal.journalBook())) c.player.spawnAtLocation(c.level, Journal.journalBook());
            SteveVoice.steve(c.steve, "Here. I can't keep things written down. Maybe you can.");
            Progress.grant(c.player, "journal");
        }
    }

    static int score(TalkBook.Intent intent, String text) {
        int best = 0;
        for (TalkBook.Pat pat : intent.patterns()) {
            if (pat.score() > best && pat.regex().matcher(text).find()) best = pat.score();
        }
        return best;
    }

    private static TalkBook.@Nullable Intent intentById(String id) {
        for (TalkBook.Intent intent : TalkBook.get().intents) if (intent.id().equals(id)) return intent;
        return null;
    }

    private static TalkBook.@Nullable Reply firstReply(Ctx c, List<TalkBook.Reply> replies) {
        for (TalkBook.Reply reply : replies) if (reply.cond().test(c)) return reply;
        return null;
    }

    private static void run(Ctx c, String key, TalkBook.Reply reply) {
        if (reply.special() != null) special(c, reply.special());
        else say(c, key, reply.variants());
        apply(c, reply.effects());
    }

    private static void apply(Ctx c, TalkBook.Effects e) {
        if (e == TalkBook.Effects.NONE) return;
        if (e.trust() != 0) c.rel.addTrust(e.trust());
        if (e.anger() != 0) c.rel.addAnger(e.anger());
        if (e.stability() != 0) c.memory.addStability(e.stability());
        if (e.unlock() != null) Journal.unlock(c.level.getServer(), e.unlock());
        if (e.flag() != null) c.memory.setFlag(e.flag());
        if (e.mark() != null) c.rel.firstTime(e.mark());
        if (e.topic() != null) c.session.topic = e.topic();
        if (e.quiet() > 0) c.session.quietUntil = c.now + e.quiet();
        if (e.action() != null) action(c, e.action());
        if (e.end()) {
            c.steve.stopTalking();
            c.session.lastExchange = c.now - 500;
            c.session.nextInit = Math.max(c.session.nextInit, c.now + 2400);
        }
        if (e.ask() != null) {
            TalkBook.Question q = TalkBook.get().questions.get(e.ask());
            if (q != null) {
                c.session.pendingQ = q.id();
                c.session.askedAt = c.now;
                c.session.lastQ = q.id();
            }
        }
        c.memory.setDirty();
    }

    private static void action(Ctx c, String action) {
        switch (action) {
            case "follow" -> c.steve.orderFollow(c.player);
            case "stay" -> c.steve.orderStay();
            case "free" -> c.steve.orderNothing();
            case "come" -> c.steve.getNavigation().moveTo(c.player, 1.1);
            case "jump" -> c.steve.getJumpControl().jump();
            default -> { }
        }
    }

    // ---- his questions ---------------------------------------------------------------

    private static void ask(Ctx c, TalkBook.Question q) {
        c.session.pendingQ = q.id();
        c.session.askedAt = c.now;
        c.session.lastQ = q.id();
        c.rel.setMark("q_" + q.id(), c.now);
        c.memory.setDirty();
        c.steve.startTalking(c.player);
        say(c, "ask_" + q.id(), q.ask());
    }

    /** Tries to read the message as an answer to what he asked. False if it isn't one. */
    private static boolean answer(Ctx c, String text, String raw) {
        Session s = c.session;
        TalkBook book = TalkBook.get();
        if (s.pendingQ != null && s.pendingQ.equals("define")) return define(c, text, raw);
        TalkBook.Question q = s.pendingQ == null ? null : book.questions.get(s.pendingQ);
        if (q == null) return false;
        boolean question = raw.contains("?") || Words.startsLikeQuestion(text);
        switch (q.type()) {
            case "name" -> {
                String name = Words.nameStatement(text, raw, true);
                if (name != null) {
                    c.rel.notes.put("name", name);
                    say(c, "answer_" + q.id(), q.answer());
                } else if (Words.yesNo(text) == 0 || text.contains(" not telling ") || text.contains(" why ")) {
                    say(c, "other_" + q.id(), q.other());
                } else {
                    return false;
                }
            }
            case "yesno" -> {
                int yn = Words.yesNo(text);
                if (yn == 1) say(c, "yes_" + q.id(), q.yes());
                else if (yn == 0) say(c, "no_" + q.id(), q.no());
                else if (!question && Words.count(text) <= 6) say(c, "other_" + q.id(), q.other());
                else return false;
            }
            case "free" -> {
                if (question) return false;
                c.answer = Words.answerFrom(raw);
                if (c.answer.isEmpty()) return false;
                if (q.save() != null) c.rel.notes.put(q.save(), c.answer);
                say(c, "answer_" + q.id(), q.answer());
            }
            default -> {
                return false;
            }
        }
        s.pendingQ = null;
        apply(c, q.effects());
        return true;
    }

    // ---- words he knows, words he doesn't ---------------------------------------------------

    /** Handles single words: things he remembers from the old days, things you taught him, things he has never heard of. */
    private static boolean words(Ctx c, String text) {
        TalkBook book = TalkBook.get();
        Matcher what = WHAT_IS.matcher(text);
        String asked = what.find() ? Words.singular(what.group(1)) : null;
        // Something someone taught him.
        for (String token : Words.tokens(text)) {
            String w = Words.singular(token);
            String learned = c.memory.learned.get(w);
            if (learned != null) {
                c.word = w;
                int bar = learned.lastIndexOf('|');
                c.def = bar >= 0 ? learned.substring(0, bar) : learned;
                c.teacher = bar >= 0 ? learned.substring(bar + 1) : "Someone";
                if (c.teacher.equals(displayName(c))) c.teacher = "You";
                say(c, "recall", book.unknownWords.get("recall"));
                return true;
            }
        }
        // Something from when he was new.
        for (String token : Words.tokens(text)) {
            String line = book.oldWords.get(Words.singular(token));
            if (line != null) {
                say(c, line);
                return true;
            }
        }
        // Something that came after him, or a word he has never heard.
        String unknown = asked != null && !vocabulary().contains(asked) ? asked : null;
        boolean isNewThing = false;
        if (unknown == null) {
            for (String token : Words.tokens(text)) {
                String w = Words.singular(token);
                if (newThings().containsKey(w) && !book.oldWords.containsKey(w)) {
                    unknown = w;
                    isNewThing = true;
                    break;
                }
            }
        }
        if (unknown == null) {
            // An ordinary word he doesn't know: only things, so only right after "a", "the", "my" and so on.
            List<String> tokens = Words.tokens(text);
            for (int i = 1; i < tokens.size(); i++) {
                String w = Words.singular(tokens.get(i));
                if (!ARTICLES.contains(tokens.get(i - 1))) continue;
                if (w.length() >= 4 && w.chars().allMatch(Character::isLetter) && !vocabulary().contains(w) && !isPlayerName(c, w)
                        && !w.endsWith("ing") && !w.endsWith("ly") && (unknown == null || w.length() > unknown.length())) {
                    unknown = w;
                }
            }
            if (unknown != null && c.random.nextFloat() > 0.6f) unknown = null;
        }
        if (unknown == null) return false;
        c.word = unknown;
        c.session.pendingQ = "define";
        c.session.pendingWord = unknown;
        c.session.askedAt = c.now;
        if (isNewThing && c.random.nextBoolean()) {
            say(c, "{Word}... that must have come after me. What is it?");
        } else {
            say(c, "ask_word", book.unknownWords.get("ask"));
        }
        return true;
    }

    private static boolean define(Ctx c, String text, String raw) {
        String word = c.session.pendingWord;
        c.session.pendingQ = null;
        if (word == null) return false;
        c.word = word;
        if (Words.yesNo(text) == 0 && Words.count(text) <= 3 || text.contains(" do not know ") || text.contains(" never mind ")) {
            say(c, "refused", TalkBook.get().unknownWords.get("refused"));
            return true;
        }
        if (raw.contains("?")) return false;
        String def = Words.definitionFrom(raw, word);
        if (def.isEmpty()) return false;
        c.def = def;
        if (c.memory.learned.size() < 80) c.memory.learned.put(word, def + "|" + displayName(c));
        c.rel.addTrust(1f);
        c.memory.setDirty();
        say(c, "learned", TalkBook.get().unknownWords.get("learned"));
        Fx.memoryDust(c.steve, 6);
        return true;
    }

    private static final Set<String> ARTICLES = Set.of("a", "an", "the", "my", "your", "some", "this", "that", "his", "her", "our", "their");

    private static final Pattern WHAT_IS = Pattern.compile(
            " (?:what is|what are|whats|do you know what|do you know|have you seen|have you heard of|ever seen) (?:a |an |the |some )?([a-z]+) ");

    private static boolean isPlayerName(Ctx c, String word) {
        for (ServerPlayer p : c.level.getServer().getPlayerList().getPlayers()) {
            if (p.getGameProfile().name().equalsIgnoreCase(word)) return true;
            String name = c.memory.relationship(p.getUUID()).note("name");
            if (name != null && name.equalsIgnoreCase(word)) return true;
        }
        return false;
    }

    /** Words he understands: everything in his conversation file, the words he grew up with, and plain English. */
    private static Set<String> vocabulary() {
        if (vocabulary == null) {
            Set<String> words = new HashSet<>(Words.COMMON);
            TalkBook book = TalkBook.get();
            for (TalkBook.Intent intent : book.intents) {
                for (TalkBook.Pat pat : intent.patterns()) {
                    for (String w : pat.regex().pattern().replaceAll("[^a-z|]", " ").split("[ |]+")) if (!w.isEmpty()) words.add(w);
                }
            }
            words.addAll(book.oldWords.keySet());
            vocabulary = words;
        }
        return vocabulary;
    }

    /** Names of the game's things he never saw: every block, item and creature, minus plain English words. */
    private static Map<String, String> newThings() {
        if (newThings == null) {
            Map<String, String> things = new HashMap<>();
            List<Identifier> ids = new ArrayList<>();
            BuiltInRegistries.ITEM.keySet().forEach(ids::add);
            BuiltInRegistries.ENTITY_TYPE.keySet().forEach(ids::add);
            for (Identifier id : ids) {
                for (String part : id.getPath().split("_")) {
                    if (part.length() >= 4 && !Words.COMMON.contains(part)) things.putIfAbsent(part, id.getPath());
                }
            }
            newThings = things;
        }
        return newThings;
    }

    // ---- special answers -----------------------------------------------------------------

    private static void special(Ctx c, String special) {
        switch (special) {
            case "story" -> {
                if (!tellStory(c, true)) say(c, "story_none", List.of(List.of("I've told you everything I remember. That's the truth.",
                        "Ask me again after something happens. Things keep happening.")));
            }
            case "more" -> more(c);
            case "me_too" -> {
                TalkBook.Question q = c.session.lastQ == null ? null : TalkBook.get().questions.get(c.session.lastQ);
                if (q != null && !q.me().isEmpty()) say(c, "me_" + q.id(), q.me());
                else say(c, "me_none", List.of(List.of("Me? I'm alright. I'm always alright. Mostly."), List.of("Same as always. Here.")));
            }
            case "doing" -> {
                if (c.player.getUUID().equals(c.steve.followingId())) say(c, "Following you. You walk fast, you know.");
                else if (c.steve.isStaying()) say(c, "Waiting. You told me to wait. I'm good at waiting.");
                else if (c.memory.habit("chop_wood") >= 10) say(c, "Practising. Trees. I'm getting better. The trees aren't.");
                else say(c, "doing", List.of(List.of("Watching. The clouds. You. The edges."), List.of("Counting chickens. One is missing."),
                            List.of("Standing very still. If I stand still, the day lasts longer.")));
            }
            case "time" -> {
                long day = c.level.getOverworldClockTime() / 24000L + 1;
                long t = c.level.getOverworldClockTime() % 24000L;
                String part = t < 1000 ? "early morning" : t < 6000 ? "morning" : t < 11500 ? "afternoon" : t < 13000 ? "evening" : "night";
                say(c, "Day " + day + ". It's " + part + "." + (part.equals("evening") ? " Get inside soon." : part.equals("night") ? " Stay near me." : ""));
            }
            case "home" -> {
                BlockPos home = StoryDirector.homeOf(c.player, c.level);
                if (home == null) {
                    say(c, "You haven't slept anywhere yet. Sleep in a bed and I'll remember where it is.");
                } else {
                    double dx = home.getX() + 0.5 - c.player.getX();
                    double dz = home.getZ() + 0.5 - c.player.getZ();
                    int dist = (int) Math.sqrt(dx * dx + dz * dz);
                    if (dist < 8) say(c, "You're home. This is it. Look around.");
                    else say(c, "Your bed is " + direction(dx, dz) + " of here. About " + dist + " blocks. I'd go before dark.");
                }
            }
            case "learned" -> {
                List<String> mine = new ArrayList<>();
                String me = displayName(c);
                for (Map.Entry<String, String> e : c.memory.learned.entrySet()) if (e.getValue().endsWith("|" + me)) mine.add(e.getKey());
                if (c.memory.learned.isEmpty()) say(c, "Nothing yet. Tell me a word I don't know. I'll ask what it means.");
                else if (mine.isEmpty()) say(c, "Other people taught me " + c.memory.learned.size() + " words. You haven't taught me any yet.");
                else say(c, "You taught me " + String.join(", ", mine.subList(0, Math.min(5, mine.size()))) + ". I kept all of them.");
            }
            case "give" -> {
                if (c.rel.trust >= 45f && c.memory.cooldown("gift_" + c.player.getUUID(), c.now, 24000)) {
                    ItemStack rose = new ItemStack(Items.POPPY);
                    if (!c.player.getInventory().add(rose)) c.player.spawnAtLocation(c.level, rose);
                    say(c, "I don't have much. Here. It's a rose. They were called roses, once.");
                } else {
                    say(c, "give_no", List.of(List.of("I don't have anything. I'm sorry. I'll find something."),
                            List.of("I only have the clothes I came with. And those were never really mine.")));
                }
            }
            default -> say(c, Dialogue.answer(c.steve, c.player, special));
        }
    }

    private static String direction(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        int index = (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
        return names[index];
    }

    /** "Why?", "tell me more": the next thing he can say about whatever you were just talking about. */
    private static void more(Ctx c) {
        String topic = c.session.topic;
        List<List<String>> lines = topic == null ? null : TalkBook.get().topics.get(topic);
        if (lines == null || lines.isEmpty()) {
            say(c, "more_none", List.of(List.of("Why what? Sorry. I lost the start of that."), List.of("I don't know. I just know it's true."),
                    List.of("That's all I've got. I'm not hiding anything. Probably.")));
            return;
        }
        int next = (int) c.rel.mark("topic_" + topic);
        if (next >= lines.size()) {
            say(c, "That's all I remember about that. Maybe more will come back.");
            c.rel.setMark("topic_" + topic, 0);
            return;
        }
        c.rel.setMark("topic_" + topic, next + 1);
        speak(c, lines.get(next));
    }

    private static boolean tellStory(Ctx c, boolean askedFor) {
        List<TalkBook.Story> ready = new ArrayList<>();
        for (TalkBook.Story story : TalkBook.get().stories) {
            if (!c.rel.hasMark("s_" + story.id()) && story.cond().test(c)) ready.add(story);
        }
        if (ready.isEmpty()) return false;
        TalkBook.Story story = askedFor ? ready.get(c.random.nextInt(ready.size())) : ready.get(0);
        c.rel.setMark("s_" + story.id(), c.now);
        c.memory.setDirty();
        speak(c, story.lines());
        return true;
    }

    // ---- him starting it --------------------------------------------------------------------

    private static void initiateTick(ServerLevel level, long now) {
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve == null || !steve.isAlive()) return;
        SteveMemory memory = SteveMemory.get(level.getServer());
        boolean busy = steve.inEpisode() || steve.getTarget() != null || SteveVoice.isSpeaking(steve);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive() || player.distanceToSqr(steve) > 9 * 9) continue;
            Session s = session(player);
            boolean wasAway = now - s.lastNear > 6000;
            s.lastNear = now;
            if (busy || s.quietUntil > now || !steve.hasLineOfSight(player)) continue;
            Ctx c = new Ctx(player, steve, level, memory);
            if (c.rel.stage().atLeast(AngerStage.PROVOCATION)) continue;
            if (wasAway) {
                greet(c);
                return;
            }
            if (now < s.nextInit) continue;
            if (now - s.lastHeard < 400) {
                // They're in the middle of telling him something. Let them.
                s.nextInit = now + 200;
                continue;
            }
            initiate(c);
            int base = c.rel.trust >= 60f ? 1800 : c.rel.trust >= 35f ? 2400 : 3000;
            s.nextInit = now + base + c.random.nextInt(base / 2);
            return;
        }
    }

    private static void greet(Ctx c) {
        c.steve.startTalking(c.player);
        c.session.lastExchange = c.now;
        if (c.rel.firstTime("met")) {
            say(c, "Oh. Hello. You can see me? Most of them just walk through.");
            c.player.sendOverlayMessage(Component.literal("Type in chat to talk to Steve.").withStyle(ChatFormatting.GRAY));
            c.session.nextInit = c.now + 160;
            c.memory.setDirty();
            return;
        }
        String line = Dialogue.greeting(c.level, c.steve, c.player, c.memory, c.rel);
        say(c, line);
        c.session.nextInit = Math.max(c.session.nextInit, c.now + 500);
    }

    private static void initiate(Ctx c) {
        TalkBook book = TalkBook.get();
        if (c.rel.note("name") == null && !c.rel.hasMark("q_q_name")) {
            ask(c, book.questions.get("q_name"));
            return;
        }
        float roll = c.random.nextFloat();
        if (roll < 0.55f) {
            List<TalkBook.Question> ready = new ArrayList<>();
            for (TalkBook.Question q : book.questions.values()) {
                if (!c.rel.hasMark("q_" + q.id()) && q.cond().test(c)) ready.add(q);
                if (ready.size() >= 3) break;
            }
            if (!ready.isEmpty()) {
                ask(c, ready.get(c.random.nextInt(ready.size())));
                return;
            }
        }
        if (roll < 0.85f && tellStory(c, false)) return;
        Reactions.idleThought(c.steve, c.memory, c.level);
    }

    // ---- right-clicking him, right-clicking Herobrine --------------------------------------------

    public static void interact(OriginalSteve steve, ServerPlayer player) {
        if (!(steve.level() instanceof ServerLevel level)) return;
        Ctx c = new Ctx(player, steve, level, SteveMemory.get(level.getServer()));
        steve.startTalking(player);
        if (SteveVoice.isSpeaking(steve)) return;
        countTalk(c);
        Session s = c.session;
        if (c.rel.stage().atLeast(AngerStage.PROVOCATION)) {
            say(c, "cold", List.of(List.of("Leave me alone."), List.of("I don't want to talk to you."), List.of("You know what you did.")));
            return;
        }
        if (c.rel.firstTime("hint_talk")) {
            player.sendOverlayMessage(Component.literal("Type in chat to talk to Steve.").withStyle(ChatFormatting.GRAY));
        }
        TalkBook.Question pending = s.pendingQ == null ? null : TalkBook.get().questions.get(s.pendingQ);
        if (pending != null && c.now - s.askedAt < 1600) {
            s.askedAt = c.now;
            say(c, "ask_" + pending.id(), pending.ask());
            return;
        }
        s.lastExchange = c.now;
        if (c.rel.firstTime("met")) {
            say(c, "Oh. Hello. You can see me? Most of them just walk through.");
            s.nextInit = c.now + 100;
            return;
        }
        String greeting = Dialogue.greeting(level, steve, player, c.memory, c.rel);
        say(c, greeting);
        // He has things to say, if you came over to listen.
        s.nextInit = Math.min(s.nextInit, c.now + 90);
    }

    public static void herobrineInteract(Herobrine herobrine, ServerPlayer player) {
        Session s = session(player);
        long now = player.level().getGameTime();
        s.herobrineUntil = now + 400;
        Herobrine.whisper(player, "ask.");
        player.sendOverlayMessage(Component.literal("He is waiting. Type your question.").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static String herobrineTopic(String text) {
        if (text.contains(" want ") || text.contains(" need ")) return "want";
        if (text.contains(" what are you ") || text.contains(" who are you ") || text.contains(" what is your ") || text.contains(" are you ")) return "what";
        if (text.contains(" leave ") || text.contains(" stop ") || text.contains(" go away ") || text.contains(" alone ")) return "leave";
        if (text.contains(" why ")) return "why";
        return "other";
    }

    // ---- saying things ----------------------------------------------------------------------

    private static void say(Ctx c, String line) {
        speak(c, List.of(line));
    }

    /** Picks one of the variants, not the same one as last time if there's a choice, and says it. */
    private static void say(Ctx c, String key, @Nullable List<List<String>> variants) {
        if (variants == null || variants.isEmpty()) return;
        int index = c.random.nextInt(variants.size());
        Integer last = c.session.lastVariant.get(key);
        if (variants.size() > 1 && last != null && last == index) index = (index + 1) % variants.size();
        c.session.lastVariant.put(key, index);
        speak(c, variants.get(index));
    }

    private static void speak(Ctx c, List<String> lines) {
        for (String line : lines) SteveVoice.steve(c.steve, fill(c, line), voiceText(line));
    }

    /**
     * What the recording of a line says. Recordings never say a name (he can't know it ahead of time),
     * and lines built from something you told him have no recording at all.
     */
    static @Nullable String voiceText(String line) {
        if (line.contains("{word}") || line.contains("{Word}") || line.contains("{def}") || line.contains("{answer}") || line.contains("{teacher}")) return null;
        line = line.replace(", {name}", "").replace(" {name}", "").replace("{name}, ", "").replace("{name}. ", "").replace("{name}", "you");
        return capitalize(line.replace(" ,", ",").replace("  ", " ").trim());
    }

    static String displayName(Ctx c) {
        String name = c.rel.note("name");
        return name != null ? name : c.player.getGameProfile().name();
    }

    /** Fills in {name}, {word}, {def}, {answer}, {teacher}. Without a known name, "{name}" and the comma before it disappear. */
    static String fill(Ctx c, String line) {
        String name = c.rel.note("name");
        if (name == null) {
            line = line.replace(", {name}", "").replace(" {name}", "").replace("{name}, ", "").replace("{name}. ", "").replace("{name}", "you");
        } else {
            line = line.replace("{name}", name);
        }
        if (c.word != null) {
            line = line.replace("{Word}", capitalize(c.word)).replace("{word}", c.word);
        }
        if (c.def != null) {
            line = line.replace(". {def}", ". " + capitalize(c.def)).replace("{def}", c.def);
        }
        if (c.answer != null) line = line.replace("{answer}", c.answer);
        if (c.teacher != null) line = line.replace("{teacher}", c.teacher);
        line = line.replace(" ,", ",").replace("  ", " ").trim();
        return capitalize(line);
    }

    static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** For the in-game test and /leftbehind debug: what he would make of a message, said aloud. */
    public static String normalizeForDebug(String raw) {
        return Words.normalize(raw).trim().toLowerCase(Locale.ROOT);
    }
}
