package com.jmane2026.simplyrecipes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class EditorScreen extends Screen {
    private final Identifier targetItem;

    public enum Mode {
        CRAFTING("Crafting", "crafting_table"),
        SMELTING("Smelting", "furnace"),
        BLASTING("Blasting", "blast_furnace"),
        SMOKING("Smoking", "smoker"),
        CAMPFIRE_COOKING("Campfire Cooking", "campfire"),
        STONECUTTING("Stonecutting", "stonecutter"),
        SMITHING("Smithing", "smithing_table");

        private final String displayName;
        private final Identifier stationId;

        Mode(String displayName, String stationPath) {
            this.displayName = displayName;
            this.stationId = Identifier.fromNamespaceAndPath("minecraft", stationPath);
        }

        public String getDisplayName() { return displayName; }
        public int getDefaultTicks() { return this == CAMPFIRE_COOKING || this == BLASTING || this == SMOKING ? 100 : 200; }
        public ItemStack getStationStack() {
            return BuiltInRegistries.ITEM.getOptional(stationId).map(ItemStack::new).orElse(ItemStack.EMPTY);
        }
    }

    private Mode currentMode = Mode.CRAFTING;

    private final ItemStack[] inputs = new ItemStack[9];
    private final List<OutputData> outputs = new ArrayList<>();

    private boolean isOverride = true;
    private boolean isShapeless = false;

    private Checkbox overrideCheckbox;
    private boolean isDropdownOpen = false;
    private int dropdownScroll = 0;
    private static final int MAX_DROPDOWN_VISIBLE = 5;

    private Checkbox shapelessCheckbox;
    private Button modeSelector;
    private Button saveButton;

    private EditBox recipeNameBox;
    private EditBox processingTimeBox;
    private EditBox searchBox;
    private List<SearchEntry> filteredItems = new ArrayList<>();
    private Button toggleButton;
    private boolean isSidebarVisible = true;
    private int scrollOffset = 0;

    private ItemStack draggedItem = ItemStack.EMPTY;

    protected EditorScreen(Identifier targetItem) {
        super(Component.literal("Recipe Editor: " + targetItem.toString()));
        this.targetItem = targetItem;

        for (int i = 0; i < 9; i++) inputs[i] = ItemStack.EMPTY;

        ItemStack result = BuiltInRegistries.ITEM.getOptional(targetItem)
                .map(ItemStack::new).orElse(ItemStack.EMPTY);
        outputs.add(new OutputData(result, 1.0f));
    }

    @Override
    protected void init() {
        super.init();

        this.searchBox = new EditBox(this.font, 10, 10, 100, 20, Component.literal("Search..."));
        this.searchBox.setResponder(this::updateSearch);
        this.addRenderableWidget(this.searchBox);

        this.recipeNameBox = new EditBox(this.font, 0, 0, 100, 20, Component.literal("Recipe Name"));
        this.recipeNameBox.setValue(targetItem.toString());
        this.recipeNameBox.setMaxLength(128);
        this.addRenderableWidget(this.recipeNameBox);

        this.processingTimeBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("Ticks"));
        this.processingTimeBox.setValue("200");
        this.addRenderableWidget(this.processingTimeBox);

        this.toggleButton = Button.builder(Component.literal("Close"), (btn) -> {
            this.toggleSidebar();
        }).pos(10, this.height - 30).size(100, 20).build();

        this.modeSelector = Button.builder(Component.literal(currentMode.getDisplayName()), (btn) -> {
            this.isDropdownOpen = !this.isDropdownOpen;
        }).size(120, 20).build();

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

        this.saveButton = Button.builder(Component.literal("Save"), (btn) -> {
            this.saveRecipe();
        }).size(50, 20).build();

        this.addRenderableWidget(this.toggleButton);
        this.addRenderableWidget(this.modeSelector);
        this.addRenderableWidget(this.overrideCheckbox);
        this.addRenderableWidget(this.shapelessCheckbox);
        this.addRenderableWidget(this.saveButton);

        updateSearch("");
    }

    private void setMode(Mode mode) {
        this.currentMode = mode;
        this.modeSelector.setMessage(Component.literal(mode.getDisplayName()));
        if (this.processingTimeBox != null && isCookingMode()) {
            this.processingTimeBox.setValue(String.valueOf(mode.getDefaultTicks()));
        }
        this.isDropdownOpen = false;
    }

    private void saveRecipe() {
        ItemStack resultStack = outputs.get(0).stack;
        if (resultStack.isEmpty()) return;

        Identifier resultId = BuiltInRegistries.ITEM.getKey(resultStack.getItem());
        String rawInput = recipeNameBox.getValue().toLowerCase();
        
        Identifier recipeId;
        if (isOverride) {
            recipeId = resultId;
        } else {
            if (rawInput.contains(":")) {
                String[] parts = rawInput.split(":", 2);
                recipeId = Identifier.fromNamespaceAndPath(parts[0], parts[1].replaceAll("[^a-z0-9/._-]", ""));
            } else {
                String fileName = rawInput.replaceAll("[^a-z0-9/._-]", "");
                if (fileName.isEmpty()) fileName = resultId.getPath() + "_custom";
                recipeId = Identifier.fromNamespaceAndPath(resultId.getNamespace(), fileName);
            }
        }
        
        int cookTime = 200;
        try {
            cookTime = Integer.parseInt(processingTimeBox.getValue());
        } catch (NumberFormatException ignored) {}

        if (currentMode == Mode.CRAFTING) {
            if (isShapeless) {
                List<Identifier> ingredients = new ArrayList<>();
                for (ItemStack stack : inputs) {
                    if (!stack.isEmpty()) {
                        ingredients.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                    }
                }
                JsonObject json = RecipeGenerator.createShapelessRecipeTemplate(resultId, resultStack.getCount(), ingredients);
                RecipeGenerator.saveCustomRecipe(recipeId, json);
            } else {
                JsonObject json = RecipeGenerator.createShapedRecipeTemplate(resultId, resultStack.getCount(), inputs);
                RecipeGenerator.saveCustomRecipe(recipeId, json);
            }
        } 
        else if (isCookingMode()) {
            if (inputs[0].isEmpty()) return;
            Identifier ingredientId = BuiltInRegistries.ITEM.getKey(inputs[0].getItem());
            String type = getCookingType();
            JsonObject json = RecipeGenerator.createCookingRecipeTemplate(type, resultId, ingredientId, cookTime, 0.1f);
            RecipeGenerator.saveCustomRecipe(recipeId, json);
        } 
        else if (currentMode == Mode.STONECUTTING) {
            if (inputs[0].isEmpty()) return;
            Identifier ingredientId = BuiltInRegistries.ITEM.getKey(inputs[0].getItem());
            JsonObject json = RecipeGenerator.createStonecuttingRecipeTemplate(resultId, resultStack.getCount(), ingredientId);
            RecipeGenerator.saveCustomRecipe(recipeId, json);
        } 
        else if (currentMode == Mode.SMITHING) {
            if (inputs[0].isEmpty() || inputs[1].isEmpty() || inputs[2].isEmpty()) return;
            Identifier templateId = BuiltInRegistries.ITEM.getKey(inputs[0].getItem());
            Identifier baseId = BuiltInRegistries.ITEM.getKey(inputs[1].getItem());
            Identifier additionId = BuiltInRegistries.ITEM.getKey(inputs[2].getItem());
            JsonObject json = RecipeGenerator.createSmithingRecipeTemplate(resultId, templateId, baseId, additionId);
            RecipeGenerator.saveCustomRecipe(recipeId, json);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.isSingleplayer()) {
            mc.player.sendSystemMessage(
                Component.literal("§aSuccessfully saved: §f" + recipeId.toString())
            );

            if (mc.player.connection != null) {
                mc.player.connection.sendCommand("reload");
            }
        }
    }

    private String getCookingType() {
        return switch(currentMode) {
                case SMELTING -> "minecraft:smelting";
                case BLASTING -> "minecraft:blasting";
                case SMOKING -> "minecraft:smoking";
                case CAMPFIRE_COOKING -> "minecraft:campfire_cooking";
                default -> "minecraft:smelting";
            };
    }

    private boolean isCookingMode() {
        return currentMode == Mode.SMELTING || currentMode == Mode.BLASTING || 
               currentMode == Mode.SMOKING || currentMode == Mode.CAMPFIRE_COOKING;
    }

    private void toggleSidebar() {
        this.isSidebarVisible = !this.isSidebarVisible;
        this.searchBox.visible = isSidebarVisible;
        this.toggleButton.setMessage(Component.literal(isSidebarVisible ? "Close" : "Search"));
    }

    private void updateSearch(String query) {
        String lowerQuery = query.toLowerCase();

        List<SearchEntry> items = BuiltInRegistries.ITEM.stream()
                .filter(item -> {
                    Identifier id = BuiltInRegistries.ITEM.getKey(item);
                    if (lowerQuery.startsWith("@")) {
                        return id.getNamespace().contains(lowerQuery.substring(1));
                    } else if (lowerQuery.startsWith("#")) {
                        String tagPart = lowerQuery.substring(1);
                        return BuiltInRegistries.ITEM.wrapAsHolder(item).tags().anyMatch(tag -> tag.location().toString().contains(tagPart));
                    } else {
                        if (id.toString().contains(lowerQuery)) return true;
                        String translatedName = Component.translatable(item.getDescriptionId()).getString().toLowerCase();
                        return translatedName.contains(lowerQuery);
                    }
                })
                .map(item -> new SearchEntry(new ItemStack(item)))
                .collect(Collectors.toList());

        this.filteredItems = new ArrayList<>();
        this.filteredItems.addAll(items);

        this.scrollOffset = 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 10, 0xFFFFFF);

        int centerX = isSidebarVisible ? (this.width + 120) / 2 : this.width / 2;
        int centerY = this.height / 2;

        renderWorkstationBackground(graphics, centerX, centerY);

        this.modeSelector.setX(centerX - 60);
        this.modeSelector.setY(centerY - 75);

        this.overrideCheckbox.setX(centerX - 75);
        this.overrideCheckbox.setY(centerY + 15);
        this.overrideCheckbox.visible = true;

        this.shapelessCheckbox.setX(centerX - 75);
        this.shapelessCheckbox.setY(centerY + 35);
        this.shapelessCheckbox.visible = (currentMode == Mode.CRAFTING);

        this.processingTimeBox.setX(centerX - 75);
        this.processingTimeBox.setY(centerY + 35);
        this.processingTimeBox.visible = isCookingMode();

        this.recipeNameBox.setX(centerX - 75);
        this.recipeNameBox.setY(centerY + 55);
        this.recipeNameBox.visible = !isOverride;

        this.saveButton.setX(centerX + 25);
        this.saveButton.setY(centerY + 55);

        if (currentMode == Mode.CRAFTING) {
            renderVanillaLayout(graphics, centerX, centerY, mouseX, mouseY);
        } else {
            renderMachineLayout(graphics, centerX, centerY, mouseX, mouseY);
        }

        if (isSidebarVisible) {
            renderSidebar(graphics, mouseX, mouseY);
            this.searchBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        if (this.overrideCheckbox.visible) {
            this.overrideCheckbox.extractRenderState(graphics, mouseX, mouseY, partialTick);
            graphics.text(this.font, Component.literal("Override Existing"), centerX - 50, centerY + 20, 0xFF3F3F3F,false);
        }
        if (this.shapelessCheckbox.visible) {
            this.shapelessCheckbox.extractRenderState(graphics, mouseX, mouseY, partialTick);
            graphics.text(this.font, Component.literal("Shapeless"), centerX - 50, centerY + 40, 0xFF3F3F3F,false);
        }
        if (this.processingTimeBox.visible) {
            this.processingTimeBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
            graphics.text(this.font, Component.literal("Ticks"), centerX - 30, centerY + 40, 0xFF3F3F3F,false);
        }

        this.toggleButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.modeSelector.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.recipeNameBox.visible) this.recipeNameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.saveButton.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (isDropdownOpen) {
            renderDropdownList(graphics, mouseX, mouseY);
        }

        graphics.fakeItem(draggedItem, mouseX - 8, mouseY - 8);
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
        int x = this.modeSelector.getX();
        int y = this.modeSelector.getY() + 20;
        int width = this.modeSelector.getWidth();

        Mode[] modes = Mode.values();
        int visibleCount = Math.min(modes.length, MAX_DROPDOWN_VISIBLE);
        int height = visibleCount * 20;

        graphics.fill(x, y, x + width, y + height, 0xFF202020);
        graphics.fill(x - 1, y, x, y + height, 0xFFFFFFFF);
        graphics.fill(x + width, y, x + width + 1, y + height, 0xFFFFFFFF);
        graphics.fill(x, y + height, x + width, y + height + 1, 0xFFFFFFFF);

        for (int i = 0; i < visibleCount; i++) {
            int index = i + dropdownScroll;
            if (index >= modes.length) break;

            int itemY = y + (i * 20);
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= itemY && mouseY <= itemY + 20;
            graphics.text(this.font, Component.literal(modes[index].getDisplayName()), x + 5, itemY + 6, hovered ? 0xFFFFFFA0 : 0xFFFFFFFF);
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
            drawSlot(graphics, x, y, mouseX, mouseY);

            graphics.fakeItem(entry.stack, x + 1, y + 1);
        }
    }

    private void renderVanillaLayout(GuiGraphicsExtractor graphics, int centerX, int centerY, int mouseX, int mouseY) {
        int startX = centerX - 50;
        int startY = centerY - 50;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int slotX = startX + (col * 20);
                int slotY = startY + (row * 20);
                drawSlot(graphics, slotX, slotY, mouseX, mouseY);
                graphics.fakeItem(inputs[row * 3 + col], slotX + 1, slotY + 1);
            }
        }

        graphics.fakeItem(currentMode.getStationStack(), centerX + 15, centerY - 29);

        drawSlot(graphics, centerX + 35, centerY - 30, mouseX, mouseY);
        graphics.fakeItem(outputs.get(0).stack, centerX + 36, centerY - 29);
    }

    private void renderMachineLayout(GuiGraphicsExtractor graphics, int centerX, int centerY, int mouseX, int mouseY) {
        int outputX = centerX + 20;

        if (currentMode == Mode.SMITHING) {
            drawSlot(graphics, centerX - 60, centerY - 30, mouseX, mouseY);
            graphics.fakeItem(inputs[0], centerX - 59, centerY - 29);

            drawSlot(graphics, centerX - 40, centerY - 30, mouseX, mouseY);
            graphics.fakeItem(inputs[1], centerX - 39, centerY - 29);

            drawSlot(graphics, centerX - 20, centerY - 30, mouseX, mouseY);
            graphics.fakeItem(inputs[2], centerX - 19, centerY - 29);

            graphics.fakeItem(currentMode.getStationStack(), centerX + 7, centerY - 29);
            outputX = centerX + 30;
        } else {
            drawSlot(graphics, centerX - 30, centerY - 30, mouseX, mouseY);
            graphics.fakeItem(inputs[0], centerX - 29, centerY - 29);

            graphics.fakeItem(currentMode.getStationStack(), centerX - 5, centerY - 29);
        }

        drawSlot(graphics, outputX, centerY - 30, mouseX, mouseY);
        graphics.fakeItem(outputs.get(0).stack, outputX + 1, centerY - 29);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClicked) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        if (isDropdownOpen) {
            int dx = this.modeSelector.getX();
            int dy = this.modeSelector.getY() + 20;
            int dw = this.modeSelector.getWidth();
            Mode[] modes = Mode.values();
            int visibleCount = Math.min(modes.length, MAX_DROPDOWN_VISIBLE);

            int dh = visibleCount * 20;
            if (mouseX >= dx && mouseX <= dx + dw && mouseY >= dy && mouseY <= dy + dh) {
                int clickedIndex = (int)((mouseY - dy) / 20) + dropdownScroll;
                if (clickedIndex < modes.length) {
                    setMode(modes[clickedIndex]);
                    return true;
                }
            } else {
                if (!(mouseX >= dx && mouseX <= dx + dw && mouseY >= dy - 20 && mouseY <= dy)) {
                    this.isDropdownOpen = false;
                }
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
        if (this.modeSelector.mouseClicked(event, doubleClicked)) return true;
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
                    this.draggedItem = entry.stack.copy();
                    return true;
                }
            }
            }
        }

        int centerX = isSidebarVisible ? (this.width + 120) / 2 : this.width / 2;
        int centerY = this.height / 2;

        if (currentMode == Mode.CRAFTING) {
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
        } else if (currentMode == Mode.SMITHING) {
            for (int i = 0; i < 3; i++) {
                int x = centerX - 60 + (i * 20);
                int y = centerY - 30;
                if (mouseX >= x && mouseX <= (x + 18) && mouseY >= y && mouseY <= (y + 18)) {
                    handleSlotClick(i, button);
                    return true;
                }
            }
        } else {
            int x = centerX - 30;
            int y = centerY - 30;
            if (mouseX >= x && mouseX <= (x + 18) && mouseY >= y && mouseY <= (y + 18)) {
                handleSlotClick(0, button);
                return true;
            }
        }

        int outputStartX = (currentMode == Mode.CRAFTING) ? centerX + 35 : (currentMode == Mode.SMITHING ? centerX + 30 : centerX + 20);
        int outputY = centerY - 30;
        if (mouseX >= outputStartX && mouseX <= (outputStartX + 18) && mouseY >= outputY && mouseY <= (outputY + 18)) {
            if (button == 1) {
                outputs.get(0).stack = ItemStack.EMPTY;
                this.recipeNameBox.setValue("");
            } else if (!draggedItem.isEmpty()) {
                outputs.get(0).stack = draggedItem.copy();
                Identifier id = BuiltInRegistries.ITEM.getKey(outputs.get(0).stack.getItem());
                if (id != null) this.recipeNameBox.setValue(id.toString());
            }
            return true;
        }

        handleExternalDrag();

        if (isSidebarVisible) {
            boolean handled = this.searchBox.mouseClicked(event, doubleClicked);
            if (handled) {
                this.setFocused(this.searchBox);
                return true;
            }
        }

        ItemStack cursorStack = Minecraft.getInstance().player.containerMenu.getCarried();
        if (!cursorStack.isEmpty()) {
             this.draggedItem = cursorStack.copy();
        }

        this.draggedItem = ItemStack.EMPTY;

        return super.mouseClicked(event, doubleClicked);
    }

    private void handleSlotClick(int slotIndex, int button) {
        if (button == 1) {
            inputs[slotIndex] = ItemStack.EMPTY;
        } else if (!draggedItem.isEmpty()) {
            inputs[slotIndex] = draggedItem.copy();
        }
    }

    private void handleExternalDrag() { }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isDropdownOpen && mouseX >= this.modeSelector.getX() && mouseX <= this.modeSelector.getX() + this.modeSelector.getWidth()) {
            int maxScroll = Math.max(0, Mode.values().length - MAX_DROPDOWN_VISIBLE);
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

    private void drawSlot(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        graphics.fill(x, y, x + 18, y + 18, 0xFFBDBDBD);
        graphics.fill(x, y, x + 18, y + 1, 0xFF707070);
        graphics.fill(x, y, x + 1, y + 18, 0xFF707070);

        if (mouseX >= x && mouseX <= x + 18 && mouseY >= y && mouseY <= y + 18) {
            graphics.fill(x, y, x + 18, y + 18, 0x80FFFFFF);
        }
    }

    private record SearchEntry(ItemStack stack) {}

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