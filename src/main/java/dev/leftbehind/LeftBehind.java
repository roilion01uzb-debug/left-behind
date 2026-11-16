package dev.leftbehind;

import dev.leftbehind.command.LeftBehindCommand;
import dev.leftbehind.fx.FxPayload;
import dev.leftbehind.registry.ModEntities;
import dev.leftbehind.registry.ModSounds;
import dev.leftbehind.story.Observation;
import dev.leftbehind.story.StoryDirector;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeftBehind implements ModInitializer {
    public static final String MOD_ID = "leftbehind";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModSounds.init();
        ModEntities.init();
        PayloadTypeRegistry.clientboundPlay().register(FxPayload.TYPE, FxPayload.CODEC);
        Observation.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> StoryDirector.resetTransient());
        ServerTickEvents.END_SERVER_TICK.register(StoryDirector::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> LeftBehindCommand.register(dispatcher));
        LOGGER.info("Left Behind loaded. He is already here.");
    }
}
