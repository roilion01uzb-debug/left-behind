package dev.leftbehind.talk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leftbehind.LeftBehind;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Which lines have a recorded voice, and how long each recording is.
 * The recordings are made by tools/make_voice.py and found by the hash of the line's exact text.
 */
public final class VoiceIndex {
    public static final String STEVE = "steve";
    public static final String HEROBRINE = "herobrine";

    private static @Nullable Map<String, Map<String, Integer>> index;

    private VoiceIndex() {}

    /** FNV-1a over the UTF-8 bytes, as eight hex digits. Must match fnv() in tools/make_voice.py. */
    public static String hash(String text) {
        int h = 0x811c9dc5;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x01000193;
        }
        return String.format("%08x", h);
    }

    private static Map<String, Map<String, Integer>> index() {
        if (index != null) return index;
        Map<String, Map<String, Integer>> loaded = new HashMap<>();
        try (InputStream in = VoiceIndex.class.getResourceAsStream("/data/leftbehind/voice/index.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> speaker : root.entrySet()) {
                    Map<String, Integer> lines = new HashMap<>();
                    for (Map.Entry<String, JsonElement> line : speaker.getValue().getAsJsonObject().entrySet()) {
                        lines.put(line.getKey(), line.getValue().getAsInt());
                    }
                    loaded.put(speaker.getKey(), lines);
                }
            }
            LeftBehind.LOGGER.info("Steve has {} recorded lines", loaded.getOrDefault(STEVE, Map.of()).size());
        } catch (Exception e) {
            LeftBehind.LOGGER.warn("Couldn't read the voice index", e);
        }
        index = loaded;
        return loaded;
    }

    /** The sound name ("steve.1a2b3c4d") for this line, or "" if it was never recorded. */
    public static String sound(String speaker, @Nullable String text) {
        if (text == null || text.isBlank()) return "";
        String h = hash(text);
        Map<String, Integer> lines = index().get(speaker);
        return lines != null && lines.containsKey(h) ? speaker + "." + h : "";
    }

    /** Length of a recording in ticks, or 0. */
    public static int ticks(String sound) {
        int dot = sound.indexOf('.');
        if (dot < 0) return 0;
        Map<String, Integer> lines = index().get(sound.substring(0, dot));
        return lines == null ? 0 : lines.getOrDefault(sound.substring(dot + 1), 0);
    }
}
