package com.jmane2026.simplyrecipes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class NetworkHandler {

    public static void handleRequestRecipes(final RequestRecipesPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            MinecraftServer server = context.player().level().getServer();
            List<ProvideRecipesPayload.RecipeInfo> found = new ArrayList<>();
            
            // 1. Use getOptional to safely retrieve the target item from the registry
            BuiltInRegistries.ITEM.getOptional(payload.itemId()).ifPresent(targetItem -> {
                
                // 2. Iterate through all registered recipes. RecipeManager#getRecipes() 
                // returns a Collection of RecipeHolders.
                server.getRecipeManager().getRecipes().forEach(holder -> {
                    // 3. In 1.21.3, recipes use the Display system. We check if any of the 
                    // recipe's displays result in the target item.
                    boolean matches = holder.value().display().stream().anyMatch(display -> 
                        display.result().resolveForFirstStack(SlotDisplayContext.fromLevel(context.player().level())).is(targetItem)
                    );

                    if (matches) {
                        found.add(new ProvideRecipesPayload.RecipeInfo(
                            holder.id().identifier(),
                            BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString()
                        ));
                    }
                });
            });

            context.reply(new ProvideRecipesPayload(found));
        });
    }

    public static void handleProvideRecipes(final ProvideRecipesPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            // This runs on the CLIENT
            if (Minecraft.getInstance().screen instanceof EditorScreen screen) {
                screen.receiveDiscoveredRecipes(payload.recipes());
            }
        });
    }

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