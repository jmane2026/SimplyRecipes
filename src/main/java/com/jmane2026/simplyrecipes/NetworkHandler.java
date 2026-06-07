package com.jmane2026.simplyrecipes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;

public class NetworkHandler {
    
    public static void handleSaveRecipe(final SaveRecipePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            MinecraftServer server = player.level().getServer();

            NameAndId identity = new NameAndId(player.getGameProfile().id(), player.getGameProfile().name());
            
            if (!server.getPlayerList().isOp(identity)) {
                player.sendSystemMessage(Component.literal("§cYou do not have permission to edit recipes."));
                return;
            }

            try {
                JsonObject json = JsonParser.parseString(payload.jsonContent()).getAsJsonObject();
                RecipeGenerator.saveCustomRecipe(payload.id(), json);
                
                player.sendSystemMessage(Component.literal("§aServer: Saved recipe " + payload.id().toString()));
                
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS).withSuppressedOutput(),
                        "reload"
                );
            } catch (Exception e) {
                player.sendSystemMessage(Component.literal("§cFailed to save recipe on server: " + e.getMessage()));
            }
        });
    }
}