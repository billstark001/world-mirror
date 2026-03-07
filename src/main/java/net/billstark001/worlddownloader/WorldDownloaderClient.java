package net.billstark001.worlddownloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.billstark001.worlddownloader.util.ChunkListener;
import net.billstark001.worlddownloader.util.ContainerTracker;
import net.billstark001.worlddownloader.util.EntityTracker;
import net.billstark001.worlddownloader.util.Exporter;
import net.billstark001.worlddownloader.util.WorldExporter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public class WorldDownloaderClient implements ClientModInitializer {
    private static KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("category.worlddownloader"));
    private static KeyBinding exportKey;
    private static KeyBinding clearKey;

    public void onInitializeClient() {
        exportKey = KeyBindingHelper
                .registerKeyBinding(new KeyBinding("key.worlddownloader.export", InputUtil.Type.SCANCODE, 79, category));

        clearKey = KeyBindingHelper
                .registerKeyBinding(new KeyBinding("key.worlddownloader.clear", InputUtil.Type.SCANCODE, 76, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (exportKey.wasPressed()) {
                Path worldFolder = Path.of("downloaded_world", new String[0]);

                Path regionDir = worldFolder.resolve("region");

                try {
                    Files.createDirectories(regionDir, (FileAttribute<?>[]) new FileAttribute[0]);
                } catch (IOException e) {
                    System.err.println("❌ Couldn't create world directory: " + e.getMessage());

                    return;
                }

                try {
                    int chunkCount = ChunkListener.getAll().size();

                    if (chunkCount == 0) {
                        System.out.println("⚠ No chunks to export! Walk around to load chunks first.");

                        return;
                    }

                    System.out.println("🚀 Starting export of " + chunkCount + " chunks...");

                    System.out.println("📊 Capturing entities...");

                    EntityTracker.captureAllEntities();

                    int entityCount = EntityTracker.getTotalTrackedEntities();

                    int containerCount = ContainerTracker.getTotalSavedContainers();

                    System.out.println("📦 Found " + entityCount + " entities and " + containerCount + " containers");
                    Exporter.exportChunks();
                    WorldExporter.createLoadableWorld(worldFolder.toFile());
                    System.out.println("✅ World exported! Copy 'downloaded_world/' to '.minecraft/saves/'");
                    System.out.println("📊 Export Summary:");
                    System.out.println("   - Chunks: " + chunkCount);
                    System.out.println("   - Entities: " + entityCount);
                    System.out.println("   - Containers: " + containerCount);
                } catch (Exception e) {
                    System.err.println("❌ Failed to export world: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            while (clearKey.wasPressed()) {
                int chunkCount = ChunkListener.getAll().size();
                int entityCount = EntityTracker.getTotalTrackedEntities();
                int containerCount = ContainerTracker.getTotalSavedContainers();
                ChunkListener.clear();
                EntityTracker.clear();
                ContainerTracker.clear();
                System.out.println("🗑️ Cleared cached data:");
                System.out.println("   - Chunks: " + chunkCount);
                System.out.println("   - Entities: " + entityCount);
                System.out.println("   - Containers: " + containerCount);
            }
        });
    }
}
