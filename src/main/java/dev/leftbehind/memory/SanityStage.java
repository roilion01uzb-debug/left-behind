package dev.leftbehind.memory;

/**
 * How Steve's sanity shows. Nothing is ever on screen: each stage changes what he says and does.
 * Steady: friendly, curious, copies you. Uneasy: counts, repeats himself, wants the lights on.
 * Fraying: talks to someone who isn't there, sees him where there's nothing, calls you by the wrong name.
 * Breaking: lost nights, Herobrine's words in his mouth. Taken: Herobrine has him, for a while.
 */
public enum SanityStage {
    TAKEN("Taken"),
    BREAKING("Breaking"),
    FRAYING("Fraying"),
    UNEASY("Uneasy"),
    STEADY("Steady");

    public final String title;

    SanityStage(String title) {
        this.title = title;
    }

    public static SanityStage of(float sanity) {
        if (sanity <= 0f) return TAKEN;
        if (sanity < 25f) return BREAKING;
        if (sanity < 50f) return FRAYING;
        if (sanity < 75f) return UNEASY;
        return STEADY;
    }

    /** True at this stage or worse. */
    public boolean atMost(SanityStage other) {
        return ordinal() <= other.ordinal();
    }
}
