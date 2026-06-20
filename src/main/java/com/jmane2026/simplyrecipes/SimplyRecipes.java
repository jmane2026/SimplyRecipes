package com.jmane2026.simplyrecipes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.food.FoodProperties;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import java.util.HashSet;
import java.util.Set;
import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Path;
import java.util.Optional;

@Mod(SimplyRecipes.MODID)
public class SimplyRecipes {
    public static final String MODID = "simplyrecipes";
    public static final Logger LOGGER = LogUtils.getLogger();
    
    private static final Set<Identifier> REGISTERED_CUSTOM_ITEMS = ConcurrentHashMap.newKeySet();

    public static final KeyMapping EDITOR_KEY = new KeyMapping(
            "key.simplyrecipes.open_editor",
            GLFW.GLFW_KEY_K,
            KeyMapping.Category.MISC
    );

    public SimplyRecipes(IEventBus modEventBus, ModContainer modContainer) {
        createRecipeDirectory();

        modEventBus.addListener(this::onAddPackFinders);
        modEventBus.addListener(this::registerNetworking);
        modEventBus.addListener(this::onRegisterKeyMappings);
        modEventBus.addListener(this::onRegisterItems);

        NeoForge.EVENT_BUS.addListener(this::onKeyInput);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(EDITOR_KEY);
    }

    private void onKeyInput(InputEvent.Key event) {
        if (EDITOR_KEY.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.screen == null) {
                Identifier defaultId = Identifier.fromNamespaceAndPath("minecraft", "air");
                mc.execute(() -> mc.setScreen(new EditorScreen(defaultId)));
            }
        }
    }

    private void registerNetworking(final RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(SaveRecipePayload.TYPE, SaveRecipePayload.STREAM_CODEC, NetworkHandler::handleSaveRecipe)
                .playToServer(RequestRecipesPayload.TYPE, RequestRecipesPayload.STREAM_CODEC, NetworkHandler::handleRequestRecipes)
                .playToServer(CreateItemPayload.TYPE, CreateItemPayload.STREAM_CODEC, NetworkHandler::handleCreateItem)
                .playToClient(ProvideRecipesPayload.TYPE, ProvideRecipesPayload.STREAM_CODEC, NetworkHandler::handleProvideRecipes);
    }

    private void onRegisterItems(RegisterEvent event) {
        if (event.getRegistryKey().equals(Registries.ITEM)) {
            Path dataPath = FMLPaths.GAMEDIR.get().resolve("simplyrecipes/data");
            if (!Files.exists(dataPath)) return;

            LOGGER.info("SimplyRecipes: Starting item registration scan...");

            try (Stream<Path> namespaces = Files.list(dataPath)) {
                for (Path nsPath : namespaces.filter(Files::isDirectory).toList()) {
                    String namespace = nsPath.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9._-]", "");
                    if (namespace.isEmpty()) continue;

                    Path defsPath = nsPath.resolve("item_definitions");
                    
                    if (Files.exists(defsPath)) {
                        try (Stream<Path> itemFiles = Files.list(defsPath)) {
                            for (Path jsonPath : itemFiles.filter(p -> p.toString().endsWith(".json")).toList()) {
                                try {
                                    String rawFileName = jsonPath.getFileName().toString().replace(".json", "");
                                    String pathPart = rawFileName.toLowerCase().replaceAll("[^a-z0-9._-]", "");

                                    if (pathPart.isEmpty()) continue;
                                    
                                    Identifier id = Identifier.fromNamespaceAndPath(SimplyRecipes.MODID, namespace + "_" + pathPart);
                                    
                                    ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);

                                    if (BuiltInRegistries.ITEM.containsKey(id) || !REGISTERED_CUSTOM_ITEMS.add(id)) {
                                        LOGGER.warn("SimplyRecipes: Skipping duplicate/existing ID: {}", id);
                                        continue;
                                    }

                                    LOGGER.info("SimplyRecipes: Attempting to register: {}", id);

                                    String content = Files.readString(jsonPath);
                                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();

                                    int maxStack = getSafeInt(json, "max_stack_size", 64);
                                    String rarityName = getSafeString(json, "rarity", "COMMON").toUpperCase();
                                    boolean isFireResistant = getSafeBoolean(json, "fire_resistant", false);
                                    boolean hasGlint = getSafeBoolean(json, "has_glint", false);

                                    JsonObject foodJson = json.has("food") ? json.getAsJsonObject("food") : null;
                                    boolean isFood = foodJson != null && getSafeBoolean(foodJson, "is_food", false);

                                    final int finalMaxStack = Math.max(1, Math.min(64, maxStack));
                                    final Rarity finalRarity = parseRarity(rarityName);
                                    final boolean finalFire = isFireResistant;
                                    final boolean finalGlint = hasGlint;
                                    final JsonObject finalFood = isFood ? foodJson : null;

                                    event.register(Registries.ITEM, id, () -> {
                                        try {
                                            LOGGER.info("SimplyRecipes: Supplier invoked for: {}", id);
                                            Item.Properties props = new Item.Properties()
                                                    .setId(itemKey)
                                                    .stacksTo(finalMaxStack)
                                                    .rarity(finalRarity);

                                            if (finalFire) props.fireResistant();
                                            if (finalGlint) props.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                                            
                                            if (finalFood != null) {
                                                FoodProperties food = new FoodProperties.Builder()
                                                        .nutrition(getSafeInt(finalFood, "nutrition", 4))
                                                        .saturationModifier(getSafeFloat(finalFood, "saturation", 0.3f))
                                                        .alwaysEdible()
                                                        .build();
                                                if (!getSafeBoolean(finalFood, "always_edible", false)) {
                                                }
                                                props.food(food);
                                            }

                                            return new Item(props);
                                        } catch (Exception e) {
                                            LOGGER.error("SimplyRecipes: CRITICAL error inside Item supplier for {}: {}", id, e.getMessage());
                                            throw e;
                                        }
                                    });

                                    LOGGER.info("Registered custom item: {}", id);
                                } catch (Exception e) {
                                    LOGGER.error("Failed to register custom item at {}", jsonPath, e);
                                }
                            }
                        } catch (IOException ignored) {}
                    }
                }
            } catch (IOException ignored) {}
        }
    }

    private int getSafeInt(JsonObject json, String key, int defaultValue) {
        try { return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : defaultValue; }
        catch (Exception e) { return defaultValue; }
    }

    private float getSafeFloat(JsonObject json, String key, float defaultValue) {
        try { return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsFloat() : defaultValue; }
        catch (Exception e) { return defaultValue; }
    }

    private String getSafeString(JsonObject json, String key, String defaultValue) {
        try { return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : defaultValue; }
        catch (Exception e) { return defaultValue; }
    }

    private boolean getSafeBoolean(JsonObject json, String key, boolean defaultValue) {
        try { return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsBoolean() : defaultValue; }
        catch (Exception e) { return defaultValue; }
    }

    private Rarity parseRarity(String name) {
        try {
            return Rarity.valueOf(name);
        } catch (Exception e) {
            return Rarity.COMMON;
        }
    }

    private void createRecipeDirectory() {
        Path recipePath = FMLPaths.GAMEDIR.get().resolve("simplyrecipes");
        try {
            if (!Files.exists(recipePath)) {
                Files.createDirectories(recipePath);
                LOGGER.info("Created simplyrecipes directory at {}", recipePath);
            }

            Path mcmeta = recipePath.resolve("pack.mcmeta");
            if (true) {
                String content = "{" +
                        "\"pack\":{" +
                        "\"description\":\"Simply Recipes Resources\"," +
                        "\"pack_format\":42," +
                        "\"supported_formats\":[42, 61]" +
                        "}}";
                Files.writeString(mcmeta, content);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to initialize simplyrecipes directory", e);
        }
    }

    private void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.SERVER_DATA || event.getPackType() == PackType.CLIENT_RESOURCES) {
            Path recipePath = FMLPaths.GAMEDIR.get().resolve("simplyrecipes");

            PackLocationInfo info = new PackLocationInfo(
                    "simplyrecipes_external",
                    Component.literal("Simply Recipes External Resources"),
                    PackSource.BUILT_IN,
                    Optional.empty()
            );

            Pack.ResourcesSupplier resourcesSupplier = new Pack.ResourcesSupplier() {
                @Override
                public PackResources openPrimary(PackLocationInfo location) {
                    return new PathPackResources(location, recipePath);
                }

                @Override
                public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                    return openPrimary(location);
                }
            };

            PackSelectionConfig selectionConfig = new PackSelectionConfig(true, Pack.Position.TOP, false);
            Pack pack = Pack.readMetaAndCreate(info, resourcesSupplier, event.getPackType(), selectionConfig);

            if (pack != null) {
                event.addRepositorySource((packConsumer) -> packConsumer.accept(pack));
            }
        }
    }
}
