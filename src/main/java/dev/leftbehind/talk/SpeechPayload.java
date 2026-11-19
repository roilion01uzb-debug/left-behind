package dev.leftbehind.talk;

import dev.leftbehind.LeftBehind;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One spoken line, sent to the players who can hear it. The client shows it above the speaker's head
 * and as a subtitle at the bottom of the screen.
 *
 * @param entityId the speaker, or -1 for a voice with no body
 * @param ticks    how long the line stays up
 * @param style    {@link #SPOKEN} or {@link #WHISPER} (subtitle only, no bubble)
 * @param voice    the recorded voice to play with it ("steve.1a2b3c4d"), or "" for none; see {@link VoiceIndex}
 */
public record SpeechPayload(int entityId, String speaker, int color, String text, int ticks, int style, String voice) implements CustomPacketPayload {
    public static final int SPOKEN = 0;
    public static final int WHISPER = 1;

    public static final CustomPacketPayload.Type<SpeechPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "speech"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpeechPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpeechPayload::entityId,
            ByteBufCodecs.STRING_UTF8, SpeechPayload::speaker,
            ByteBufCodecs.INT, SpeechPayload::color,
            ByteBufCodecs.STRING_UTF8, SpeechPayload::text,
            ByteBufCodecs.VAR_INT, SpeechPayload::ticks,
            ByteBufCodecs.VAR_INT, SpeechPayload::style,
            ByteBufCodecs.STRING_UTF8, SpeechPayload::voice,
            SpeechPayload::new);

    @Override
    public Type<SpeechPayload> type() {
        return TYPE;
    }
}
