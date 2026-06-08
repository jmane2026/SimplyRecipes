package com.jmane2026.simplyrecipes;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;

public record ProvideRecipesPayload(List<RecipeInfo> recipes) implements CustomPacketPayload {
    public static final Type<ProvideRecipesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("simplyrecipes", "provide_recipes"));

    public record RecipeInfo(Identifier id, String type) {
        public static final StreamCodec<FriendlyByteBuf, RecipeInfo> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, RecipeInfo::id,
                ByteBufCodecs.STRING_UTF8, RecipeInfo::type,
                RecipeInfo::new
        );
    }

    public static final StreamCodec<FriendlyByteBuf, ProvideRecipesPayload> STREAM_CODEC = ByteBufCodecs.collection(ArrayList::new, RecipeInfo.STREAM_CODEC)
            .map(list -> new ProvideRecipesPayload(list), payload -> new ArrayList<>(payload.recipes()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}