package dev.leftbehind.memory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;

/**
 * How Original Steve feels about one specific player.
 * Trust and anger are separate on purpose: a player can be trusted and still
 * get hurt during a Herobrine episode, and an angry Steve knows exactly what he is doing.
 */
public final class Relationship {
    public static final Codec<Relationship> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.optionalFieldOf("trust", 20f).forGetter(r -> r.trust),
            Codec.FLOAT.optionalFieldOf("anger", 0f).forGetter(r -> r.anger),
            Codec.INT.optionalFieldOf("times_hurt", 0).forGetter(r -> r.timesHurt),
            Codec.INT.optionalFieldOf("gifts", 0).forGetter(r -> r.gifts),
            Codec.INT.optionalFieldOf("kills_witnessed", 0).forGetter(r -> r.killsWitnessed)
    ).apply(i, Relationship::new));

    public float trust;
    public float anger;
    public int timesHurt;
    public int gifts;
    public int killsWitnessed;

    public Relationship() {
        this(20f, 0f, 0, 0, 0);
    }

    public Relationship(float trust, float anger, int timesHurt, int gifts, int killsWitnessed) {
        this.trust = trust;
        this.anger = anger;
        this.timesHurt = timesHurt;
        this.gifts = gifts;
        this.killsWitnessed = killsWitnessed;
    }

    public void addTrust(float amount) {
        trust = Mth.clamp(trust + amount, 0f, 100f);
    }

    public void addAnger(float amount) {
        anger = Mth.clamp(anger + amount, 0f, 100f);
    }

    public AngerStage stage() {
        return AngerStage.of(anger);
    }
}
