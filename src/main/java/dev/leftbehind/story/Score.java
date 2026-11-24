package dev.leftbehind.story;

import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.fx.FxPayload;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModSounds;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.CampfireBlock;
import org.jspecify.annotations.Nullable;

/**
 * The music. Pieces in the theme's style play at the moments they belong to, in place of the game's own songs:
 * the first minutes of a night, deep underground, by the fire with Steve when he trusts you, when Herobrine is near,
 * when a memory comes back, and at each act card. Quiet cues never cut off a song that is already playing.
 */
public final class Score {
    // Cue numbers. They index ModSounds.MUSIC.
    public static final int THEME = 0;
    public static final int NIGHT = 1;
    public static final int UNDERGROUND = 2;
    public static final int FIRE = 3;
    public static final int FLASHBACK = 4;
    public static final int NEAR = 5;
    public static final int ACT_2 = 6;
    public static final int ACT_3 = 7;
    public static final int ACT_4 = 8;

    /** At least this long between two cues for the same player, so music stays a moment and not wallpaper. */
    private static final long GAP = 6000;
    private static final long FIRE_GAP = 24000;
    private static final long UNDERGROUND_GAP = 18000;
    /** How long you have to be down there before the cave piece starts. */
    private static final long UNDERGROUND_AFTER = 1200;

    private static final Map<UUID, Long> LAST = new HashMap<>();
    private static final Map<UUID, Long> LAST_FIRE = new HashMap<>();
    private static final Map<UUID, Long> LAST_UNDERGROUND = new HashMap<>();
    private static final Map<UUID, Long> UNDER_SINCE = new HashMap<>();
    private static final Map<UUID, Long> NIGHT_PLAYED = new HashMap<>();
    private static final Map<UUID, UUID> NEAR_FOR = new HashMap<>();

    private Score() {}

    public static void resetTransient() {
        LAST.clear();
        LAST_FIRE.clear();
        LAST_UNDERGROUND.clear();
        UNDER_SINCE.clear();
        NIGHT_PLAYED.clear();
        NEAR_FOR.clear();
    }

    /** Cues that belong to a moment, and so may stop a song that is playing. */
    public static boolean interrupts(int cue) {
        return cue == THEME || cue == FLASHBACK || cue == NEAR || cue >= ACT_2;
    }

    /** Start a cue for one player. Players without the mod only ever hear the theme, as a record. */
    public static void play(ServerPlayer player, int cue) {
        LAST.put(player.getUUID(), player.level().getGameTime());
        if (ServerPlayNetworking.canSend(player, FxPayload.TYPE)) {
            Fx.screen(player, FxPayload.MUSIC, cue);
        } else if (cue == THEME || cue >= ACT_2) {
            Fx.soundTo(player, ModSounds.THEME, SoundSource.RECORDS, 0.8f, 1.0f);
        }
    }

    /** The piece for an act card. */
    public static int forAct(int act) {
        return act <= 1 ? THEME : ACT_2 + Math.min(act, 4) - 2;
    }

    /** A memory came back to someone: the theme, backwards, as if the tape caught. */
    public static void memory(ServerPlayer player) {
        if (player.level().getGameTime() - LAST.getOrDefault(player.getUUID(), -GAP) < GAP / 2) return;
        play(player, FLASHBACK);
    }

    /** Called once a second from the story director. */
    public static void tick(ServerLevel level, SteveMemory memory, @Nullable OriginalSteve steve, long time) {
        Herobrine herobrine = StoryDirector.activeHerobrine();
        long clock = level.getOverworldClockTime();
        long day = clock / 24000L;
        long hour = clock % 24000L;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            UUID id = player.getUUID();
            boolean sky = level.canSeeSky(player.blockPosition().above());
            if (!sky && player.getY() < 40) UNDER_SINCE.putIfAbsent(id, time);
            else UNDER_SINCE.remove(id);
            if (StoryDirector.isHunted(player)) continue; // the chase has its own sound

            // He is near: this one doesn't wait, but only once for each time he comes.
            if (herobrine != null && memory.act() >= 2 && !herobrine.getUUID().equals(NEAR_FOR.get(id))
                    && (Sense.get(player) >= 12f || player.distanceToSqr(herobrine) < 20 * 20)) {
                NEAR_FOR.put(id, herobrine.getUUID());
                if (time - LAST.getOrDefault(id, -GAP) > 1200) play(player, NEAR);
                continue;
            }
            if (time - LAST.getOrDefault(id, -GAP) < GAP) continue;

            // By the fire, when he trusts you.
            if (steve != null && !memory.mood().inEpisode() && memory.trust(id) >= 40f && hour > 11500 && hour < 23000
                    && player.distanceToSqr(steve) < 9 * 9 && nearLitFire(level, player.blockPosition())
                    && time - LAST_FIRE.getOrDefault(id, -FIRE_GAP) > FIRE_GAP) {
                LAST_FIRE.put(id, time);
                play(player, FIRE);
                continue;
            }
            // Deep down, after a while.
            Long under = UNDER_SINCE.get(id);
            if (under != null && time - under > UNDERGROUND_AFTER && time - LAST_UNDERGROUND.getOrDefault(id, -UNDERGROUND_GAP) > UNDERGROUND_GAP
                    && level.getRandom().nextInt(3) == 0) {
                LAST_UNDERGROUND.put(id, time);
                play(player, UNDERGROUND);
                continue;
            }
            // The first part of a night, once a night, outside.
            if (sky && hour > 13000 && hour < 15500 && NIGHT_PLAYED.getOrDefault(id, -1L) != day) {
                NIGHT_PLAYED.put(id, day);
                if (level.getRandom().nextInt(100) < 60) play(player, NIGHT);
            }
        }
    }

    private static boolean nearLitFire(ServerLevel level, BlockPos at) {
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-5, -2, -5), at.offset(5, 2, 5))) {
            if (CampfireBlock.isLitCampfire(level.getBlockState(pos))) return true;
        }
        return false;
    }
}
