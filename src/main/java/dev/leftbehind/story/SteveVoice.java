package dev.leftbehind.story;

import dev.leftbehind.fx.Fx;
import dev.leftbehind.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * Placeholder for voiceovers: lines appear in chat for nearby players.
 * Each line here is a future voiceover cue.
 */
public final class SteveVoice {
    private SteveVoice() {}

    public static void say(Entity speaker, String name, ChatFormatting color, String line) {
        if (!(speaker.level() instanceof ServerLevel level)) return;
        Component message = Component.literal("<" + name + "> ").withStyle(color)
                .append(Component.literal(line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(speaker) < 48 * 48) player.sendSystemMessage(message);
        }
    }

    public static void steve(Entity steve, String line) {
        say(steve, "Steve", ChatFormatting.AQUA, line);
        // Until there are recorded voiceovers, he has a small wordless voice of his own.
        Fx.sound(steve, ModSounds.TALK, SoundSource.NEUTRAL, 0.7f, 0.92f + steve.getRandom().nextFloat() * 0.16f);
    }

    public static void steve(Entity steve, RandomSource random, String... lines) {
        steve(steve, lines[random.nextInt(lines.length)]);
    }

    public static void herobrine(Entity herobrine, String line) {
        say(herobrine, "???", ChatFormatting.DARK_GRAY, line);
    }

    /** Only the given player hears it. */
    public static void whisper(ServerPlayer player, String name, String line) {
        player.sendSystemMessage(Component.literal("<" + name + "> ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
    }
}
