package dev.leftbehind.client;

import dev.leftbehind.client.fx.ScreenFx;
import dev.leftbehind.client.render.HerobrineRenderer;
import dev.leftbehind.client.render.ModModels;
import dev.leftbehind.client.render.OriginalSteveRenderer;
import dev.leftbehind.client.render.RemnantRenderer;
import dev.leftbehind.fx.FxPayload;
import dev.leftbehind.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

public class LeftBehindClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModModels.register();
        EntityRendererRegistry.register(ModEntities.ORIGINAL_STEVE, OriginalSteveRenderer::new);
        EntityRendererRegistry.register(ModEntities.HEROBRINE, HerobrineRenderer::new);
        EntityRendererRegistry.register(ModEntities.REMNANT, RemnantRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(FxPayload.TYPE, (payload, context) -> context.client().execute(() -> ScreenFx.receive(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ScreenFx.reset());
        ClientTickEvents.END_CLIENT_TICK.register(ScreenFx::tick);
        HudElementRegistry.addLast(ScreenFx.ID, ScreenFx::extract);
    }
}
