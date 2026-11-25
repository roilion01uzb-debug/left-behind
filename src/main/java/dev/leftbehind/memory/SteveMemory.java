package dev.leftbehind.memory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.leftbehind.LeftBehind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Everything Original Steve remembers, and where the story has got to.
 * Saved with the world, so it survives server restarts.
 */
public final class SteveMemory extends SavedData {
    public static final Codec<SteveMemory> CODEC = RecordCodecBuilder.create(i -> i.group(
            // Saved under its old name ("memory stability" before 0.5), so older worlds keep their Steve as he was.
            Codec.FLOAT.optionalFieldOf("stability", 100f).forGetter(m -> m.sanity),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Relationship.CODEC).optionalFieldOf("relationships", Map.of()).forGetter(m -> m.relationships),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("habits", Map.of()).forGetter(m -> m.habits),
            UUIDUtil.STRING_CODEC.optionalFieldOf("steve").forGetter(m -> Optional.ofNullable(m.steveId)),
            Codec.BOOL.optionalFieldOf("steve_dead", false).forGetter(m -> m.steveDead),
            Codec.INT.optionalFieldOf("breaches", 0).forGetter(m -> m.breaches),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("counters", Map.of()).forGetter(m -> m.counters),
            Codec.STRING.listOf().optionalFieldOf("flags", List.of()).forGetter(m -> List.copyOf(m.flags)),
            Codec.STRING.listOf().optionalFieldOf("journal", List.of()).forGetter(m -> m.journal),
            Codec.STRING.listOf().optionalFieldOf("night_log", List.of()).forGetter(m -> m.nightLog),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("learned", Map.of()).forGetter(m -> m.learned),
            Codec.STRING.listOf().optionalFieldOf("story_log", List.of()).forGetter(m -> m.storyLog)
    ).apply(i, SteveMemory::new));

    public static final SavedDataType<SteveMemory> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "steve_memory"), SteveMemory::new, CODEC, null);

    /** 100 = himself. Low values = memory intrusions, seeing Herobrine where he isn't, episodes. */
    /** Steve's sanity, 0 to 100. Never shown as a bar: you read it from how he acts. See {@link SanityStage}. */
    public float sanity;
    public final Map<UUID, Relationship> relationships;
    /** Things he has watched players do, and how many times. */
    public final Map<String, Integer> habits;
    public UUID steveId;
    public boolean steveDead;
    public int breaches;
    /** Story clocks and numbers: the act, cooldowns, when the episode ends. */
    public final Map<String, Long> counters;
    /** One-time story events that have happened. */
    public final Set<String> flags;
    /** Unlocked journal entries, in the order they were found. */
    public final List<String> journal;
    /** What Steve did during his last episode. He doesn't remember it. The world does. */
    public final List<String> nightLog;
    /** Words players have taught him, and what they told him they mean. */
    public final Map<String, String> learned;
    /** What has happened in the story, in order: "day|id". The journal's "Where things stand" page reads it. */
    public final List<String> storyLog;

    public SteveMemory() {
        this(100f, Map.of(), Map.of(), Optional.empty(), false, 0, Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }

    private SteveMemory(float sanity, Map<UUID, Relationship> relationships, Map<String, Integer> habits,
                        Optional<UUID> steveId, boolean steveDead, int breaches, Map<String, Long> counters,
                        List<String> flags, List<String> journal, List<String> nightLog, Map<String, String> learned, List<String> storyLog) {
        this.sanity = sanity;
        this.relationships = new HashMap<>(relationships);
        this.habits = new HashMap<>(habits);
        this.steveId = steveId.orElse(null);
        this.steveDead = steveDead;
        this.breaches = breaches;
        this.counters = new HashMap<>(counters);
        this.flags = new LinkedHashSet<>(flags);
        this.journal = new ArrayList<>(journal);
        this.nightLog = new ArrayList<>(nightLog);
        this.learned = new HashMap<>(learned);
        this.storyLog = new ArrayList<>(storyLog);
    }

    public static SteveMemory get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    // ---- relationships ----------------------------------------------------

    public Relationship relationship(UUID player) {
        setDirty();
        return relationships.computeIfAbsent(player, id -> new Relationship());
    }

    public float trust(UUID player) {
        Relationship r = relationships.get(player);
        return r == null ? 20f : r.trust;
    }

    public AngerStage anger(UUID player) {
        Relationship r = relationships.get(player);
        return r == null ? AngerStage.CALM : r.stage();
    }

    public float highestTrust() {
        float best = 0f;
        for (Relationship r : relationships.values()) best = Math.max(best, r.trust);
        return best;
    }

    // ---- habits -----------------------------------------------------------

    public int habit(String name) {
        return habits.getOrDefault(name, 0);
    }

    public java.util.Set<String> habitKeys() {
        return habits.keySet();
    }

    public int observe(String habit) {
        setDirty();
        return habits.merge(habit, 1, Integer::sum);
    }

    // ---- sanity and mood -----------------------------------------------

    public void addSanity(float amount) {
        sanity = Mth.clamp(sanity + amount, 0f, 100f);
        setDirty();
    }

    public SanityStage sanityStage() {
        return SanityStage.of(sanity);
    }

    public Mood mood() {
        return Mood.byId(counter("mood"));
    }

    public void setMood(Mood mood) {
        setCounter("mood", mood.ordinal());
    }

    // ---- story state ------------------------------------------------------

    public long counter(String key) {
        return counters.getOrDefault(key, 0L);
    }

    public void setCounter(String key, long value) {
        counters.put(key, value);
        setDirty();
    }

    public long addCounter(String key, long amount) {
        setDirty();
        return counters.merge(key, amount, Long::sum);
    }

    public boolean flag(String key) {
        return flags.contains(key);
    }

    /** Sets a flag. Returns true if it was not set before. */
    public boolean setFlag(String key) {
        boolean added = flags.add(key);
        if (added) setDirty();
        return added;
    }

    public void clearFlag(String key) {
        if (flags.remove(key)) setDirty();
    }

    /** True if enough time has passed since this key was last used; if so, starts the cooldown again. */
    public boolean cooldown(String key, long now, long ticks) {
        long next = counter("cd_" + key);
        if (now < next) return false;
        setCounter("cd_" + key, now + ticks);
        return true;
    }

    public int act() {
        return (int) Mth.clamp(counter("act"), 1, 4);
    }

    public int count(String prefix) {
        int n = 0;
        for (String id : journal) if (id.startsWith(prefix)) n++;
        return n;
    }
}
