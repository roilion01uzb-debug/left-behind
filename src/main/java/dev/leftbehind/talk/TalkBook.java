package dev.leftbehind.talk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leftbehind.LeftBehind;
import dev.leftbehind.story.StoryDirector;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Everything Steve can understand and say, read from data/leftbehind/talk/steve.json inside the mod.
 *
 * <p>Conditions ("if"): trustMin, trustMax, actMin, actMax, stabMin, stabMax, breachesMin, night, rain, known (he knows
 * your name), hunted, flag / noFlag (story flags), unlocked / locked (journal entries), mark / noMark, chance.
 *
 * <p>Effects ("do"): trust, anger, stability, unlock, flag, mark, action (follow, stay, free, come, jump), ask (a question id),
 * topic, quiet (ticks he stays quiet), end (the conversation is over).
 */
public final class TalkBook {
    public record Cond(float trustMin, float trustMax, int actMin, int actMax, float stabMin, float stabMax, int breachesMin,
                       @Nullable Boolean night, @Nullable Boolean rain, @Nullable Boolean known, @Nullable Boolean hunted,
                       @Nullable String flag, @Nullable String noFlag, @Nullable String unlocked, @Nullable String locked,
                       @Nullable String mark, @Nullable String noMark, float chance) {
        static final Cond ALWAYS = new Cond(-1, 999, 0, 99, -1, 999, 0, null, null, null, null, null, null, null, null, null, null, 1f);

        public boolean test(Talk.Ctx c) {
            if (c.rel.trust < trustMin || c.rel.trust > trustMax) return false;
            int act = c.memory.act();
            if (act < actMin || act > actMax) return false;
            if (c.memory.stability < stabMin || c.memory.stability > stabMax) return false;
            if (c.memory.breaches < breachesMin) return false;
            if (night != null && c.level.isDarkOutside() != night) return false;
            if (rain != null && c.level.isRaining() != rain) return false;
            if (known != null && (c.rel.note("name") != null) != known) return false;
            if (hunted != null && StoryDirector.isHunted(c.player) != hunted) return false;
            if (flag != null && !c.memory.flag(flag)) return false;
            if (noFlag != null && c.memory.flag(noFlag)) return false;
            if (unlocked != null && !c.memory.journal.contains(unlocked)) return false;
            if (locked != null && c.memory.journal.contains(locked)) return false;
            if (mark != null && !c.rel.hasMark(mark)) return false;
            if (noMark != null && c.rel.hasMark(noMark)) return false;
            return chance >= 1f || c.random.nextFloat() < chance;
        }
    }

    public record Effects(float trust, float anger, float stability, @Nullable String unlock, @Nullable String flag, @Nullable String mark,
                          @Nullable String action, @Nullable String ask, @Nullable String topic, int quiet, boolean end) {
        static final Effects NONE = new Effects(0, 0, 0, null, null, null, null, null, null, 0, false);
    }

    /** One way of answering: when it applies, what he might say (each variant is one or more lines), and what it does. */
    public record Reply(Cond cond, List<List<String>> variants, Effects effects, @Nullable String special) {}

    public record Pat(Pattern regex, int score) {}

    public record Intent(String id, List<Pat> patterns, @Nullable String topic, List<Reply> replies) {}

    public record Question(String id, String type, Cond cond, List<List<String>> ask, List<List<String>> yes, List<List<String>> no,
                           List<List<String>> other, List<List<String>> answer, List<List<String>> me, @Nullable String save, Effects effects) {}

    public record Story(String id, Cond cond, List<String> lines) {}

    public final List<Intent> intents = new ArrayList<>();
    public final Map<String, Question> questions = new LinkedHashMap<>();
    public final List<Story> stories = new ArrayList<>();
    public final Map<String, List<List<String>>> topics = new HashMap<>();
    public final List<Reply> fallback = new ArrayList<>();
    public final Map<String, String> oldWords = new HashMap<>();
    public final Map<String, List<List<String>>> unknownWords = new HashMap<>();

    private static @Nullable TalkBook instance;

    public static TalkBook get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static TalkBook load() {
        TalkBook book = new TalkBook();
        try (InputStream in = TalkBook.class.getResourceAsStream("/data/leftbehind/talk/steve.json")) {
            if (in == null) throw new IllegalStateException("steve.json is missing from the mod");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("intents")) {
                JsonObject o = e.getAsJsonObject();
                List<Pat> pats = new ArrayList<>();
                for (JsonElement p : o.getAsJsonArray("match")) pats.add(pattern(p.getAsString()));
                book.intents.add(new Intent(o.get("id").getAsString(), pats, str(o, "topic"), replies(o.getAsJsonArray("replies"))));
            }
            for (JsonElement e : root.getAsJsonArray("questions")) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                book.questions.put(id, new Question(id, o.get("type").getAsString(), cond(o.getAsJsonObject("if")),
                        variants(o.get("ask")), variants(o.get("yes")), variants(o.get("no")), variants(o.get("other")),
                        variants(o.get("answer")), variants(o.get("me")), str(o, "save"), effects(o.getAsJsonObject("do"))));
            }
            for (JsonElement e : root.getAsJsonArray("stories")) {
                JsonObject o = e.getAsJsonObject();
                List<String> lines = new ArrayList<>();
                for (JsonElement l : o.getAsJsonArray("say")) lines.add(l.getAsString());
                book.stories.add(new Story(o.get("id").getAsString(), cond(o.getAsJsonObject("if")), lines));
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("topics").entrySet()) {
                book.topics.put(e.getKey(), variants(e.getValue()));
            }
            book.fallback.addAll(replies(root.getAsJsonArray("fallback")));
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("old_words").entrySet()) {
                book.oldWords.put(e.getKey(), e.getValue().getAsString());
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("unknown_words").entrySet()) {
                book.unknownWords.put(e.getKey(), variants(e.getValue()));
            }
        } catch (Exception e) {
            LeftBehind.LOGGER.error("Could not read Steve's conversation file", e);
        }
        LeftBehind.LOGGER.info("Steve knows {} things to talk about, {} questions and {} stories.",
                book.intents.size(), book.questions.size(), book.stories.size());
        return book;
    }

    /** "who * you" matches "who are you"; "hi|hello" matches either; "=why" must be the whole message. */
    static Pat pattern(String text) {
        boolean whole = text.startsWith("=");
        if (whole) text = text.substring(1);
        StringBuilder regex = new StringBuilder(whole ? "^ " : " ");
        int score = 0;
        String[] tokens = text.trim().split(" +");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.equals("*")) {
                regex.append("(?:\\S+ )*?");
                continue;
            }
            regex.append(token.contains("|") ? "(?:" + token + ")" : Pattern.quote(token)).append(' ');
            score++;
        }
        if (whole) {
            regex.append('$');
            score += 2;
        }
        return new Pat(Pattern.compile(regex.toString()), score);
    }

    private static List<Reply> replies(@Nullable JsonArray array) {
        List<Reply> out = new ArrayList<>();
        if (array == null) return out;
        for (JsonElement e : array) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Reply(cond(o.getAsJsonObject("if")), variants(o.get("say")), effects(o.getAsJsonObject("do")), str(o, "special")));
        }
        return out;
    }

    /** A list where each entry is either one line, or a list of lines said one after another. */
    private static List<List<String>> variants(@Nullable JsonElement element) {
        List<List<String>> out = new ArrayList<>();
        if (element == null) return out;
        for (JsonElement e : element.getAsJsonArray()) {
            List<String> lines = new ArrayList<>();
            if (e.isJsonArray()) {
                for (JsonElement l : e.getAsJsonArray()) lines.add(l.getAsString());
            } else {
                lines.add(e.getAsString());
            }
            out.add(lines);
        }
        return out;
    }

    private static Cond cond(@Nullable JsonObject o) {
        if (o == null) return Cond.ALWAYS;
        return new Cond(f(o, "trustMin", -1), f(o, "trustMax", 999), (int) f(o, "actMin", 0), (int) f(o, "actMax", 99),
                f(o, "stabMin", -1), f(o, "stabMax", 999), (int) f(o, "breachesMin", 0),
                bool(o, "night"), bool(o, "rain"), bool(o, "known"), bool(o, "hunted"),
                str(o, "flag"), str(o, "noFlag"), str(o, "unlocked"), str(o, "locked"), str(o, "mark"), str(o, "noMark"),
                f(o, "chance", 1f));
    }

    private static Effects effects(@Nullable JsonObject o) {
        if (o == null) return Effects.NONE;
        return new Effects(f(o, "trust", 0), f(o, "anger", 0), f(o, "stability", 0), str(o, "unlock"), str(o, "flag"), str(o, "mark"),
                str(o, "action"), str(o, "ask"), str(o, "topic"), (int) f(o, "quiet", 0), o.has("end") && o.get("end").getAsBoolean());
    }

    private static float f(JsonObject o, String key, float fallback) {
        return o.has(key) ? o.get(key).getAsFloat() : fallback;
    }

    private static @Nullable Boolean bool(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsBoolean() : null;
    }

    private static @Nullable String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : null;
    }
}
