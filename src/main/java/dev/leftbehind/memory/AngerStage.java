package dev.leftbehind.memory;

/** Steve's anger toward one player, from the design doc's escalation table. */
public enum AngerStage {
    CALM(0),
    RESENTMENT(20),      // distant, stops helping, watches from farther away
    PROVOCATION(40),     // doors left open, torches go missing
    RETALIATION(60),     // sabotages things near the player's home
    TERRITORIAL(80),     // claims the house, stands by the bed
    ESCALATION(90);      // removes the bed: "You don't live here anymore."

    public final float threshold;

    AngerStage(float threshold) {
        this.threshold = threshold;
    }

    public static AngerStage of(float anger) {
        AngerStage result = CALM;
        for (AngerStage stage : values()) {
            if (anger >= stage.threshold) result = stage;
        }
        return result;
    }

    public boolean atLeast(AngerStage other) {
        return ordinal() >= other.ordinal();
    }
}
