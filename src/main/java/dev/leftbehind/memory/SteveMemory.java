package dev.leftbehind.memory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.leftbehind.LeftBehind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Everything Original Steve remembers. Saved with the world, so it survives server restarts.
 */
public final class SteveMemory extends SavedData {
    public static final Codec<SteveMemory> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.optionalFieldOf("stability", 100f).forGetter(m -> m.stability),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Relationship.CODEC).optionalFieldOf("relationships", Map.of()).forGetter(m -> m.relationships),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("habits", Map.of()).forGetter(m -> m.habits),
            UUIDUtil.STRING_CODEC.optionalFieldOf("steve").forGetter(m -> Optional.ofNullable(m.steveId)),
            Codec.BOOL.optionalFieldOf("steve_dead", false).forGetter(m -> m.steveDead),
            Codec.INT.optionalFieldOf("breaches", 0).forGetter(m -> m.breaches),
            Codec.BOOL.optionalFieldOf("lost_night", false).forGetter(m -> m.lostNight),
            Codec.LONG.optionalFieldOf("episode_end", 0L).forGetter(m -> m.episodeEnd),
            Codec.STRING.listOf().optionalFieldOf("night_log", List.of()).forGetter(m -> m.nightLog),
            Codec.LONG.optionalFieldOf("next_herobrine_check", 0L).forGetter(m -> m.nextHerobrineCheck)
    ).apply(i, SteveMemory::new));

    public static final SavedDataType<SteveMemory> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "steve_memory"), SteveMemory::new, CODEC, null);

    /** 100 = himself. Low values = memory intrusions, seeing Herobrine where he isn't, episodes. */
    public float stability;
    public final Map<UUID, Relationship> relationships;
    /** Things he has watched players do, and how many times. */
    public final Map<String, Integer> habits;
    public UUID steveId;
    public boolean steveDead;
    public int breaches;
    public boolean lostNight;
    public long episodeEnd;
    /** What Steve did during the last lost night. He doesn't remember it. The world does. */
    public final List<String> nightLog;
    public long nextHerobrineCheck;

    public SteveMemory() {
        this(100f, Map.of(), Map.of(), Optional.empty(), false, 0, false, 0L, List.of(), 0L);
    }

    private SteveMemory(float stability, Map<UUID, Relationship> relationships, Map<String, Integer> habits,
                        Optional<UUID> steveId, boolean steveDead, int breaches, boolean lostNight, long episodeEnd,
                        List<String> nightLog, long nextHerobrineCheck) {
        this.stability = stability;
        this.relationships = new HashMap<>(relationships);
        this.habits = new HashMap<>(habits);
        this.steveId = steveId.orElse(null);
        this.steveDead = steveDead;
        this.breaches = breaches;
        this.lostNight = lostNight;
        this.episodeEnd = episodeEnd;
        this.nightLog = new ArrayList<>(nightLog);
        this.nextHerobrineCheck = nextHerobrineCheck;
    }

    public static SteveMemory get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Relationship relationship(UUID player) {
        setDirty();
        return relationships.computeIfAbsent(player, id -> new Relationship());
    }

    public int habit(String name) {
        return habits.getOrDefault(name, 0);
    }

    public void observe(String habit) {
        habits.merge(habit, 1, Integer::sum);
        setDirty();
    }

    public void addStability(float amount) {
        stability = Mth.clamp(stability + amount, 0f, 100f);
        setDirty();
    }
}
