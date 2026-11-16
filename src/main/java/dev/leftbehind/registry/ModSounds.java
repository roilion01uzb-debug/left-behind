package dev.leftbehind.registry;

import dev.leftbehind.LeftBehind;
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
    public static final Holder<SoundEvent> BREACH = register("steve.breach");
    public static final Holder<SoundEvent> PANIC_BREATH = register("steve.panic_breath");
    public static final Holder<SoundEvent> MUTTER = register("steve.mutter");
    public static final Holder<SoundEvent> TALK = register("steve.talk");
    public static final Holder<SoundEvent> GHOST_APPEAR = register("ghost.appear");
    public static final Holder<SoundEvent> GHOST_HUM = register("ghost.hum");
    public static final Holder<SoundEvent> FRAGMENT = register("memory.fragment");
    public static final Holder<SoundEvent> THEME = register("memory.theme");

    private ModSounds() {}

    private static Holder<SoundEvent> register(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, name);
        return Registry.registerForHolder(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    public static void init() {}
}
