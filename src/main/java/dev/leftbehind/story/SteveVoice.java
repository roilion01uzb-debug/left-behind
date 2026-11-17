package dev.leftbehind.story;

import dev.leftbehind.talk.SpeechPayload;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Everything Steve (and Herobrine) says goes through here.
 * A line shows above the speaker's head, as a subtitle on the screen of everyone close enough to hear,
 * and in chat so it can be scrolled back. Lines from one speaker queue up so they never talk over themselves.
 */
public final class SteveVoice {
    public static final int STEVE_COLOR = 0x55FFFF;
    public static final int HEROBRINE_COLOR = 0xBBBBBB;
    private static final double HEARING = 40.0;

    private record Queued(Entity speaker, String name, int color, String line) {}

    private static final Map<Integer, Deque<Queued>> QUEUES = new HashMap<>();
    private static final Map<Integer, Long> BUSY_UNTIL = new HashMap<>();

    private SteveVoice() {}

    /** How long it takes to get a line out: the typing on screen plus a breath. */
    public static int speakingTicks(String line) {
        return Mth.clamp(Math.round(line.length() / 1.6f) + 26, 34, 200);
    }

    public static void say(Entity speaker, String name, int color, String line) {
        if (!(speaker.level() instanceof ServerLevel level) || line.isBlank()) return;
        int id = speaker.getId();
        Deque<Queued> queue = QUEUES.get(id);
        if (level.getGameTime() < BUSY_UNTIL.getOrDefault(id, 0L) || (queue != null && !queue.isEmpty())) {
            queue = QUEUES.computeIfAbsent(id, k -> new ArrayDeque<>());
            if (queue.size() < 6) queue.addLast(new Queued(speaker, name, color, line));
            return;
        }
        emit(level, speaker, name, color, line);
    }

    /** Drops anything he was still going to say, so the next line comes out right away. */
    public static void interrupt(Entity speaker) {
        QUEUES.remove(speaker.getId());
        BUSY_UNTIL.remove(speaker.getId());
    }

    public static boolean isSpeaking(Entity speaker) {
        Deque<Queued> queue = QUEUES.get(speaker.getId());
        return speaker.level().getGameTime() < BUSY_UNTIL.getOrDefault(speaker.getId(), 0L) || (queue != null && !queue.isEmpty());
    }

    private static void emit(ServerLevel level, Entity speaker, String name, int color, String line) {
        int speaking = speakingTicks(line);
        BUSY_UNTIL.put(speaker.getId(), level.getGameTime() + speaking);
        SpeechPayload payload = new SpeechPayload(speaker.getId(), name, color, line, speaking + 50, SpeechPayload.SPOKEN);
        Component chat = Component.literal(name + ": ").withStyle(s -> s.withColor(TextColor.fromRgb(color)))
                .append(Component.literal(line).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(speaker) > HEARING * HEARING) continue;
            if (ServerPlayNetworking.canSend(player, SpeechPayload.TYPE)) ServerPlayNetworking.send(player, payload);
            player.sendSystemMessage(chat);
        }
    }

    /** Runs every tick: lets queued lines out once the previous one is finished. */
    public static void tick(MinecraftServer server) {
        if (QUEUES.isEmpty()) return;
        Iterator<Map.Entry<Integer, Deque<Queued>>> it = QUEUES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Deque<Queued>> entry = it.next();
            Deque<Queued> queue = entry.getValue();
            if (queue.isEmpty()) {
                it.remove();
                continue;
            }
            Queued next = queue.peekFirst();
            if (next.speaker().isRemoved() || !(next.speaker().level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            if (level.getGameTime() < BUSY_UNTIL.getOrDefault(entry.getKey(), 0L)) continue;
            queue.pollFirst();
            emit(level, next.speaker(), next.name(), next.color(), next.line());
        }
    }

    public static void reset() {
        QUEUES.clear();
        BUSY_UNTIL.clear();
    }

    // ---- shortcuts ------------------------------------------------------------------

    public static void steve(Entity steve, String line) {
        say(steve, "Steve", STEVE_COLOR, line);
    }

    public static void steve(Entity steve, RandomSource random, String... lines) {
        steve(steve, lines[random.nextInt(lines.length)]);
    }

    /** Several lines in a row, as one thing said. */
    public static void steveLines(Entity steve, Iterable<String> lines) {
        for (String line : lines) steve(steve, line);
    }

    public static void herobrine(Entity herobrine, String line) {
        say(herobrine, "???", HEROBRINE_COLOR, line);
    }

    /** Only the given player hears it, as a voice in their head: a subtitle, no bubble. */
    public static void whisper(ServerPlayer player, @Nullable Entity from, String name, int color, String line) {
        int ticks = speakingTicks(line) + 50;
        if (ServerPlayNetworking.canSend(player, SpeechPayload.TYPE)) {
            ServerPlayNetworking.send(player, new SpeechPayload(from == null ? -1 : from.getId(), name, color, line, ticks, SpeechPayload.WHISPER));
        }
        player.sendSystemMessage(Component.literal(name + ": ").withStyle(s -> s.withColor(TextColor.fromRgb(color)))
                .append(Component.literal(line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
    }

    public static void whisper(ServerPlayer player, String name, String line) {
        whisper(player, null, name, STEVE_COLOR, line);
    }
}
