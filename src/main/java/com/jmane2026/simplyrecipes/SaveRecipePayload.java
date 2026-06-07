package com.jmane2026.simplyrecipes;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SaveRecipePayload(Identifier id, String jsonContent) implements CustomPacketPayload {
    public static final Type<SaveRecipePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("simplyrecipes", "save_recipe"));

    public static final StreamCodec<FriendlyByteBuf, SaveRecipePayload> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, SaveRecipePayload::id,
            ByteBufCodecs.STRING_UTF8, SaveRecipePayload::jsonContent,
            SaveRecipePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}