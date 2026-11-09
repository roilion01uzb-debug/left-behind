package dev.leftbehind.client;

import dev.leftbehind.client.render.HerobrineRenderer;
import dev.leftbehind.client.render.SteveLikeRenderer;
import dev.leftbehind.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class LeftBehindClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.ORIGINAL_STEVE, SteveLikeRenderer::new);
        EntityRendererRegistry.register(ModEntities.HEROBRINE, HerobrineRenderer::new);
    }
}
