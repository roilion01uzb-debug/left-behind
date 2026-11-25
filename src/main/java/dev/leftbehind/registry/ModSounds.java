package dev.leftbehind.registry;

import dev.leftbehind.LeftBehind;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** The mod's own sounds. All of them are synthesised by tools/make_sounds.py. */
public final class ModSounds {
    public static final Holder<SoundEvent> DREAD = register("ambient.dread");
    public static final Holder<SoundEvent> WIND_HOWL = register("ambient.wind_howl");
    public static final Holder<SoundEvent> STATIC = register("ambient.static");
    public static final Holder<SoundEvent> KNOCK = register("ambient.knock");
    public static final Holder<SoundEvent> PAGE = register("ambient.page");
    public static final Holder<SoundEvent> PRESENCE = register("herobrine.presence");
    public static final Holder<SoundEvent> HEARTBEAT = register("herobrine.heartbeat");
    public static final Holder<SoundEvent> STING = register("herobrine.sting");
    public static final Holder<SoundEvent> CHASE = register("herobrine.chase");
    public static final Holder<SoundEvent> VANISH = register("herobrine.vanish");
    public static final Holder<SoundEvent> WHISPER = register("herobrine.whisper");
    public static final Holder<SoundEvent> BREATH = register("herobrine.breath");
    public static final Holder<SoundEvent> KILL = register("herobrine.kill");
    public static final Holder<SoundEvent> STALK_STEP = register("herobrine.step");
    public static final Holder<SoundEvent> TORCH_OUT = register("ambient.torch_out");
    public static final Holder<SoundEvent> STONE_GRIND = register("ambient.stone_grind");
    public static final Holder<SoundEvent> FAR_STEPS = register("ambient.far_steps");
    public static final Holder<SoundEvent> FAR_MINING = register("ambient.far_mining");
    public static final Holder<SoundEvent> DRIP = register("ambient.drip");
    public static final Holder<SoundEvent> CREAK = register("ambient.creak");
    public static final Holder<SoundEvent> DOOR_FAR = register("ambient.door_far");
    public static final Holder<SoundEvent> BREACH = register("steve.breach");
    public static final Holder<SoundEvent> PANIC_BREATH = register("steve.panic_breath");
    public static final Holder<SoundEvent> MUTTER = register("steve.mutter");
    public static final Holder<SoundEvent> GHOST_APPEAR = register("ghost.appear");
    public static final Holder<SoundEvent> GHOST_HUM = register("ghost.hum");
    public static final Holder<SoundEvent> FRAGMENT = register("memory.fragment");
    public static final Holder<SoundEvent> THEME = register("memory.theme");
    // 0.7: sounds that belong to this story, instead of the usual horror drone.
    /** Steve's tune, on a note block somewhere you can't see. */
    public static final Holder<SoundEvent> TUNE_FAR = register("lore.tune_far");
    /** Steve's tune played back by someone who only heard it once. */
    public static final Holder<SoundEvent> TUNE_WRONG = register("lore.tune_wrong");
    /** The music box running down. */
    public static final Holder<SoundEvent> WINDING_DOWN = register("lore.winding_down");
    /** Seven knocks on stone, a gap, then an eighth. He counts things. */
    public static final Holder<SoundEvent> COUNT = register("lore.count");
    /** Somebody placing blocks a long way off. */
    public static final Holder<SoundEvent> BUILDING_FAR = register("lore.building_far");
    /** Somebody digging straight down. */
    public static final Holder<SoundEvent> DIGGING_DOWN = register("lore.digging_down");
    /** The tone of a world before its seed. */
    public static final Holder<SoundEvent> SEED = register("lore.seed");
    /** The world forgetting itself for a second. */
    public static final Holder<SoundEvent> RESET = register("lore.reset");
    /** Steve humming his tune, far off, while he is standing next to you. */
    public static final Holder<SoundEvent> HUM = register("lore.hum");
    /** A wooden door, opening slowly by itself. */
    public static final Holder<SoundEvent> DOOR_OPEN = register("lore.door_open");

    /** The music cues, in the order of the cue numbers in {@link dev.leftbehind.story.Score}. */
    public static final List<Holder<SoundEvent>> MUSIC = List.of(THEME, register("music.night"), register("music.underground"),
            register("music.fire"), register("music.flashback"), register("music.near"), register("music.act_2"),
            register("music.act_3"), register("music.act_4"));

    private ModSounds() {}

    private static Holder<SoundEvent> register(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, name);
        return Registry.registerForHolder(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    public static void init() {}
}
