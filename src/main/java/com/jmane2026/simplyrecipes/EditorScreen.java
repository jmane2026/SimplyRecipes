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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.lwjgl.glfw.GLFW;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class EditorScreen extends Screen {
    private final Identifier targetItem;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Category {
        ADD_RECIPE("Add Recipe"),
        REMOVE_RECIPE("Remove"),
        CREATE_ITEM("Create Item"),
        CREATE_BLOCK("Create Block");

        private final String displayName;
        Category(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
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

    private Checkbox overrideCheckbox;
    private boolean isDropdownOpen = false;
    private boolean isTypeDropdownOpen = false;
    private int dropdownScroll = 0;
    private static final int MAX_DROPDOWN_VISIBLE = 5;

    private Checkbox shapelessCheckbox;
    private Button categorySelector;
    private Button recipeTypeSelector;
    private Button saveButton;

    private EditBox recipeNameBox;
    private EditBox processingTimeBox;
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

        this.processingTimeBox = new EditBox(this.font, 0, 0, 40, 20, Component.translatable("gui.simplyrecipes.ticks"));
        this.processingTimeBox.setValue("200");
        this.addRenderableWidget(this.processingTimeBox);

        this.toggleButton = Button.builder(Component.translatable("gui.simplyrecipes.close"), (btn) -> {
            this.toggleSidebar();
        }).pos(10, this.height - 30).size(100, 20).build();

        this.categorySelector = Button.builder(Component.literal(currentCategory.getDisplayName()), (btn) -> {
            this.isDropdownOpen = !this.isDropdownOpen;
        }).size(75, 20).build();

        this.recipeTypeSelector = Button.builder(Component.literal(currentRecipeType.getDisplayName()), (btn) -> {
            this.isTypeDropdownOpen = !this.isTypeDropdownOpen;
        }).size(75, 20).build();

        this.overrideCheckbox = Checkbox.builder(Component.empty(), this.font)
                .pos(0, 0)
                .selected(isOverride)
                .onValueChange((cb, val) -> this.isOverride = val)
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
        this.addRenderableWidget(this.recipeTypeSelector);
        this.addRenderableWidget(this.overrideCheckbox);
        this.addRenderableWidget(this.shapelessCheckbox);
        this.addRenderableWidget(this.saveButton);

        updateSearch("");
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

    public void receiveDiscoveredRecipes(List<ProvideRecipesPayload.RecipeInfo> recipes) {
        this.discoveredRecipes = recipes;
        this.discoveryIndex = 0;
        if (!recipes.isEmpty()) {
            this.recipeNameBox.setValue(recipes.get(0).id().toString());
        }
    }

    private void saveRecipe() {
        if (currentCategory == Category.REMOVE_RECIPE) {
            if (discoveredRecipes.isEmpty()) return;
            Identifier recipeId = discoveredRecipes.get(discoveryIndex).id();
            JsonObject deletionJson = RecipeGenerator.createDeletionTemplate();

            if (Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().getConnection().send(new SaveRecipePayload(recipeId, GSON.toJson(deletionJson)));
                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§eDisabling recipe: §f" + recipeId.toString()));

                // Remove the recipe from the local list to reflect it's gone
                discoveredRecipes.remove(discoveryIndex);
                if (discoveredRecipes.isEmpty()) {
                    discoveryIndex = 0;
                    this.recipeNameBox.setValue("");
                } else {
                    // Adjust index if we were at the end of the list
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

        // Parse Cook Time safely
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
            // Use getTags() to iterate through all available tags in the registry
            BuiltInRegistries.ITEM.getTags().forEach(tagNamed -> {
                // In 1.21.3, getTags() returns HolderSet.Named.
                // We extract the TagKey using .key()
                TagKey<Item> tagKey = tagNamed.key();

                if (tagKey.location().toString().contains(tagPart)) {
                    // Add the cycling tag entry first.
                    this.filteredItems.add(new SearchEntry(tagKey));

                    // Add each member separately
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
        // Iterate through all items and check if their holder contains the specified tag
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

        int centerX = isSidebarVisible ? (this.width + 120) / 2 : this.width / 2;
        int centerY = this.height / 2;

        renderWorkstationBackground(graphics, centerX, centerY);

        this.categorySelector.setX(centerX - 77);
        this.categorySelector.setY(centerY - 75);

        this.recipeTypeSelector.setX(centerX + 2);
        this.recipeTypeSelector.setY(centerY - 75);
        this.recipeTypeSelector.visible = currentCategory == Category.ADD_RECIPE;

        boolean isRemoveMode = currentCategory == Category.REMOVE_RECIPE;

        this.overrideCheckbox.setX(centerX - 75);
        this.overrideCheckbox.setY(centerY + 15);
        this.overrideCheckbox.visible = !isRemoveMode;

        this.shapelessCheckbox.setX(centerX - 75);
        this.shapelessCheckbox.setY(centerY + 35);
        this.shapelessCheckbox.visible = currentCategory == Category.ADD_RECIPE && currentRecipeType == RecipeType.CRAFTING;

        this.processingTimeBox.setX(centerX - 75);
        this.processingTimeBox.setY(centerY + 35);
        this.processingTimeBox.visible = isCookingMode();

        this.recipeNameBox.setX(centerX - 75);
        this.recipeNameBox.setY(centerY + 55);
        this.recipeNameBox.visible = !isOverride || isRemoveMode;

        this.saveButton.setX(centerX + 25);
        this.saveButton.setY(centerY + 55);

        // Update Save Button Text based on mode
        this.saveButton.setMessage(currentCategory == Category.REMOVE_RECIPE
                ? Component.literal("Remove")
            : Component.translatable("gui.simplyrecipes.save"));

        if (isRemoveMode) {
            if (discoveredRecipes.isEmpty()) {
                String line1 = "Select an item";
                String line2 = "to lookup recipes";
                graphics.text(this.font, Component.literal(line1), centerX - (this.font.width(line1) / 2), centerY - 15, 0xFF3F3F3F, false);
                graphics.text(this.font, Component.literal(line2), centerX - (this.font.width(line2) / 2), centerY - 5, 0xFF3F3F3F, false);
            } else {
                ProvideRecipesPayload.RecipeInfo current = discoveredRecipes.get(discoveryIndex);
                
                String countText = "Recipe " + (discoveryIndex + 1) + " of " + discoveredRecipes.size();
                // Strip "minecraft:" from type for cleaner display
                String typeText = current.type().replace("minecraft:", "");
                
                graphics.text(this.font, Component.literal(countText), centerX - (this.font.width(countText) / 2), centerY - 35, 0xFF3F3F3F, false);
                graphics.text(this.font, Component.literal(typeText), centerX - (this.font.width(typeText) / 2), centerY - 25, 0xFF3F3F3F, false);

                // Draw cycle buttons
                graphics.fill(centerX - 75, centerY - 10, centerX - 55, centerY + 5, 0xFF707070);
                graphics.text(this.font, Component.literal("<"), centerX - 70, centerY - 6, 0xFFFFFFFF);

                graphics.fill(centerX + 55, centerY - 10, centerX + 75, centerY + 5, 0xFF707070);
                graphics.text(this.font, Component.literal(">"), centerX + 62, centerY - 6, 0xFFFFFFFF);

                // Draw Station Icon for the recipe type if possible, otherwise barrier
                graphics.fakeItem(getIconForRecipeType(current.type()), centerX - 8, centerY - 15);
            }
        } else if (currentRecipeType == RecipeType.CRAFTING) {
            renderVanillaLayout(graphics, centerX, centerY, mouseX, mouseY);
        } else {
            renderMachineLayout(graphics, centerX, centerY, mouseX, mouseY);
        }

        if (isSidebarVisible) {
            renderSidebar(graphics, mouseX, mouseY);
            this.searchBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        renderConfigurationRows(graphics, centerX, centerY, mouseX, mouseY, partialTick);

        if (this.recipeNameBox.visible) this.recipeNameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.saveButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (isTypeDropdownOpen) {
            renderTypeDropdownList(graphics, mouseX, mouseY);
        }

        if (isDropdownOpen) {
            renderDropdownList(graphics, mouseX, mouseY);
        }

        renderIngredient(graphics, draggedItem, mouseX - 8, mouseY - 8);

        // Render Tooltip at the very end so it's on top of everything
        if (draggedItem.isEmpty() && hoveredIngredient != null && !hoveredIngredient.isEmpty()) {
            if (hoveredIngredient.isTag) {
                List<Component> components = List.of(Component.literal("#" + hoveredIngredient.tag.location().toString()));
                List<ClientTooltipComponent> tooltipLines = components.stream()
                        .map(c -> ClientTooltipComponent.create(c.getVisualOrderText()))
                        .collect(Collectors.toList());
                graphics.tooltip(this.font, tooltipLines, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
            } else {
                List<ClientTooltipComponent> tooltipLines = this.getTooltipFromItem(Minecraft.getInstance(), hoveredIngredient.stack).stream()
                        .map(c -> ClientTooltipComponent.create(c.getVisualOrderText()))
                        .collect(Collectors.toList());
                graphics.tooltip(this.font, tooltipLines, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
            }
        }
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

    private void renderConfigurationRows(GuiGraphicsExtractor graphics, int centerX, int centerY, int mouseX, int mouseY, float partialTick) {
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
        this.toggleButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.categorySelector.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.recipeTypeSelector.visible) this.recipeTypeSelector.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderWorkstationBackground(GuiGraphicsExtractor graphics, int centerX, int centerY) {
        int bgWidth = 160;
        int bgHeight = 160;
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
            renderIngredient(graphics, inputs[0], centerX - 59, centerY - 29);

            drawSlot(graphics, centerX - 40, centerY - 10, mouseX, mouseY, inputs[1]);
            renderIngredient(graphics, inputs[1], centerX - 39, centerY - 29);

            drawSlot(graphics, centerX - 20, centerY - 10, mouseX, mouseY, inputs[2]);
            renderIngredient(graphics, inputs[2], centerX - 19, centerY - 29);

            graphics.fakeItem(currentRecipeType.getStationStack(), centerX + 7, centerY - 29);
            drawArrow(graphics, centerX + 6, centerY - 1);
            outputX = centerX + 30;
        } else {
            drawSlot(graphics, centerX - 30, centerY - 10, mouseX, mouseY, inputs[0]);
            renderIngredient(graphics, inputs[0], centerX - 29, centerY - 29);

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

        if (mouseX >= x && mouseX <= x + 18 && mouseY >= y && mouseY <= y + 18) {
            graphics.fill(x, y, x + 18, y + 18, 0x80FFFFFF);
            this.hoveredIngredient = content;
        }
    }

    private void drawArrow(GuiGraphicsExtractor graphics, int x, int y) {
        // The line is now 18px long and perfectly centered vertically on 'y'
        graphics.fill(x, y - 1, x + 18, y + 1, 0xFFFFFFFF);

        // Larger, more proportionate Arrow head (11px tall, 5px wide)
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

        int centerX = isSidebarVisible ? (this.width + 120) / 2 : this.width / 2;
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

        if (this.toggleButton.mouseClicked(event, doubleClicked)) return true;
        if (this.categorySelector.mouseClicked(event, doubleClicked)) return true;
        if (this.recipeTypeSelector.visible && this.recipeTypeSelector.mouseClicked(event, doubleClicked)) return true;
        if (this.overrideCheckbox.visible && this.overrideCheckbox.mouseClicked(event, doubleClicked)) return true;
        if (this.shapelessCheckbox.visible && this.shapelessCheckbox.mouseClicked(event, doubleClicked)) return true;
        if (this.saveButton.mouseClicked(event, doubleClicked)) return true;

        if (isSidebarVisible) {
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
                            // Request recipes for this item from the server
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
                int y = centerY - 30;
                if (mouseX >= x && mouseX <= (x + 18) && mouseY >= y && mouseY <= (y + 18)) {
                    handleSlotClick(i, button);
                    return true;
                }
            }
        } else if (currentCategory == Category.ADD_RECIPE) {
            int x = centerX - 30;
            int y = centerY - 30;
            if (mouseX >= x && mouseX <= (x + 18) && mouseY >= y && mouseY <= (y + 18)) {
                handleSlotClick(0, button);
                return true;
            }
        }

        if (currentCategory == Category.ADD_RECIPE) {
            int outputStartX = (currentRecipeType == RecipeType.CRAFTING) ? centerX + 35 : (currentRecipeType == RecipeType.SMITHING ? centerX + 30 : centerX + 20);
            int outputY = centerY - 30;
            if (mouseX >= outputStartX && mouseX <= (outputStartX + 18) && mouseY >= outputY && mouseY <= (outputY + 18)) {
                if (button == 1) {
                    outputs.get(0).stack = ItemStack.EMPTY; // Result still requires specific item
                    this.recipeNameBox.setValue("");
                } else if (!draggedItem.isEmpty() && !draggedItem.isTag) {
                    outputs.get(0).stack = draggedItem.stack.copy();
                    Identifier id = BuiltInRegistries.ITEM.getKey(outputs.get(0).stack.getItem());
                    if (id != null) this.recipeNameBox.setValue(id.toString());
                }
                return true;
            }
        }

        handleExternalDrag();

        if (isSidebarVisible) {
            boolean handled = this.searchBox.mouseClicked(event, doubleClicked);
            if (handled) { this.setFocused(this.searchBox); return true; }
        }

        ItemStack cursorStack = Minecraft.getInstance().player.containerMenu.getCarried();
        if (!cursorStack.isEmpty()) {
            this.draggedItem.set(cursorStack.copy());
             return true; // Return here so the code below doesn't immediately clear it
        }

        // Only clear the item if we clicked the empty background and weren't picking something up
        this.draggedItem.clear();

        return super.mouseClicked(event, doubleClicked);
    }

    private void handleSlotClick(int slotIndex, int button) {
        if (button == 1) {
            inputs[slotIndex].clear();
        } else if (!draggedItem.isEmpty()) {
            inputs[slotIndex] = draggedItem.copy();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();

        // If any of the text boxes are focused, we let them handle the key press
        // so that typing 'e' or 'k' doesn't accidentally close the editor.
        if (this.searchBox.isFocused() || this.recipeNameBox.isFocused() || this.processingTimeBox.isFocused()) {
            return super.keyPressed(event);
        }

        // Check if 'E' was pressed or if the configurable 'K' keybind was pressed
        if (keyCode == GLFW.GLFW_KEY_E || SimplyRecipes.EDITOR_KEY.matches(event)) {
            this.onClose();
            return true;
        }

        return super.keyPressed(event);
    }

    private void handleExternalDrag() { }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
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

        if (mouseX < 120) {
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