package dev.leftbehind.fx;

import dev.leftbehind.LeftBehind;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A screen effect sent from the server to one player's client. */
public record FxPayload(int kind, float value) implements CustomPacketPayload {
    /** How strongly Herobrine is aware of you, 0 to 1. Drives the dark vignette. */
    public static final int DREAD = 0;
    /** TV static over the screen, for this many ticks. */
    public static final int STATIC = 1;
    /** Two white eyes, for this many ticks. */
    public static final int EYES = 2;
    /** The screen cuts to black, for this many ticks. */
    public static final int BLACKOUT = 3;
    /** The picture shakes, for this many ticks. */
    public static final int SHAKE = 4;

    public static final CustomPacketPayload.Type<FxPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "fx"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FxPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FxPayload::kind, ByteBufCodecs.FLOAT, FxPayload::value, FxPayload::new);

    @Override
    public Type<FxPayload> type() {
        return TYPE;
    }
}
