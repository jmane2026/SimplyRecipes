package com.jmane2026.simplyrecipes;

import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Mod(SimplyRecipes.MODID)
public class SimplyRecipes {
    public static final String MODID = "simplyrecipes";
    public static final Logger LOGGER = LogUtils.getLogger();

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
                .playToClient(ProvideRecipesPayload.TYPE, ProvideRecipesPayload.STREAM_CODEC, NetworkHandler::handleProvideRecipes);
    }

    private void createRecipeDirectory() {
        Path recipePath = FMLPaths.GAMEDIR.get().resolve("simplyrecipes");
        try {
            if (!Files.exists(recipePath)) {
                Files.createDirectories(recipePath);
                LOGGER.info("Created simplyrecipes directory at {}", recipePath);
            }

            Path mcmeta = recipePath.resolve("pack.mcmeta");
            if (!Files.exists(mcmeta)) {
                String content = "{\"pack\":{\"description\":\"Simply Recipes External Overrides\",\"pack_format\":57}}";
                Files.writeString(mcmeta, content);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to initialize simplyrecipes directory", e);
        }
    }

    private void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.SERVER_DATA) {
            Path recipePath = FMLPaths.GAMEDIR.get().resolve("simplyrecipes");

            PackLocationInfo info = new PackLocationInfo(
                    "simplyrecipes_external",
                    Component.literal("Simply Recipes External"),
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
            Pack pack = Pack.readMetaAndCreate(info, resourcesSupplier, PackType.SERVER_DATA, selectionConfig);

            if (pack != null) {
                event.addRepositorySource((packConsumer) -> packConsumer.accept(pack));
            }
        }
    }
}
