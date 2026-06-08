package com.jmane2026.simplyrecipes;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RequestRecipesPayload(Identifier itemId) implements CustomPacketPayload {
    public static final Type<RequestRecipesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("simplyrecipes", "request_recipes"));
    public static final StreamCodec<FriendlyByteBuf, RequestRecipesPayload> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, RequestRecipesPayload::itemId,
            RequestRecipesPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}