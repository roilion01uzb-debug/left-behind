package dev.leftbehind;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeftBehind implements ModInitializer {
    public static final String MOD_ID = "leftbehind";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Left Behind loaded.");
    }
}
