package com.jmane2026.simplyrecipes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CreateItemPayload(String jsonContent, byte[] textureData) implements CustomPacketPayload {
    public static final Type<CreateItemPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SimplyRecipes.MODID, "create_item"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CreateItemPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CreateItemPayload::jsonContent,
            ByteBufCodecs.BYTE_ARRAY, CreateItemPayload::textureData,
            CreateItemPayload::new
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}