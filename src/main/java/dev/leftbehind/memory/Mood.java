package dev.leftbehind.memory;

/** What Original Steve is going through right now. Saved with the world. */
public enum Mood {
    /** Himself. */
    NORMAL,
    /** A memory just broke through. He is running from everyone. */
    FLEEING,
    /** Far from the players, living through it again. Can be watched from a distance. */
    TRAUMA,
    /** He sees Herobrine everywhere and comes back to tear things down. */
    RAMPAGE,
    /** It's over. He walks back, and doesn't remember. */
    RETURNING;

    public boolean inEpisode() {
        return this != NORMAL;
    }

    public static Mood byId(long id) {
        Mood[] values = values();
        return id < 0 || id >= values.length ? NORMAL : values[(int) id];
    }
}
