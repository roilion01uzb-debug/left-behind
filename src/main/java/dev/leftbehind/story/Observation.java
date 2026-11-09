package dev.leftbehind.story;

import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Steve watches. Only what he actually sees counts: he has to be nearby with a line of sight.
 */
public final class Observation {
    private Observation() {}

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer serverPlayer)) return;
            OriginalSteve steve = watching(server, serverPlayer);
            if (steve == null) return;
            SteveMemory memory = SteveMemory.get(server.getServer());
            if (state.is(BlockTags.LOGS)) {
                memory.observe("chop_wood");
                if (memory.habit("chop_wood") == 1) SteveVoice.steve(steve, "What are you doing to that tree?");
                if (memory.habit("chop_wood") == 4) SteveVoice.steve(steve, "Oh. You're building something.");
            } else if (state.is(BlockTags.BEDS)) {
                memory.observe("break_bed");
                SteveVoice.steve(steve, "Don't. Somebody sleeps there.");
                memory.addStability(-2f);
            } else if (state.is(BlockTags.BASE_STONE_OVERWORLD)) {
                memory.observe("mine");
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity.level() instanceof ServerLevel server) || entity instanceof OriginalSteve) return;
            if (!(source.getEntity() instanceof ServerPlayer player)) return;
            OriginalSteve steve = watching(server, player);
            if (steve == null) return;
            SteveMemory memory = SteveMemory.get(server.getServer());
            Relationship r = memory.relationship(player.getUUID());
            r.killsWitnessed++;
            memory.observe("kill");
            // The same act means different things depending on what he has been through.
            if (memory.breaches == 0) {
                if (entity instanceof Enemy) {
                    r.addTrust(1.5f);
                    if (steve.getRandom().nextInt(4) == 0) SteveVoice.steve(steve, steve.getRandom(), "Nice one.", "Thanks.", "You're good at that.");
                }
            } else {
                memory.addStability(-3f);
                if (steve.getRandom().nextInt(3) == 0) {
                    SteveVoice.steve(steve, steve.getRandom(), "Stop. I've seen this before.", "That's how it started last time.",
                            "Please. Not in front of me.");
                }
            }
            if (!(entity instanceof Enemy) && entity instanceof LivingEntity) r.addTrust(-1f);
        });
    }

    private static OriginalSteve watching(ServerLevel level, Entity player) {
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve == null || steve.isInvisible() || steve.distanceToSqr(player) > 20 * 20 || !steve.hasLineOfSight(player)) return null;
        return steve;
    }
}
