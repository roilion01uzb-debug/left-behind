package dev.leftbehind.story;

import dev.leftbehind.fx.Fx;
import dev.leftbehind.registry.ModSounds;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * How aware Herobrine is of each player, from 0 to 100.
 * It climbs while you look at him, stand near him or run near him. It drains when you leave him alone.
 * At 100 he comes for you.
 */
public final class Sense {
    private static final Map<UUID, Float> LEVELS = new HashMap<>();
    private static final Map<UUID, Float> LAST_SENT = new HashMap<>();
    private static final Map<UUID, Long> LAST_GAIN = new HashMap<>();

    private Sense() {}

    public static float get(ServerPlayer player) {
        return LEVELS.getOrDefault(player.getUUID(), 0f);
    }

    public static void set(ServerPlayer player, float value) {
        LEVELS.put(player.getUUID(), Mth.clamp(value, 0f, 100f));
    }

    public static void add(ServerPlayer player, float amount) {
        set(player, get(player) + amount);
        LAST_GAIN.put(player.getUUID(), player.level().getGameTime());
    }

    public static void clear() {
        LEVELS.clear();
        LAST_SENT.clear();
        LAST_GAIN.clear();
    }

    /** Runs four times a second. Drains the meter, drives the vignette and the heartbeat. */
    public static void tick(MinecraftServer server, long time) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            float level = LEVELS.getOrDefault(id, 0f);
            if (level <= 0f && LAST_SENT.getOrDefault(id, 0f) <= 0f) continue;
            // It only drains once he has stopped paying attention for a moment.
            if (time - LAST_GAIN.getOrDefault(id, 0L) > 30 && !StoryDirector.isHunted(player)) {
                level = Math.max(0f, level - 0.9f);
                LEVELS.put(id, level);
            }
            float last = LAST_SENT.getOrDefault(id, 0f);
            if (Math.abs(level - last) >= 1.5f || (level == 0f && last != 0f)) {
                Fx.dread(player, level / 100f);
                LAST_SENT.put(id, level);
            }
            if (level >= 35f) {
                // The closer he is to noticing, the faster your heart goes.
                int every = level >= 85f ? 10 : level >= 60f ? 16 : 24;
                if (time % every < 5) {
                    Fx.soundTo(player, ModSounds.HEARTBEAT, SoundSource.PLAYERS, 0.35f + level / 160f, 0.9f + level / 500f);
                }
            }
        }
    }
}
