package dev.leftbehind.client.fx;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.registry.ModSounds;
import dev.leftbehind.story.Score;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.core.Holder;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;

/**
 * Plays the mod's music cues through the game's own music player, so they use the Music slider,
 * show in the "Now playing" toast, and the game's songs wait their usual time after one ends.
 */
public final class ScoreClient {
    private static int played;
    private static String last = "";

    private ScoreClient() {}

    public static void play(int cue) {
        if (cue < 0 || cue >= ModSounds.MUSIC.size()) return;
        Minecraft client = Minecraft.getInstance();
        MusicManager music = client.getMusicManager();
        // The quiet cues wait their turn: they never cut off a song that is already playing.
        // The ones that belong to a moment (an act, a memory, him) always play.
        if (!Score.interrupts(cue) && music.getCurrentMusicTranslationKey() != null) return;
        Holder<SoundEvent> sound = ModSounds.MUSIC.get(cue);
        music.stopPlaying();
        music.startPlaying(new Music(sound, 6000, 12000, false));
        played++;
        last = sound.value().location().getPath();
        LeftBehind.LOGGER.info("Music: {}", last);
    }

    /** For the game test: how many cues have started, and the last one. */
    public static int played() {
        return played;
    }

    public static String last() {
        return last;
    }
}
