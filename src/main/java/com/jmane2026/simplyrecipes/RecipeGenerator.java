package com.jmane2026.simplyrecipes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecipeGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void saveCustomRecipe(Identifier recipeId, JsonObject recipeJson) {
        Path path = FMLPaths.GAMEDIR.get().resolve("simplyrecipes")
                .resolve("data")
                .resolve(recipeId.getNamespace())
                .resolve("recipe")
                .resolve(recipeId.getPath() + ".json");

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(recipeJson));
            SimplyRecipes.LOGGER.info("Successfully saved custom recipe: {}", path);
        } catch (IOException e) {
            SimplyRecipes.LOGGER.error("Failed to save custom recipe {}", recipeId, e);
        }
    }

    public static JsonObject createShapedRecipeTemplate(Identifier resultId, int count, ItemStack[] grid) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:crafting_shaped");

        Map<Identifier, Character> keyMap = new HashMap<>();
        char nextChar = 'A';
        
        JsonArray patternArray = new JsonArray();
        for (int row = 0; row < 3; row++) {
            StringBuilder rowString = new StringBuilder();
            for (int col = 0; col < 3; col++) {
                ItemStack stack = grid[row * 3 + col];
                if (stack.isEmpty()) {
                    rowString.append(" ");
                } else {
                    Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (!keyMap.containsKey(id)) {
                        keyMap.put(id, nextChar++);
                    }
                    rowString.append(keyMap.get(id));
                }
            }
            patternArray.add(rowString.toString());
        }
        json.add("pattern", patternArray);

        JsonObject keyObject = new JsonObject();
        for (Map.Entry<Identifier, Character> entry : keyMap.entrySet()) {
            keyObject.addProperty(entry.getValue().toString(), entry.getKey().toString());
        }
        json.add("key", keyObject);

        JsonObject result = new JsonObject();
        result.addProperty("id", resultId.toString());
        result.addProperty("count", count);
        json.add("result", result);

        return json;
    }

    public static JsonObject createShapelessRecipeTemplate(Identifier resultId, int count, List<Identifier> ingredients) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:crafting_shapeless");

        JsonArray ingredientArray = new JsonArray();
        for (Identifier ing : ingredients) {
            ingredientArray.add(ing.toString());
        }
        json.add("ingredients", ingredientArray);

        JsonObject result = new JsonObject();
        result.addProperty("id", resultId.toString());
        result.addProperty("count", count);
        json.add("result", result);

        return json;
    }

    public static JsonObject createCookingRecipeTemplate(String type, Identifier resultId, Identifier ingredientId, int cookingTime, float experience) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);

        json.addProperty("ingredient", ingredientId.toString());

        JsonObject result = new JsonObject();
        result.addProperty("id", resultId.toString());
        json.add("result", result);

        json.addProperty("experience", experience);
        json.addProperty("cooking_time", cookingTime);

        return json;
    }

    public static JsonObject createStonecuttingRecipeTemplate(Identifier resultId, int count, Identifier ingredientId) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:stonecutting");

        json.addProperty("ingredient", ingredientId.toString());

        json.addProperty("result", resultId.toString());
        json.addProperty("count", count);

        return json;
    }

    public static JsonObject createSmithingRecipeTemplate(Identifier resultId, Identifier templateId, Identifier baseId, Identifier additionId) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:smithing_transform");

        json.addProperty("template", templateId.toString());
        json.addProperty("base", baseId.toString());
        json.addProperty("addition", additionId.toString());

        JsonObject result = new JsonObject();
        result.addProperty("id", resultId.toString());
        json.add("result", result);

        return json;
    }
}