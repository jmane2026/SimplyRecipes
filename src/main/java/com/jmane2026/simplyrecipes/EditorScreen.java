package com.jmane2026.simplyrecipes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.gui.components.EditBox;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.lwjgl.glfw.GLFW;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class EditorScreen extends Screen {
    private final Identifier targetItem;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Category {
        ADD_RECIPE("Add Recipe"),
        REMOVE_RECIPE("Remove"),
        CREATE_ITEM("Create Item");

        private final String displayName;
        Category(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    public enum ItemType {
        BASIC("Basic"),
        FOOD("Food"),
        TOOL("Tool"),
        WEAPON("Weapon");

        private final String label;
        ItemType(String label) { this.label = label; }
    }

    public enum ItemRarity {
        COMMON(Rarity.COMMON, "Common"),
        UNCOMMON(Rarity.UNCOMMON, "Uncommon"),
        RARE(Rarity.RARE, "Rare"),
        EPIC(Rarity.EPIC, "Epic");

        private final Rarity rarity;
        private final String label;
        ItemRarity(Rarity rarity, String label) { this.rarity = rarity; this.label = label; }
    }

    public enum RecipeType {
        CRAFTING("Crafting", "crafting_table"),
        SMELTING("Smelting", "furnace"),
        BLASTING("Blasting", "blast_furnace"),
        SMOKING("Smoking", "smoker"),
        CAMPFIRE_COOKING("Campfire", "campfire"),
        STONECUTTING("Stonecutter", "stonecutter"),
        SMITHING("Smithing", "smithing_table");

        private final String displayName;
        private final Identifier stationId;

        RecipeType(String displayName, String stationPath) {
            this.displayName = displayName;
            this.stationId = Identifier.fromNamespaceAndPath("minecraft", stationPath);
        }

        public String getDisplayName() { return displayName; }
        public int getDefaultTicks() { return this == CAMPFIRE_COOKING || this == BLASTING || this == SMOKING ? 100 : 200; }
        public ItemStack getStationStack() {
            return BuiltInRegistries.ITEM.getOptional(stationId).map(ItemStack::new).orElse(ItemStack.EMPTY);
        }
    }

    private Category currentCategory = Category.ADD_RECIPE;
    private RecipeType currentRecipeType = RecipeType.CRAFTING;

    private final IngredientHolder[] inputs = new IngredientHolder[9];
    private final List<OutputData> outputs = new ArrayList<>();

    private boolean isOverride = true;
    private boolean isShapeless = false;
    private boolean isFireResistant = false;
    private boolean isFood = false;
    private boolean hasGlint = false;
    private boolean alwaysEdible = false;

    private Checkbox overrideCheckbox;
    private Checkbox fireResistantCheckbox;
    private Checkbox foodCheckbox;
    private Checkbox glintCheckbox;
    private Checkbox alwaysEdibleCheckbox;
    private Checkbox isPickaxeCheckbox;

    private boolean isDropdownOpen = false;
    private boolean isTypeDropdownOpen = false;
    private boolean isRarityDropdownOpen = false;
    private boolean isItemTypeDropdownOpen = false;
    private int dropdownScroll = 0;
    private static final int MAX_DROPDOWN_VISIBLE = 5;

    private Checkbox shapelessCheckbox;
    private Button categorySelector;
    private Button recipeTypeSelector;
    private Button itemTypeSelector;
    private Button raritySelector;
    private Button selectTextureButton;
    private byte[] selectedTextureBytes = new byte[0];
    private ItemType currentItemType = ItemType.BASIC;
    private int customTextureWidth = 16;
    private int customTextureHeight = 16;
    private int itemPropertiesScroll = 0;
    private Identifier customTextureLocation = null;
    private Button saveButton;

    private ItemRarity currentRarity = ItemRarity.COMMON;
    private EditBox recipeNameBox;
    private EditBox itemDisplayNameBox;
    private EditBox processingTimeBox;
    private EditBox maxStackSizeBox;
    private EditBox nutritionBox;
    private EditBox saturationBox;
    private EditBox eatTimeBox;
    private EditBox durabilityBox;
    private EditBox enchantabilityBox;
    private EditBox miningLevelBox;
    private EditBox miningSpeedBox;
    private EditBox burnTimeBox;
    private EditBox loreBox;
    private EditBox attackDamageBox;
    private EditBox attackSpeedBox;
    private EditBox searchBox;
    private List<SearchEntry> filteredItems = new ArrayList<>();
    private Button toggleButton;
    private boolean isSidebarVisible = true;
    private int scrollOffset = 0;

    private IngredientHolder draggedItem = new IngredientHolder();
    private IngredientHolder hoveredIngredient = null;

    private List<ProvideRecipesPayload.RecipeInfo> discoveredRecipes = new ArrayList<>();
    private int discoveryIndex = 0;

    protected EditorScreen(Identifier targetItem) {
        super(Component.literal("Recipe Editor: " + targetItem.toString()));
        this.targetItem = targetItem;

        for (int i = 0; i < 9; i++) inputs[i] = new IngredientHolder();

        ItemStack result = BuiltInRegistries.ITEM.getOptional(targetItem)
                .map(ItemStack::new).orElse(ItemStack.EMPTY);
        outputs.add(new OutputData(result, 1.0f));
    }

    @Override
    protected void init() {
        super.init();

        this.searchBox = new EditBox(this.font, 10, 10, 100, 20, Component.translatable("gui.simplyrecipes.search_hint"));
        this.searchBox.setResponder(this::updateSearch);
        this.addRenderableWidget(this.searchBox);

        this.recipeNameBox = new EditBox(this.font, 0, 0, 100, 20, Component.translatable("gui.simplyrecipes.recipe_name"));
        this.recipeNameBox.setValue(targetItem.toString());
        this.recipeNameBox.setMaxLength(128);
        this.addRenderableWidget(this.recipeNameBox);

        this.itemDisplayNameBox = new EditBox(this.font, 0, 0, 100, 20, Component.literal("Display Name"));
        this.itemDisplayNameBox.setValue("New Item");
        this.addRenderableWidget(this.itemDisplayNameBox);

        this.processingTimeBox = new EditBox(this.font, 0, 0, 40, 20, Component.translatable("gui.simplyrecipes.ticks"));
        this.processingTimeBox.setValue("200");
        this.addRenderableWidget(this.processingTimeBox);

        this.maxStackSizeBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("64"));
        this.maxStackSizeBox.setValue("64");
        this.addRenderableWidget(this.maxStackSizeBox);

        this.nutritionBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Nutrition"));
        this.nutritionBox.setValue("4");
        this.addRenderableWidget(this.nutritionBox);

        this.saturationBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Saturation"));
        this.saturationBox.setValue("0.3");
        this.addRenderableWidget(this.saturationBox);

        this.eatTimeBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Eat Ticks"));
        this.eatTimeBox.setValue("32");
        this.addRenderableWidget(this.eatTimeBox);

        this.durabilityBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Durability"));
        this.durabilityBox.setValue("0");
        this.addRenderableWidget(this.durabilityBox);

        this.enchantabilityBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Enchantability"));
        this.enchantabilityBox.setValue("10");
        this.addRenderableWidget(this.enchantabilityBox);

        this.miningLevelBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Mining Level"));
        this.miningLevelBox.setValue("1");
        this.addRenderableWidget(this.miningLevelBox);

        this.miningSpeedBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Mining Speed"));
        this.miningSpeedBox.setValue("4.0");
        this.addRenderableWidget(this.miningSpeedBox);

        this.burnTimeBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Burn Time"));
        this.burnTimeBox.setValue("0");
        this.addRenderableWidget(this.burnTimeBox);

        this.attackDamageBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Damage"));
        this.attackDamageBox.setValue("1.0");
        this.addRenderableWidget(this.attackDamageBox);

        this.attackSpeedBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Speed"));
        this.attackSpeedBox.setValue("-2.4");
        this.addRenderableWidget(this.attackSpeedBox);

        this.loreBox = new EditBox(this.font, 0, 0, 200, 20, Component.literal("Tooltip"));
        this.loreBox.setValue("");
        this.loreBox.setMaxLength(256);
        this.addRenderableWidget(this.loreBox);

        this.toggleButton = Button.builder(Component.translatable("gui.simplyrecipes.close"), (btn) -> {
            this.toggleSidebar();
        }).pos(10, this.height - 30).size(100, 20).build();

        this.categorySelector = Button.builder(Component.literal(currentCategory.getDisplayName()), (btn) -> {
            this.isDropdownOpen = !this.isDropdownOpen;
        }).size(75, 20).build();

        this.itemTypeSelector = Button.builder(Component.literal(currentItemType.label), (btn) -> {
            this.isItemTypeDropdownOpen = !this.isItemTypeDropdownOpen;
        }).size(75, 20).build();

        this.recipeTypeSelector = Button.builder(Component.literal(currentRecipeType.getDisplayName()), (btn) -> {
            this.isTypeDropdownOpen = !this.isTypeDropdownOpen;
        }).size(75, 20).build();

        this.raritySelector = Button.builder(Component.literal(currentRarity.label), (btn) -> {
            this.isRarityDropdownOpen = !this.isRarityDropdownOpen;
        }).size(75, 20).build();

        this.selectTextureButton = Button.builder(Component.literal("Select Texture"), (btn) -> {
            this.openFilePicker();
        }).size(100, 20).build();

        this.overrideCheckbox = Checkbox.builder(Component.empty(), this.font)
                .pos(0, 0)
                .selected(isOverride)
                .onValueChange((cb, val) -> this.isOverride = val)
                .build();

        this.fireResistantCheckbox = Checkbox.builder(Component.literal("Fire Resistant"), this.font)
                .pos(0, 0)
                .selected(isFireResistant)
                .onValueChange((cb, val) -> this.isFireResistant = val)
                .build();

        this.glintCheckbox = Checkbox.builder(Component.empty(), this.font)
                .pos(0, 0)
                .selected(hasGlint)
                .onValueChange((cb, val) -> this.hasGlint = val)
                .build();

        this.foodCheckbox = Checkbox.builder(Component.empty(), this.font)
                .pos(0, 0)
                .selected(isFood)
                .onValueChange((cb, val) -> this.isFood = val)
                .build();

        this.isPickaxeCheckbox = Checkbox.builder(Component.empty(), this.font)
                .pos(0, 0)
                .selected(false)
                .onValueChange((cb, val) -> {})
                .build();

        this.alwaysEdibleCheckbox = Checkbox.builder(Component.empty(), this.font)
                .pos(0, 0)
                .selected(alwaysEdible)
                .onValueChange((cb, val) -> this.alwaysEdible = val)
                .build();

        this.shapelessCheckbox = Checkbox.builder(Component.empty(), this.font)
                .pos(0, 0)
                .selected(isShapeless)
                .onValueChange((cb, val) -> this.isShapeless = val)
                .build();

        this.saveButton = Button.builder(Component.translatable("gui.simplyrecipes.save"), (btn) -> {
            this.saveRecipe();
        }).size(50, 20).build();

        this.addRenderableWidget(this.toggleButton);
        this.addRenderableWidget(this.categorySelector);
        this.addRenderableWidget(this.itemTypeSelector);
        this.addRenderableWidget(this.recipeTypeSelector);
        this.addRenderableWidget(this.raritySelector);
        this.addRenderableWidget(this.selectTextureButton);
        this.addRenderableWidget(this.overrideCheckbox);
        this.addRenderableWidget(this.fireResistantCheckbox);
        this.addRenderableWidget(this.isPickaxeCheckbox);
        this.addRenderableWidget(this.durabilityBox);
        this.addRenderableWidget(this.enchantabilityBox);
        this.addRenderableWidget(this.burnTimeBox);
        this.addRenderableWidget(this.glintCheckbox);
        this.addRenderableWidget(this.foodCheckbox);
        this.addRenderableWidget(this.alwaysEdibleCheckbox);
        this.addRenderableWidget(this.shapelessCheckbox);
        this.addRenderableWidget(this.saveButton);

        updateSearch("");
    }

    private void openFilePicker() {
        PointerBuffer filters = MemoryUtil.memAllocPointer(1);
        filters.put(MemoryUtil.memUTF8("*.png"));
        filters.flip();

        String result = TinyFileDialogs.tinyfd_openFileDialog("Select Item Texture", "", filters, "PNG Files", false);
        MemoryUtil.memFree(filters);

        if (result != null) {
            try {
                Path path = Paths.get(result);
                byte[] bytes = Files.readAllBytes(path);
                this.selectedTextureBytes = bytes;

                NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                this.customTextureWidth = image.getWidth();
                this.customTextureHeight = image.getHeight();

                this.customTextureLocation = Identifier.fromNamespaceAndPath(SimplyRecipes.MODID, "temp_preview_" + System.currentTimeMillis());
                DynamicTexture texture = new DynamicTexture(() -> "SimplyRecipesPreview", image);
                Minecraft.getInstance().getTextureManager().register(this.customTextureLocation, texture);

                this.selectTextureButton.setMessage(Component.literal("Texture Loaded!"));
            } catch (Exception e) {
                this.selectTextureButton.setMessage(Component.literal("Error Loading!"));
            }
        }
    }

    private void setCategory(Category category) {
        this.currentCategory = category;
        this.categorySelector.setMessage(Component.literal(category.getDisplayName()));
        this.isDropdownOpen = false;
    }

    private void setRecipeType(RecipeType type) {
        this.currentRecipeType = type;
        this.recipeTypeSelector.setMessage(Component.literal(type.getDisplayName()));
        if (this.processingTimeBox != null && isCookingMode()) {
            this.processingTimeBox.setValue(String.valueOf(type.getDefaultTicks()));
        }
        this.isTypeDropdownOpen = false;
    }

    private void setItemType(ItemType type) {
        this.currentItemType = type;
        this.itemTypeSelector.setMessage(Component.literal(type.label));
        this.isItemTypeDropdownOpen = false;
    }

    private void setRarity(ItemRarity rarity) {
        this.currentRarity = rarity;
        this.raritySelector.setMessage(Component.literal(rarity.label));
        this.isRarityDropdownOpen = false;
    }

    public void receiveDiscoveredRecipes(List<ProvideRecipesPayload.RecipeInfo> recipes) {
        this.discoveredRecipes = recipes;
        this.discoveryIndex = 0;
        if (!recipes.isEmpty()) {
            this.recipeNameBox.setValue(recipes.get(0).id().toString());
        }
    }

    private void saveRecipe() {
        if (currentCategory == Category.CREATE_ITEM) {
            String itemId = recipeNameBox.getValue();
            String displayName = itemDisplayNameBox.getValue();
            int maxStack = 64;
            try {
                maxStack = Integer.parseInt(maxStackSizeBox.getValue());
            } catch (NumberFormatException ignored) {}

            JsonObject itemJson = new JsonObject();
            itemJson.addProperty("id", itemId);
            itemJson.addProperty("name", displayName);
            itemJson.addProperty("max_stack_size", maxStack);
            itemJson.addProperty("fire_resistant", isFireResistant);
            itemJson.addProperty("has_glint", hasGlint);
            itemJson.addProperty("rarity", currentRarity.rarity.name());
            itemJson.addProperty("item_type", currentItemType.name());
            itemJson.addProperty("lore", loreBox.getValue());

            try {
                itemJson.addProperty("burn_time", Integer.parseInt(burnTimeBox.getValue()));
                itemJson.addProperty("durability", Integer.parseInt(durabilityBox.getValue()));
                itemJson.addProperty("enchantability", Integer.parseInt(enchantabilityBox.getValue()));
            } catch (NumberFormatException ignored) {}

            JsonObject foodJson = new JsonObject();
            foodJson.addProperty("is_food", isFood);
            if (currentItemType == ItemType.FOOD) {
                int nutrition = 4;
                float saturation = 0.3f;
                try {
                    nutrition = Integer.parseInt(nutritionBox.getValue());
                    saturation = Float.parseFloat(saturationBox.getValue());
                } catch (NumberFormatException ignored) {}
                foodJson.addProperty("nutrition", nutrition);
                foodJson.addProperty("saturation", saturation);
                foodJson.addProperty("always_edible", alwaysEdible);
                try { foodJson.addProperty("eat_ticks", Integer.parseInt(eatTimeBox.getValue())); } catch (Exception ignored) {}
            }
            itemJson.add("food", foodJson);

            if (currentItemType == ItemType.TOOL || currentItemType == ItemType.WEAPON) {
                float damage = 1.0f;
                float speed = -2.4f;
                int mLevel = 1;
                float mSpeed = 4.0f;
                try {
                    damage = Float.parseFloat(attackDamageBox.getValue());
                    speed = Float.parseFloat(attackSpeedBox.getValue());
                    mLevel = Integer.parseInt(miningLevelBox.getValue());
                    mSpeed = Float.parseFloat(miningSpeedBox.getValue());
                } catch (NumberFormatException ignored) {}
                itemJson.addProperty("attack_damage", damage);
                itemJson.addProperty("attack_speed", speed);
                itemJson.addProperty("mining_level", mLevel);
                itemJson.addProperty("mining_speed", mSpeed);
            }

            if (Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().getConnection().send(new CreateItemPayload(GSON.toJson(itemJson), selectedTextureBytes));
            }

            Minecraft.getInstance().player.sendSystemMessage(
                Component.literal("§bItem creation triggered for: §f" + itemId)
            );
            return;
        }

        if (currentCategory == Category.REMOVE_RECIPE) {
            if (discoveredRecipes.isEmpty()) return;
            Identifier recipeId = discoveredRecipes.get(discoveryIndex).id();
            JsonObject deletionJson = RecipeGenerator.createDeletionTemplate();

            if (Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().getConnection().send(new SaveRecipePayload(recipeId, GSON.toJson(deletionJson)));
                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§eDisabling recipe: §f" + recipeId.toString()));

                discoveredRecipes.remove(discoveryIndex);
                if (discoveredRecipes.isEmpty()) {
                    discoveryIndex = 0;
                    this.recipeNameBox.setValue("");
                } else {
                    discoveryIndex = Math.min(discoveryIndex, discoveredRecipes.size() - 1);
                    this.recipeNameBox.setValue(discoveredRecipes.get(discoveryIndex).id().toString());
                }
            }
            return;
        }

        ItemStack resultStack = outputs.get(0).stack;
        if (resultStack.isEmpty()) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal("§cCannot save: Output is empty!"));
            return;
        }

        Identifier resultId = BuiltInRegistries.ITEM.getKey(resultStack.getItem());
        Identifier recipeId = getFinalRecipeId(resultId);
        JsonObject recipeJson = null;

        int cookTime;
        try {
            cookTime = Integer.parseInt(processingTimeBox.getValue());
        } catch (NumberFormatException e) {
            cookTime = currentRecipeType.getDefaultTicks();
        }

        switch (currentRecipeType) {
            case CRAFTING -> {
                recipeJson = isShapeless ?
                        RecipeGenerator.createShapelessRecipeTemplate(resultId, resultStack.getCount(), getIngredientsFromGrid()) :
                        RecipeGenerator.createShapedRecipeTemplate(resultId, resultStack.getCount(), getShapedGridIngredients());
            }
            case SMELTING, BLASTING, SMOKING, CAMPFIRE_COOKING -> {
                if (inputs[0].isEmpty()) return;
                String ingredient = inputs[0].getJsonName();
                recipeJson = RecipeGenerator.createCookingRecipeTemplate(getCookingType(), resultId, ingredient, cookTime, 0.1f);
            }
            case STONECUTTING -> {
                if (inputs[0].isEmpty()) return;
                String ingredient = inputs[0].getJsonName();
                recipeJson = RecipeGenerator.createStonecuttingRecipeTemplate(resultId, resultStack.getCount(), ingredient);
            }
            case SMITHING -> {
                if (inputs[0].isEmpty() || inputs[1].isEmpty() || inputs[2].isEmpty()) return;
                String template = inputs[0].getJsonName();
                String base = inputs[1].getJsonName();
                String addition = inputs[2].getJsonName();
                recipeJson = RecipeGenerator.createSmithingRecipeTemplate(resultId, template, base, addition);
            }
        }

        if (recipeJson != null) {
            if (Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().getConnection().send(new SaveRecipePayload(recipeId, GSON.toJson(recipeJson)));
            }

            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("§eSending recipe data to server... §f(" + recipeId.toString() + ")")
            );
        }
    }

    private String[] getShapedGridIngredients() {
        String[] ingredients = new String[9];
        for (int i = 0; i < 9; i++) {
            ingredients[i] = inputs[i].isEmpty() ? "" : inputs[i].getJsonName();
        }
        return ingredients;
    }

    private List<String> getIngredientsFromGrid() {
        List<String> ingredients = new ArrayList<>();
        for (IngredientHolder holder : inputs) {
            if (!holder.isEmpty()) {
                ingredients.add(holder.getJsonName());
            }
        }
        return ingredients;
    }

    private Identifier getFinalRecipeId(Identifier resultId) {
        if (isOverride) return resultId;

        String rawInput = recipeNameBox.getValue().toLowerCase();
        if (rawInput.contains(":")) {
            String[] parts = rawInput.split(":", 2);
            return Identifier.fromNamespaceAndPath(parts[0], parts[1].replaceAll("[^a-z0-9/._-]", ""));
        }

        String fileName = rawInput.replaceAll("[^a-z0-9/._-]", "");
        if (fileName.isEmpty()) fileName = resultId.getPath() + "_custom";
        return Identifier.fromNamespaceAndPath(resultId.getNamespace(), fileName);
    }

    private String getCookingType() {
        return switch(currentRecipeType) {
                case SMELTING -> "minecraft:smelting";
                case BLASTING -> "minecraft:blasting";
                case SMOKING -> "minecraft:smoking";
                case CAMPFIRE_COOKING -> "minecraft:campfire_cooking";
            default -> "minecraft:crafting";
            };
    }

    private ItemStack getIconForRecipeType(String type) {
        return switch (type) {
            case "minecraft:crafting" -> RecipeType.CRAFTING.getStationStack();
            case "minecraft:smelting" -> RecipeType.SMELTING.getStationStack();
            case "minecraft:blasting" -> RecipeType.BLASTING.getStationStack();
            case "minecraft:smoking" -> RecipeType.SMOKING.getStationStack();
            case "minecraft:campfire_cooking" -> RecipeType.CAMPFIRE_COOKING.getStationStack();
            case "minecraft:stonecutting" -> RecipeType.STONECUTTING.getStationStack();
            case "minecraft:smithing" -> RecipeType.SMITHING.getStationStack();
            default -> BuiltInRegistries.ITEM.get(Identifier.fromNamespaceAndPath("minecraft", "barrier")).map(ItemStack::new).orElse(ItemStack.EMPTY);
        };
    }

    private boolean isCookingMode() {
        return currentRecipeType == RecipeType.SMELTING || currentRecipeType == RecipeType.BLASTING ||
                currentRecipeType == RecipeType.SMOKING || currentRecipeType == RecipeType.CAMPFIRE_COOKING;
    }

    private void toggleSidebar() {
        this.isSidebarVisible = !this.isSidebarVisible;
        this.searchBox.visible = isSidebarVisible;
        this.toggleButton.setMessage(Component.translatable(isSidebarVisible ? "gui.simplyrecipes.close" : "gui.simplyrecipes.search"));
    }

    private void updateSearch(String query) {
        String lowerQuery = query.toLowerCase();
        this.filteredItems = new ArrayList<>();

        if (lowerQuery.startsWith("#")) {
            String tagPart = lowerQuery.substring(1);
            BuiltInRegistries.ITEM.getTags().forEach(tagNamed -> {
                TagKey<Item> tagKey = tagNamed.key();

                if (tagKey.location().toString().contains(tagPart)) {
                    this.filteredItems.add(new SearchEntry(tagKey));

                    getTagMembers(tagKey).forEach(stack -> {
                        this.filteredItems.add(new SearchEntry(stack, null, false));
                    });
                }
            });
        } else {
            BuiltInRegistries.ITEM.stream()
                .filter(item -> {
                    Identifier id = BuiltInRegistries.ITEM.getKey(item);
                    if (lowerQuery.startsWith("@")) {
                        return id.getNamespace().contains(lowerQuery.substring(1));
                    } else {
                        if (id.toString().contains(lowerQuery)) return true;
                        String translatedName = Component.translatable(item.getDescriptionId()).getString().toLowerCase();
                        return translatedName.contains(lowerQuery);
                    }
                })
                    .forEach(item -> this.filteredItems.add(new SearchEntry(new ItemStack(item))));
        }

        this.scrollOffset = 0;
    }

    private List<ItemStack> getTagMembers(TagKey<Item> tag) {
        List<ItemStack> members = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item).tags().anyMatch(t -> t.equals(tag))) {
                members.add(new ItemStack(item));
            }
        }
        return members;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.hoveredIngredient = null;
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 10, 0xFFFFFF);

        boolean isRemoveMode = currentCategory == Category.REMOVE_RECIPE;
        boolean isItemMode = (currentCategory == Category.CREATE_ITEM);

        int centerX = (isSidebarVisible && !isItemMode) ? (this.width + 115) / 2 : this.width / 2;
        int centerY = this.height / 2;

        renderWorkstationBackground(graphics, centerX, centerY, isItemMode ? 350 : 160, isItemMode ? 240 : 160);

        this.categorySelector.setX(centerX - 77);
        this.categorySelector.setY(isItemMode ? centerY - 105 : centerY - 75);

        this.recipeTypeSelector.setX(centerX + 2);
        this.recipeTypeSelector.setY(centerY - 75);
        this.recipeTypeSelector.visible = currentCategory == Category.ADD_RECIPE;

        this.itemTypeSelector.setX(centerX + 2);
        this.itemTypeSelector.setY(isItemMode ? centerY - 105 : centerY - 75);
        this.itemTypeSelector.visible = isItemMode;

        this.toggleButton.visible = !isItemMode;
        this.raritySelector.setX(centerX + 2); this.raritySelector.setY(centerY - 75);
        this.raritySelector.visible = false;

        this.selectTextureButton.setX(centerX - 50);
        this.selectTextureButton.setY(centerY - 10);
        this.selectTextureButton.visible = isItemMode;

        this.overrideCheckbox.setX(centerX - 75);
        this.overrideCheckbox.setY(centerY + 15);
        this.overrideCheckbox.visible = !isRemoveMode && !isItemMode;

        this.fireResistantCheckbox.setX(centerX - 75);
        this.fireResistantCheckbox.setY(centerY + 30);
        this.fireResistantCheckbox.visible = isItemMode;

        this.shapelessCheckbox.setX(centerX - 75);
        this.shapelessCheckbox.setY(centerY + 35);
        this.shapelessCheckbox.visible = currentCategory == Category.ADD_RECIPE && currentRecipeType == RecipeType.CRAFTING;

        this.processingTimeBox.setX(centerX - 75);
        this.processingTimeBox.setY(centerY + 35);
        this.processingTimeBox.visible = isCookingMode();

        this.saveButton.setX(centerX + 25);
        this.saveButton.setY(centerY + 55);

        if (isItemMode) {
            int listX = centerX - 165;
            int listTop = centerY - 80;
            int listBottom = centerY + 85;
            int scroll = itemPropertiesScroll;

            this.recipeNameBox.setX(centerX - 165);
            this.recipeNameBox.setWidth(275);

            this.itemDisplayNameBox.setX(listX);
            this.itemDisplayNameBox.setY(listTop + 15 - scroll);
            this.itemDisplayNameBox.setWidth(200);
            this.itemDisplayNameBox.visible = true;

            this.raritySelector.setX(listX);
            this.raritySelector.setY(listTop + 50 - scroll);
            this.raritySelector.visible = true;

            this.maxStackSizeBox.setX(listX + 85);
            this.maxStackSizeBox.setY(listTop + 50 - scroll);
            this.maxStackSizeBox.visible = true;

            this.fireResistantCheckbox.setX(listX);
            this.fireResistantCheckbox.setY(listTop + 85 - scroll);
            this.fireResistantCheckbox.visible = true;

            this.glintCheckbox.setX(listX + 110);
            this.glintCheckbox.setY(listTop + 85 - scroll);
            this.glintCheckbox.visible = true;

            int nextY = listTop + 120;

            if (currentItemType == ItemType.FOOD) {
                this.nutritionBox.setX(listX);
                this.nutritionBox.setY(nextY - scroll);
                this.nutritionBox.visible = true;
                this.saturationBox.setX(listX + 55);
                this.saturationBox.setY(nextY - scroll);
                this.saturationBox.visible = true;
                nextY += 35;

                this.eatTimeBox.setX(listX);
                this.eatTimeBox.setY(nextY - scroll);
                this.eatTimeBox.visible = true;
                nextY += 35;

                this.alwaysEdibleCheckbox.setX(listX);
                this.alwaysEdibleCheckbox.setY(nextY - scroll);
                this.alwaysEdibleCheckbox.visible = true;
                nextY += 35;
            } else {
                this.nutritionBox.visible = false;
                this.saturationBox.visible = false;
                this.eatTimeBox.visible = false;
                this.alwaysEdibleCheckbox.visible = false;
            }

            if (currentItemType == ItemType.TOOL || currentItemType == ItemType.WEAPON) {
                this.attackDamageBox.setX(listX);
                this.attackDamageBox.setY(nextY - scroll);
                this.attackDamageBox.visible = true;
                this.attackSpeedBox.setX(listX + 55);
                this.attackSpeedBox.setY(nextY - scroll);
                this.attackSpeedBox.visible = true;
                nextY += 35;

                this.durabilityBox.setX(listX);
                this.durabilityBox.setY(nextY - scroll);
                this.durabilityBox.visible = true;
                this.enchantabilityBox.setX(listX + 55);
                this.enchantabilityBox.setY(nextY - scroll);
                this.enchantabilityBox.visible = true;
                nextY += 35;

                this.miningLevelBox.setX(listX);
                this.miningLevelBox.setY(nextY - scroll);
                this.miningLevelBox.visible = true;
                this.miningSpeedBox.setX(listX + 55);
                this.miningSpeedBox.setY(nextY - scroll);
                this.miningSpeedBox.visible = true;
                nextY += 35;
            } else {
                this.attackDamageBox.visible = false;
                this.attackSpeedBox.visible = false;
                this.durabilityBox.visible = false;
                this.enchantabilityBox.visible = false;
                this.miningLevelBox.visible = false;
                this.miningSpeedBox.visible = false;
            }

            this.burnTimeBox.setX(listX);
            this.burnTimeBox.setY(nextY - scroll);
            this.burnTimeBox.visible = true;
            nextY += 35;

            this.loreBox.setX(listX);
            this.loreBox.setY(nextY - scroll);
            this.loreBox.visible = true;
            nextY += 35;

            this.selectTextureButton.setX(listX);
            this.selectTextureButton.setY(nextY + 10 - scroll);
            this.selectTextureButton.visible = true;

            hideIfOOB(this.itemDisplayNameBox, listTop, listBottom);
            hideIfOOB(this.raritySelector, listTop, listBottom);
            hideIfOOB(this.maxStackSizeBox, listTop, listBottom);
            hideIfOOB(this.fireResistantCheckbox, listTop, listBottom);
            hideIfOOB(this.glintCheckbox, listTop, listBottom);
            hideIfOOB(this.nutritionBox, listTop, listBottom);
            hideIfOOB(this.saturationBox, listTop, listBottom);
            hideIfOOB(this.eatTimeBox, listTop, listBottom);
            hideIfOOB(this.attackDamageBox, listTop, listBottom);
            hideIfOOB(this.attackSpeedBox, listTop, listBottom);
            hideIfOOB(this.durabilityBox, listTop, listBottom);
            hideIfOOB(this.enchantabilityBox, listTop, listBottom);
            hideIfOOB(this.miningLevelBox, listTop, listBottom);
            hideIfOOB(this.miningSpeedBox, listTop, listBottom);
            hideIfOOB(this.burnTimeBox, listTop, listBottom);
            hideIfOOB(this.loreBox, listTop, listBottom);
            hideIfOOB(this.alwaysEdibleCheckbox, listTop, listBottom);
            hideIfOOB(this.selectTextureButton, listTop, listBottom);

            this.recipeNameBox.setY(centerY + 95);
            this.recipeNameBox.visible = true;

            this.saveButton.setX(centerX + 115);
            this.saveButton.setY(centerY + 95);
        } else {
            this.recipeNameBox.setWidth(100);
            this.recipeNameBox.setX(centerX - 75); 
            this.recipeNameBox.setY(centerY + 55);
            this.saveButton.setX(centerX + 25);
            this.saveButton.setY(centerY + 55);
            this.recipeNameBox.visible = !isOverride || isRemoveMode;
            this.itemDisplayNameBox.visible = false;
            this.maxStackSizeBox.visible = false;
            this.selectTextureButton.visible = false;
            this.nutritionBox.visible = false;
            this.saturationBox.visible = false;
            this.eatTimeBox.visible = false;
            this.attackDamageBox.visible = false;
            this.attackSpeedBox.visible = false;
            this.durabilityBox.visible = false;
            this.enchantabilityBox.visible = false;
            this.miningLevelBox.visible = false;
            this.miningSpeedBox.visible = false;
            this.burnTimeBox.visible = false;
            this.loreBox.visible = false;
            this.raritySelector.visible = false;
            this.glintCheckbox.visible = false;
            this.alwaysEdibleCheckbox.visible = false;
            this.isPickaxeCheckbox.visible = false;
        }

        Component saveLabel = Component.translatable("gui.simplyrecipes.save");
        if (isRemoveMode) saveLabel = Component.literal("Remove");
        else if (isItemMode) saveLabel = Component.literal("Create");
        this.saveButton.setMessage(saveLabel);

        if (isRemoveMode) {
            this.processingTimeBox.visible = false;
            if (discoveredRecipes.isEmpty()) {
                String line1 = "Select an item";
                String line2 = "to lookup recipes";
                graphics.text(this.font, Component.literal(line1), centerX - (this.font.width(line1) / 2), centerY - 15, 0xFF3F3F3F, false);
                graphics.text(this.font, Component.literal(line2), centerX - (this.font.width(line2) / 2), centerY - 5, 0xFF3F3F3F, false);
            } else {
                ProvideRecipesPayload.RecipeInfo current = discoveredRecipes.get(discoveryIndex);

                String countText = "Recipe " + (discoveryIndex + 1) + " of " + discoveredRecipes.size();
                String typeText = current.type().replace("minecraft:", "");

                graphics.text(this.font, Component.literal(countText), centerX - (this.font.width(countText) / 2), centerY - 35, 0xFF3F3F3F, false);
                graphics.text(this.font, Component.literal(typeText), centerX - (this.font.width(typeText) / 2), centerY - 25, 0xFF3F3F3F, false);

                graphics.fill(centerX - 75, centerY - 10, centerX - 55, centerY + 5, 0xFF707070);
                graphics.text(this.font, Component.literal("<"), centerX - 70, centerY - 6, 0xFFFFFFFF);

                graphics.fill(centerX + 55, centerY - 10, centerX + 75, centerY + 5, 0xFF707070);
                graphics.text(this.font, Component.literal(">"), centerX + 62, centerY - 6, 0xFFFFFFFF);

                graphics.fakeItem(getIconForRecipeType(current.type()), centerX - 8, centerY - 15);
            }
        } else if (isItemMode) {
            int previewX = centerX + 130;
            int previewY = centerY - 100;
            graphics.fill(previewX - 1, previewY - 1, previewX + 33, previewY + 33, 0xFF707070);
            graphics.fill(previewX, previewY, previewX + 32, previewY + 32, 0xFFBDBDBD);

            if (customTextureLocation != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, customTextureLocation, previewX, previewY,
                    0, 0, 32, 32,
                    customTextureWidth, customTextureHeight, customTextureWidth, customTextureHeight);
            } else {
                graphics.blit(RenderPipelines.GUI_TEXTURED, MissingTextureAtlasSprite.getLocation(), previewX, previewY,
                    0, 0, 32, 32, 16, 16, 16, 16);
            }
        } else if (currentRecipeType == RecipeType.CRAFTING) {
            renderVanillaLayout(graphics, centerX, centerY, mouseX, mouseY);
        } else {
            renderMachineLayout(graphics, centerX, centerY, mouseX, mouseY);
        }

        if (isSidebarVisible && !isItemMode) {
            renderSidebar(graphics, mouseX, mouseY);
            this.searchBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        renderConfigurationRows(graphics, centerX, centerY, mouseX, mouseY, partialTick);

        if (this.recipeNameBox.visible) this.recipeNameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.maxStackSizeBox.visible) this.maxStackSizeBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.nutritionBox.visible) this.nutritionBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.saturationBox.visible) this.saturationBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.eatTimeBox.visible) this.eatTimeBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.attackDamageBox.visible) this.attackDamageBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.attackSpeedBox.visible) this.attackSpeedBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.durabilityBox.visible) this.durabilityBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.enchantabilityBox.visible) this.enchantabilityBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.miningLevelBox.visible) this.miningLevelBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.miningSpeedBox.visible) this.miningSpeedBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.burnTimeBox.visible) this.burnTimeBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.loreBox.visible) this.loreBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.itemDisplayNameBox.visible) this.itemDisplayNameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.raritySelector.visible) this.raritySelector.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.saveButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.selectTextureButton.visible) this.selectTextureButton.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (isTypeDropdownOpen) renderTypeDropdownList(graphics, mouseX, mouseY);
        if (isItemTypeDropdownOpen) renderItemTypeDropdownList(graphics, mouseX, mouseY);
        if (isRarityDropdownOpen) renderRarityDropdownList(graphics, mouseX, mouseY);
        if (isDropdownOpen) renderDropdownList(graphics, mouseX, mouseY);

        renderIngredient(graphics, draggedItem, mouseX - 8, mouseY - 8);

        if (draggedItem.isEmpty() && !isAnyDropdownOpen() && hoveredIngredient != null && !hoveredIngredient.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            if (hoveredIngredient.isTag) {
                lines.add(Component.literal("#" + hoveredIngredient.tag.location().toString()));
            } else {
                lines.addAll(this.getTooltipFromItem(Minecraft.getInstance(), hoveredIngredient.stack));
            }

            List<ClientTooltipComponent> tooltipLines = lines.stream().map(c -> ClientTooltipComponent.create(c.getVisualOrderText())).collect(Collectors.toList());
            graphics.tooltip(this.font, tooltipLines, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
        }
    }

    private void hideIfOOB(net.minecraft.client.gui.components.AbstractWidget widget, int min, int max) {
        if (widget.getY() < min || (widget.getY() + widget.getHeight()) > max) {
            widget.visible = false;
        }
    }

    private void renderConfigurationRows(GuiGraphicsExtractor graphics, int centerX, int centerY, int mouseX, int mouseY, float partialTick) {
        boolean isItemMode = currentCategory == Category.CREATE_ITEM;
        int scroll = isItemMode ? itemPropertiesScroll : 0;

        if (this.overrideCheckbox.visible) {
            this.overrideCheckbox.extractRenderState(graphics, mouseX, mouseY, partialTick);
            graphics.text(this.font, Component.translatable("gui.simplyrecipes.override"), centerX - 50, centerY + 20, 0xFF3F3F3F, false);
        }
        if (this.shapelessCheckbox.visible) {
            this.shapelessCheckbox.extractRenderState(graphics, mouseX, mouseY, partialTick);
            graphics.text(this.font, Component.translatable("gui.simplyrecipes.shapeless"), centerX - 50, centerY + 40, 0xFF3F3F3F, false);
        }
        if (this.processingTimeBox.visible) {
            this.processingTimeBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
            graphics.text(this.font, Component.translatable("gui.simplyrecipes.ticks"), centerX - 30, centerY + 40, 0xFF3F3F3F, false);
        }

        if (isItemMode) {
            int listX = centerX - 165;
            int listTop = centerY - 80;
            int listBottom = centerY + 85;

            drawListText(graphics, "Display Name", listX, listTop + 15 - scroll, 0xFF3F3F3F, listTop, centerY + 45);
            drawListText(graphics, "Rarity", listX, listTop + 50 - scroll, 0xFF3F3F3F, listTop, listBottom);
            drawListText(graphics, "Max Stack", listX + 85, listTop + 50 - scroll, 0xFF3F3F3F, listTop, listBottom);
            drawListText(graphics, "Fire Resistance", listX, listTop + 85 - scroll, 0xFF3F3F3F, listTop, listBottom);
            drawListText(graphics, "Glint", listX + 110, listTop + 85 - scroll, 0xFF3F3F3F, listTop, listBottom);
            
            int textY = listTop + 120;
            if (currentItemType == ItemType.FOOD) {
                drawListText(graphics, "Nutrition   Saturate", listX, textY - scroll, 0xFF3F3F3F, listTop, listBottom);
                textY += 35;
                drawListText(graphics, "Eat Time (Ticks)", listX, textY - scroll, 0xFF3F3F3F, listTop, listBottom);
                textY += 35;
                drawListText(graphics, "Always Edible", listX, textY - scroll, 0xFF3F3F3F, listTop, listBottom);
                textY += 35;
            } else if (currentItemType == ItemType.TOOL || currentItemType == ItemType.WEAPON) {
                drawListText(graphics, "Damage     Speed", listX, textY - scroll, 0xFF3F3F3F, listTop, listBottom);
                textY += 35;
                drawListText(graphics, "Durability   Enchant", listX, textY - scroll, 0xFF3F3F3F, listTop, listBottom);
                textY += 35;
                drawListText(graphics, "Mining Lvl  Speed", listX, textY - scroll, 0xFF3F3F3F, listTop, listBottom);
                textY += 35;
            }

            drawListText(graphics, "Burn Time (Fuel)", listX, textY - scroll, 0xFF3F3F3F, listTop, listBottom);
            textY += 35;

            drawListText(graphics, "Tooltip", listX, textY - scroll, 0xFF3F3F3F, listTop, listBottom);
            textY += 35;

            drawListText(graphics, "Texture Import", listX, textY + 10 - scroll, 0xFF3F3F3F, listTop, listBottom);

            if (this.fireResistantCheckbox.visible) {
                this.fireResistantCheckbox.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
            if (this.glintCheckbox.visible) this.glintCheckbox.extractRenderState(graphics, mouseX, mouseY, partialTick);
            if (this.alwaysEdibleCheckbox.visible) this.alwaysEdibleCheckbox.extractRenderState(graphics, mouseX, mouseY, partialTick);

            graphics.text(this.font, Component.literal("Internal ID (Namespace:Path)"), centerX - 165, centerY + 85, 0xFF3F3F3F, false);
        }

        if (this.toggleButton.visible) this.toggleButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.categorySelector.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.itemTypeSelector.visible) this.itemTypeSelector.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.recipeTypeSelector.visible) this.recipeTypeSelector.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawListText(GuiGraphicsExtractor graphics, String text, int x, int y, int color, int min, int max) {
        if (y >= min && y <= max) {
            graphics.text(this.font, Component.literal(text), x, y - 10, color, false);
        }
    }

    private boolean isAnyDropdownOpen() {
        return isDropdownOpen || isTypeDropdownOpen || isRarityDropdownOpen || isItemTypeDropdownOpen;
    }

    private void renderIngredient(GuiGraphicsExtractor graphics, IngredientHolder ingredient, int x, int y) {
        if (ingredient.isEmpty()) return;
        if (ingredient.isTag) {
            List<ItemStack> items = getTagMembers(ingredient.tag);
            if (!items.isEmpty()) {
                int index = (int) ((System.currentTimeMillis() / 1000) % items.size());
                graphics.fakeItem(items.get(index), x, y);
            }
        } else {
            graphics.fakeItem(ingredient.stack, x, y);
        }
    }

    private void renderWorkstationBackground(GuiGraphicsExtractor graphics, int centerX, int centerY, int bgWidth, int bgHeight) {
        int x = centerX - (bgWidth / 2);
        int y = centerY - (bgHeight / 2);

        graphics.fill(x, y, x + bgWidth, y + bgHeight, 0xFFE0E0E0);
        graphics.fill(x, y + bgHeight, x + bgWidth, y + bgHeight + 1, 0xFF707070);
        graphics.fill(x + bgWidth, y, x + bgWidth + 1, y + bgHeight + 1, 0xFF707070);
    }

    private void renderDropdownList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = this.categorySelector.getX();
        int y = this.categorySelector.getY() + 20;
        int width = this.categorySelector.getWidth();

        Category[] categories = Category.values();
        int visibleCount = categories.length;
        int height = visibleCount * 20;

        graphics.fill(x, y, x + width, y + height, 0xFF202020);
        for (int i = 0; i < categories.length; i++) {
            int itemY = y + (i * 20);
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= itemY && mouseY <= itemY + 20;
            graphics.text(this.font, Component.literal(categories[i].getDisplayName()), x + 5, itemY + 6, hovered ? 0xFFFFFFA0 : 0xFFFFFFFF);
        }
    }

    private void renderTypeDropdownList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = this.recipeTypeSelector.getX();
        int y = this.recipeTypeSelector.getY() + 20;
        int width = this.recipeTypeSelector.getWidth();

        RecipeType[] types = RecipeType.values();
        int visibleCount = Math.min(types.length, MAX_DROPDOWN_VISIBLE);
        int height = visibleCount * 20;

        graphics.fill(x, y, x + width, y + height, 0xFF202020);
        graphics.fill(x - 1, y, x, y + height, 0xFFFFFFFF);
        graphics.fill(x + width, y, x + width + 1, y + height, 0xFFFFFFFF);
        graphics.fill(x, y + height, x + width, y + height + 1, 0xFFFFFFFF);

        for (int i = 0; i < visibleCount; i++) {
            int index = i + dropdownScroll;
            if (index >= types.length) break;

            int itemY = y + (i * 20);
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= itemY && mouseY <= itemY + 20;
            graphics.text(this.font, Component.literal(types[index].getDisplayName()), x + 5, itemY + 6, hovered ? 0xFFFFFFA0 : 0xFFFFFFFF);
        }
    }

    private void renderItemTypeDropdownList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = this.itemTypeSelector.getX();
        int y = this.itemTypeSelector.getY() + 20;
        int width = this.itemTypeSelector.getWidth();

        ItemType[] types = ItemType.values();
        int height = types.length * 20;

        graphics.fill(x, y, x + width, y + height, 0xFF202020);
        graphics.fill(x - 1, y, x, y + height, 0xFFFFFFFF);
        graphics.fill(x + width, y, x + width + 1, y + height, 0xFFFFFFFF);
        graphics.fill(x, y + height, x + width, y + height + 1, 0xFFFFFFFF);

        for (int i = 0; i < types.length; i++) {
            int itemY = y + (i * 20);
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= itemY && mouseY <= itemY + 20;
            graphics.text(this.font, Component.literal(types[i].label), x + 5, itemY + 6, hovered ? 0xFFFFFFA0 : 0xFFFFFFFF);
        }
    }

    private void renderRarityDropdownList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = this.raritySelector.getX();
        int y = this.raritySelector.getY() + 20;
        int width = this.raritySelector.getWidth();

        ItemRarity[] rarities = ItemRarity.values();
        int height = rarities.length * 20;

        graphics.fill(x, y, x + width, y + height, 0xFF202020);
        graphics.fill(x - 1, y, x, y + height, 0xFFFFFFFF);
        graphics.fill(x + width, y, x + width + 1, y + height, 0xFFFFFFFF);
        graphics.fill(x, y + height, x + width, y + height + 1, 0xFFFFFFFF);

        for (int i = 0; i < rarities.length; i++) {
            int itemY = y + (i * 20);
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= itemY && mouseY <= itemY + 20;
            graphics.text(this.font, Component.literal(rarities[i].label), x + 5, itemY + 6, hovered ? 0xFFFFFFA0 : 0xFFFFFFFF);
        }
    }

    private void renderSidebar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        this.searchBox.visible = true;
        graphics.fill(5, 5, 115, this.height - 5, 0x44FFFFFF);

        int startX = 10;
        int startY = 35;
        int columns = 5;
        int rows = (this.height - 70) / 20;

        for (int i = 0; i < columns * rows; i++) {
            int idx = i + scrollOffset;
            if (idx >= filteredItems.size()) break;

            int x = startX + (i % columns) * 20;
            int y = startY + (i / columns) * 20;

            SearchEntry entry = filteredItems.get(idx);
            IngredientHolder content = entry.toHolder();

            drawSlot(graphics, x, y, mouseX, mouseY, content);
            renderIngredient(graphics, content, x + 1, y + 1);
        }
    }

    private void renderVanillaLayout(GuiGraphicsExtractor graphics, int centerX, int centerY, int mouseX, int mouseY) {
        int startX = centerX - 50;
        int startY = centerY - 50;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int slotX = startX + (col * 20);
                int slotY = startY + (row * 20);
                IngredientHolder content = inputs[row * 3 + col];
                drawSlot(graphics, slotX, slotY, mouseX, mouseY, content);
                renderIngredient(graphics, content, slotX + 1, slotY + 1);
            }
        }

        graphics.fakeItem(currentRecipeType.getStationStack(), centerX + 15, centerY - 29);
        drawArrow(graphics, centerX + 14, centerY - 1);

        IngredientHolder outputHolder = new IngredientHolder();
        outputHolder.set(outputs.get(0).stack);

        drawSlot(graphics, centerX + 35, centerY - 10, mouseX, mouseY, outputHolder);
        graphics.fakeItem(outputHolder.stack, centerX + 36, centerY - 9);
    }

    private void renderMachineLayout(GuiGraphicsExtractor graphics, int centerX, int centerY, int mouseX, int mouseY) {
        int outputX = centerX + 20;

        if (currentRecipeType == RecipeType.SMITHING) {
            drawSlot(graphics, centerX - 60, centerY - 10, mouseX, mouseY, inputs[0]);
            renderIngredient(graphics, inputs[0], centerX - 59, centerY - 9);

            drawSlot(graphics, centerX - 40, centerY - 10, mouseX, mouseY, inputs[1]);
            renderIngredient(graphics, inputs[1], centerX - 39, centerY - 9);

            drawSlot(graphics, centerX - 20, centerY - 10, mouseX, mouseY, inputs[2]);
            renderIngredient(graphics, inputs[2], centerX - 19, centerY - 9);

            graphics.fakeItem(currentRecipeType.getStationStack(), centerX + 7, centerY - 29);
            drawArrow(graphics, centerX + 6, centerY - 1);
            outputX = centerX + 30;
        } else {
            drawSlot(graphics, centerX - 30, centerY - 10, mouseX, mouseY, inputs[0]);
            renderIngredient(graphics, inputs[0], centerX - 29, centerY - 9);

            graphics.fakeItem(currentRecipeType.getStationStack(), centerX - 5, centerY - 29);
            drawArrow(graphics, centerX - 6, centerY - 1);
        }

        IngredientHolder outputHolder = new IngredientHolder();
        outputHolder.set(outputs.get(0).stack);

        drawSlot(graphics, outputX, centerY - 10, mouseX, mouseY, outputHolder);
        graphics.fakeItem(outputHolder.stack, outputX + 1, centerY - 9);
    }

    private void drawSlot(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY, IngredientHolder content) {
        graphics.fill(x, y, x + 18, y + 18, 0xFFBDBDBD);
        graphics.fill(x, y, x + 18, y + 1, 0xFF707070);
        graphics.fill(x, y, x + 1, y + 18, 0xFF707070);

        if (!isAnyDropdownOpen() && mouseX >= x && mouseX <= x + 18 && mouseY >= y && mouseY <= y + 18) {
            graphics.fill(x, y, x + 18, y + 18, 0x80FFFFFF);
            this.hoveredIngredient = content;
        }
    }

    private void drawArrow(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y - 1, x + 18, y + 1, 0xFFFFFFFF);

        graphics.fill(x + 13, y - 5, x + 14, y + 5, 0xFFFFFFFF);
        graphics.fill(x + 14, y - 4, x + 15, y + 4, 0xFFFFFFFF);
        graphics.fill(x + 15, y - 3, x + 16, y + 3, 0xFFFFFFFF);
        graphics.fill(x + 16, y - 2, x + 17, y + 2, 0xFFFFFFFF);
        graphics.fill(x + 17, y - 1, x + 18, y + 1, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClicked) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        boolean isItemMode = currentCategory == Category.CREATE_ITEM;
        int centerX = (isSidebarVisible && !isItemMode) ? (this.width + 115) / 2 : this.width / 2;
        int centerY = this.height / 2;
        boolean isRemoveMode = currentCategory == Category.REMOVE_RECIPE;

        if (isTypeDropdownOpen) {
            int dx = this.recipeTypeSelector.getX();
            int dy = this.recipeTypeSelector.getY() + 20;
            int dw = this.recipeTypeSelector.getWidth();
            RecipeType[] types = RecipeType.values();
            int visibleCount = Math.min(types.length, MAX_DROPDOWN_VISIBLE);
            int dh = visibleCount * 20;
            if (mouseX >= dx && mouseX <= dx + dw && mouseY >= dy && mouseY <= dy + dh) {
                int clickedIndex = (int)((mouseY - dy) / 20) + dropdownScroll;
                if (clickedIndex < types.length) {
                    setRecipeType(types[clickedIndex]);
                    return true;
                }
            } else if (!(mouseX >= dx && mouseX <= dx + dw && mouseY >= dy - 20 && mouseY <= dy)) {
                this.isTypeDropdownOpen = false;
            }
        }

        if (isItemTypeDropdownOpen) {
            int dx = this.itemTypeSelector.getX();
            int dy = this.itemTypeSelector.getY() + 20;
            int dw = this.itemTypeSelector.getWidth();
            ItemType[] types = ItemType.values();
            int dh = types.length * 20;
            if (mouseX >= dx && mouseX <= dx + dw && mouseY >= dy && mouseY <= dy + dh) {
                int clickedIndex = (int)((mouseY - dy) / 20);
                if (clickedIndex < types.length) {
                    setItemType(types[clickedIndex]);
                    return true;
                }
            } else if (!(mouseX >= dx && mouseX <= dx + dw && mouseY >= dy - 20 && mouseY <= dy)) {
                this.isItemTypeDropdownOpen = false;
            }
        }

        if (isRarityDropdownOpen) {
            int dx = this.raritySelector.getX();
            int dy = this.raritySelector.getY() + 20;
            int dw = this.raritySelector.getWidth();
            ItemRarity[] rarities = ItemRarity.values();
            int dh = rarities.length * 20;
            if (mouseX >= dx && mouseX <= dx + dw && mouseY >= dy && mouseY <= dy + dh) {
                int clickedIndex = (int)((mouseY - dy) / 20);
                if (clickedIndex < rarities.length) {
                    setRarity(rarities[clickedIndex]);
                    return true;
                }
            } else if (!(mouseX >= dx && mouseX <= dx + dw && mouseY >= dy - 20 && mouseY <= dy)) {
                this.isRarityDropdownOpen = false;
            }
        }

        if (isDropdownOpen) {
            int dx = this.categorySelector.getX();
            int dy = this.categorySelector.getY() + 20;
            int dw = this.categorySelector.getWidth();
            Category[] categories = Category.values();
            int dh = categories.length * 20;

            if (mouseX >= dx && mouseX <= dx + dw && mouseY >= dy && mouseY <= dy + dh) {
                int clickedIndex = (int)((mouseY - dy) / 20);
                if (clickedIndex < categories.length) {
                    setCategory(categories[clickedIndex]);
                    return true;
                }
            } else if (!(mouseX >= dx && mouseX <= dx + dw && mouseY >= dy - 20 && mouseY <= dy)) {
                this.isDropdownOpen = false;
            }
        }

        if (this.recipeNameBox.visible) {
            boolean handled = this.recipeNameBox.mouseClicked(event, doubleClicked);
            if (handled) {
                this.setFocused(this.recipeNameBox);
                return true;
            }
        }

        if (this.processingTimeBox.visible) {
            boolean handled = this.processingTimeBox.mouseClicked(event, doubleClicked);
            if (handled) {
                this.setFocused(this.processingTimeBox);
                return true;
            }
        }

        if (this.itemDisplayNameBox.visible) {
            boolean handled = this.itemDisplayNameBox.mouseClicked(event, doubleClicked);
            if (handled) {
                this.setFocused(this.itemDisplayNameBox);
                return true;
            }
        }

        if (this.maxStackSizeBox.visible) {
            boolean handled = this.maxStackSizeBox.mouseClicked(event, doubleClicked);
            if (handled) {
                this.setFocused(this.maxStackSizeBox);
                return true;
            }
        }

        if (this.nutritionBox.visible) {
            if (this.nutritionBox.mouseClicked(event, doubleClicked)) {
                this.setFocused(this.nutritionBox);
                return true;
            }
        }

        if (this.saturationBox.visible) {
            if (this.saturationBox.mouseClicked(event, doubleClicked)) {
                this.setFocused(this.saturationBox);
                return true;
            }
        }

        if (this.eatTimeBox.visible && this.eatTimeBox.mouseClicked(event, doubleClicked)) { this.setFocused(this.eatTimeBox); return true; }
        if (this.miningLevelBox.visible && this.miningLevelBox.mouseClicked(event, doubleClicked)) { this.setFocused(this.miningLevelBox); return true; }
        if (this.miningSpeedBox.visible && this.miningSpeedBox.mouseClicked(event, doubleClicked)) { this.setFocused(this.miningSpeedBox); return true; }
        if (this.loreBox.visible) {
            boolean handled = this.loreBox.mouseClicked(event, doubleClicked);
            if (handled) { this.setFocused(this.loreBox); return true; }
        }

        if (this.durabilityBox.visible) {
            if (this.durabilityBox.mouseClicked(event, doubleClicked)) {
                this.setFocused(this.durabilityBox);
                return true;
            }
        }

        if (this.enchantabilityBox.visible) {
            if (this.enchantabilityBox.mouseClicked(event, doubleClicked)) {
                this.setFocused(this.enchantabilityBox);
                return true;
            }
        }

        if (this.burnTimeBox.visible) {
            if (this.burnTimeBox.mouseClicked(event, doubleClicked)) {
                this.setFocused(this.burnTimeBox);
                return true;
            }
        }

        if (this.attackDamageBox.visible) {
            if (this.attackDamageBox.mouseClicked(event, doubleClicked)) {
                this.setFocused(this.attackDamageBox);
                return true;
            }
        }

        if (this.attackSpeedBox.visible) {
            if (this.attackSpeedBox.mouseClicked(event, doubleClicked)) {
                this.setFocused(this.attackSpeedBox);
                return true;
            }
        }

        if (this.glintCheckbox.visible && this.glintCheckbox.mouseClicked(event, doubleClicked)) return true;
        if (this.alwaysEdibleCheckbox.visible && this.alwaysEdibleCheckbox.mouseClicked(event, doubleClicked)) return true;

        if (this.toggleButton.visible && this.toggleButton.mouseClicked(event, doubleClicked)) return true;
        if (this.selectTextureButton.visible && this.selectTextureButton.mouseClicked(event, doubleClicked)) return true;
        if (this.categorySelector.mouseClicked(event, doubleClicked)) return true;
        if (this.itemTypeSelector.visible && this.itemTypeSelector.mouseClicked(event, doubleClicked)) return true;
        if (this.raritySelector.visible && this.raritySelector.mouseClicked(event, doubleClicked)) return true;
        if (this.recipeTypeSelector.visible && this.recipeTypeSelector.mouseClicked(event, doubleClicked)) return true;
        if (this.overrideCheckbox.visible && this.overrideCheckbox.mouseClicked(event, doubleClicked)) return true;
        if (this.fireResistantCheckbox.visible && this.fireResistantCheckbox.mouseClicked(event, doubleClicked)) return true;
        if (this.shapelessCheckbox.visible && this.shapelessCheckbox.mouseClicked(event, doubleClicked)) return true;
        if (this.saveButton.mouseClicked(event, doubleClicked)) return true;

        if (isSidebarVisible && !isItemMode) {
            int startX = 10;
            int startY = 35;
            int columns = 5;
            int rows = (this.height - 70) / 20;
            for (int i = 0; i < columns * rows; i++) {
                int x = startX + (i % columns) * 20;
                int y = startY + (i / columns) * 20;
                if (mouseX >= x && mouseX <= (x + 18) && mouseY >= y && mouseY <= (y + 18)) {
                    int idx = i + scrollOffset;
                    if (idx < filteredItems.size()) {
                        SearchEntry entry = filteredItems.get(idx);
                        if (currentCategory == Category.REMOVE_RECIPE) {
                            Identifier id = entry.isTag ? entry.tag().location() : BuiltInRegistries.ITEM.getKey(entry.stack().getItem());
                            if (Minecraft.getInstance().getConnection() != null) {
                                Minecraft.getInstance().getConnection().send(new RequestRecipesPayload(id));
                            }
                        } else {
                            this.draggedItem = entry.toHolder();
                        }
                        return true;
                    }
                }
            }
        }

        if (isRemoveMode && !discoveredRecipes.isEmpty()) {
            if (mouseY >= centerY - 10 && mouseY <= centerY + 5) {
                if (mouseX >= centerX - 75 && mouseX <= centerX - 55) {
                    discoveryIndex = (discoveryIndex <= 0) ? discoveredRecipes.size() - 1 : discoveryIndex - 1;
                    this.recipeNameBox.setValue(discoveredRecipes.get(discoveryIndex).id().toString());
                    return true;
                }
                if (mouseX >= centerX + 55 && mouseX <= centerX + 75) {
                    discoveryIndex = (discoveryIndex + 1) % discoveredRecipes.size();
                    this.recipeNameBox.setValue(discoveredRecipes.get(discoveryIndex).id().toString());
                    return true;
                }
            }
        }

        if (currentCategory == Category.ADD_RECIPE && currentRecipeType == RecipeType.CRAFTING) {
            int gridX = centerX - 50;
            int gridY = centerY - 50;
            for (int i = 0; i < 9; i++) {
                int x = gridX + (i % 3) * 20;
                int y = gridY + (i / 3) * 20;
                if (mouseX >= x && mouseX <= (x + 18) && mouseY >= y && mouseY <= (y + 18)) {
                    handleSlotClick(i, button);
                    return true;
                }
            }
        } else if (currentCategory == Category.ADD_RECIPE && currentRecipeType == RecipeType.SMITHING) {
            for (int i = 0; i < 3; i++) {
                int x = centerX - 60 + (i * 20);
                int y = centerY - 10;
                if (mouseX >= x && mouseX <= (x + 18) && mouseY >= y && mouseY <= (y + 18)) {
                    handleSlotClick(i, button);
                    return true;
                }
            }
        } else if (currentCategory == Category.ADD_RECIPE) {
            int x = centerX - 30;
            int y = centerY - 10;
            if (mouseX >= x && mouseX <= (x + 18) && mouseY >= y && mouseY <= (y + 18)) {
                handleSlotClick(0, button);
                return true;
            }
        }

        if (currentCategory == Category.ADD_RECIPE) {
            int outputStartX = (currentRecipeType == RecipeType.CRAFTING) ? centerX + 35 : (currentRecipeType == RecipeType.SMITHING ? centerX + 30 : centerX + 20);
            int outputY = centerY - 10;
            
            if (mouseX >= outputStartX && mouseX <= (outputStartX + 18) && mouseY >= outputY && mouseY <= (outputY + 18)) {
                if (button == 1) {
                    outputs.get(0).stack = ItemStack.EMPTY;
                    this.recipeNameBox.setValue("");
                } else {
                    ItemStack toPlace = !draggedItem.isEmpty() ? draggedItem.stack : Minecraft.getInstance().player.containerMenu.getCarried();
                    if (!toPlace.isEmpty()) {
                        outputs.get(0).stack = toPlace.copy();
                        Identifier id = BuiltInRegistries.ITEM.getKey(outputs.get(0).stack.getItem());
                        if (id != null) this.recipeNameBox.setValue(id.toString());
                        this.draggedItem.clear();
                    }
                }
                return true;
            }
        }

        handleExternalDrag();

        if (isSidebarVisible) {
            boolean handled = this.searchBox.mouseClicked(event, doubleClicked);
            if (handled) { this.setFocused(this.searchBox); return true; }
        }

        this.draggedItem.clear();

        return super.mouseClicked(event, doubleClicked);
    }

    private void handleSlotClick(int slotIndex, int button) {
        if (button == 1) {
            inputs[slotIndex].clear();
        } else {
            ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();
            if (!draggedItem.isEmpty()) {
                inputs[slotIndex] = draggedItem.copy();
            } else if (!carried.isEmpty()) {
                inputs[slotIndex].set(carried.copy());
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();

        if (this.searchBox.isFocused() || this.recipeNameBox.isFocused() || this.processingTimeBox.isFocused() ||
            this.maxStackSizeBox.isFocused() || this.itemDisplayNameBox.isFocused() || this.nutritionBox.isFocused() ||
                this.saturationBox.isFocused() || this.attackDamageBox.isFocused() || this.attackSpeedBox.isFocused() ||
                this.durabilityBox.isFocused() || this.enchantabilityBox.isFocused() || this.burnTimeBox.isFocused() ||
                this.eatTimeBox.isFocused() || this.miningLevelBox.isFocused() || this.miningSpeedBox.isFocused() ||
                this.loreBox.isFocused()) {
            return super.keyPressed(event);
        }

        if (keyCode == GLFW.GLFW_KEY_E || SimplyRecipes.EDITOR_KEY.matches(event)) {
            this.onClose();
            return true;
        }

        return super.keyPressed(event);
    }

    private void handleExternalDrag() { }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        boolean isItemMode = currentCategory == Category.CREATE_ITEM;

        if (isTypeDropdownOpen && mouseX >= this.recipeTypeSelector.getX() && mouseX <= this.recipeTypeSelector.getX() + this.recipeTypeSelector.getWidth()) {
            int maxScroll = Math.max(0, RecipeType.values().length - MAX_DROPDOWN_VISIBLE);
            if (scrollY > 0) dropdownScroll = Math.max(0, dropdownScroll - 1);
            else if (scrollY < 0) dropdownScroll = Math.min(maxScroll, dropdownScroll + 1);
            return true;
        }

        if (isDropdownOpen && mouseX >= this.categorySelector.getX() && mouseX <= this.categorySelector.getX() + this.categorySelector.getWidth()) {
            int maxScroll = Math.max(0, Category.values().length - MAX_DROPDOWN_VISIBLE);
            if (scrollY > 0) dropdownScroll = Math.max(0, dropdownScroll - 1);
            else if (scrollY < 0) dropdownScroll = Math.min(maxScroll, dropdownScroll + 1);
            return true;
        }

        if (currentCategory == Category.CREATE_ITEM) {
            if (isAnyDropdownOpen()) {
                return false;
            }

            int maxScroll = 450;
            if (scrollY > 0) itemPropertiesScroll = Math.max(0, itemPropertiesScroll - 10);
            else if (scrollY < 0) itemPropertiesScroll = Math.min(maxScroll, itemPropertiesScroll + 10);
            return true;
        }

        if (mouseX < 120 && !isItemMode) {
            int columns = 5;
            int rows = (this.height - 70) / 20;
            int maxScroll = Math.max(0, filteredItems.size() - (columns * rows));

            if (scrollY > 0) scrollOffset = Math.max(0, scrollOffset - 5);
            else if (scrollY < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 5);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static class IngredientHolder {
        ItemStack stack = ItemStack.EMPTY;
        TagKey<Item> tag = null;
        boolean isTag = false;

        void set(ItemStack stack) { this.stack = stack; this.isTag = false; this.tag = null; }
        void set(TagKey<Item> tag) { this.tag = tag; this.isTag = true; this.stack = ItemStack.EMPTY; }
        void clear() { this.stack = ItemStack.EMPTY; this.tag = null; this.isTag = false; }
        boolean isEmpty() { return isTag ? tag == null : stack.isEmpty(); }
        IngredientHolder copy() {
            IngredientHolder copy = new IngredientHolder();
            copy.stack = this.stack.copy();
            copy.tag = this.tag;
            copy.isTag = this.isTag;
            return copy;
        }
        String getJsonName() {
            if (isTag) return "#" + tag.location().toString();
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }
    }

    private record SearchEntry(ItemStack stack, TagKey<Item> tag, boolean isTag) {
        public SearchEntry(ItemStack stack) { this(stack, null, false); }
        public SearchEntry(TagKey<Item> tag) { this(ItemStack.EMPTY, tag, true); }
        public IngredientHolder toHolder() {
            IngredientHolder h = new IngredientHolder();
            if (isTag) h.set(tag); else h.set(stack.copy());
            return h;
        }
    }

    private static class OutputData {
        ItemStack stack;
        float chance;

        OutputData(ItemStack stack, float chance) {
            this.stack = stack;
            this.chance = chance;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}