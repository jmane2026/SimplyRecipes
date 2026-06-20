package com.jmane2026.simplyrecipes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class NetworkHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void handleRequestRecipes(final RequestRecipesPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            MinecraftServer server = context.player().level().getServer();
            List<ProvideRecipesPayload.RecipeInfo> found = new ArrayList<>();
            
            BuiltInRegistries.ITEM.getOptional(payload.itemId()).ifPresent(targetItem -> {
                
                server.getRecipeManager().getRecipes().forEach(holder -> {
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

    public static void handleCreateItem(final CreateItemPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            try {
                JsonObject json = JsonParser.parseString(payload.jsonContent()).getAsJsonObject();
                String rawId = json.has("id") ? json.get("id").getAsString() : "new_item";
                
                String inputNamespace = "simplyrecipes";
                String inputPath = rawId;
                
                if (rawId.contains(":")) {
                    String[] parts = rawId.split(":", 2);
                    inputNamespace = parts[0].toLowerCase().replaceAll("[^a-z0-9._-]", "");
                    inputPath = parts[1].toLowerCase().replaceAll("[^a-z0-9._-]", "");
                }
                
                String finalPath = inputNamespace + "_" + inputPath;

                Path baseDir = FMLPaths.GAMEDIR.get().resolve("simplyrecipes/data").resolve(inputNamespace).resolve("item_definitions");
                Files.createDirectories(baseDir);
                
                Path filePath = baseDir.resolve(inputPath + ".json");
                Files.writeString(filePath, payload.jsonContent());

                Path langDir = FMLPaths.GAMEDIR.get().resolve("simplyrecipes/assets").resolve(SimplyRecipes.MODID).resolve("lang");
                Files.createDirectories(langDir);
                Path langFile = langDir.resolve("en_us.json");
                
                JsonObject langJson = new JsonObject(); 
                if (Files.exists(langFile)) {
                    try { langJson = JsonParser.parseString(Files.readString(langFile)).getAsJsonObject(); } catch (Exception ignored) {}
                }
                
                langJson.addProperty("item." + SimplyRecipes.MODID + "." + finalPath, json.get("name").getAsString());
                Files.writeString(langFile, GSON.toJson(langJson));

                if (payload.textureData() != null && payload.textureData().length > 0) {
                    Path textureDir = FMLPaths.GAMEDIR.get().resolve("simplyrecipes/assets").resolve(SimplyRecipes.MODID).resolve("textures/item");
                    Files.createDirectories(textureDir);
                    Files.write(textureDir.resolve(finalPath + ".png"), payload.textureData());
                }

                Path modelDir = FMLPaths.GAMEDIR.get().resolve("simplyrecipes/assets").resolve(SimplyRecipes.MODID).resolve("models/item");
                Files.createDirectories(modelDir);

                JsonObject modelJson = new JsonObject();
                modelJson.addProperty("parent", "minecraft:item/generated");
                JsonObject texturesObj = new JsonObject();
                String texPath = SimplyRecipes.MODID + ":item/" + finalPath;
                texturesObj.addProperty("layer0", texPath);
                texturesObj.addProperty("particle", texPath);
                modelJson.add("textures", texturesObj);

                Files.writeString(modelDir.resolve(finalPath + ".json"), GSON.toJson(modelJson));

                Path itemsDir = FMLPaths.GAMEDIR.get().resolve("simplyrecipes/assets").resolve(SimplyRecipes.MODID).resolve("items");
                Files.createDirectories(itemsDir);

                JsonObject definitionJson = new JsonObject();
                JsonObject modelDef = new JsonObject();
                modelDef.addProperty("type", "minecraft:model");
                modelDef.addProperty("model", SimplyRecipes.MODID + ":item/" + finalPath);
                definitionJson.add("model", modelDef);

                Files.writeString(itemsDir.resolve(finalPath + ".json"), GSON.toJson(definitionJson));

                player.sendSystemMessage(Component.literal("§bCreated item definition and model for: §f" + SimplyRecipes.MODID + ":" + finalPath + "§7 (Requires Restart)"));
            } catch (Exception e) {
                player.sendSystemMessage(Component.literal("§cError creating item: " + e.getMessage()));
            }
        });
    }
}