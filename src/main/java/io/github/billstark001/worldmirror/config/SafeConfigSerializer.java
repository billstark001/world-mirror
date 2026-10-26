package io.github.billstark001.worldmirror.config;

import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Preserves an invalid original before AutoConfig can save a normalized/default model. */
final class SafeConfigSerializer extends GsonConfigSerializer<ModConfig> {
    private final Path file;
    private boolean writable = true;

    SafeConfigSerializer(Config annotation, Class<ModConfig> type) {
        super(annotation, type);
        file = FabricLoader.getInstance().getConfigDir().resolve(annotation.name() + ".json");
    }

    @Override
    public ModConfig deserialize() {
        ModConfig config;
        boolean invalid = false;
        try {
            io.github.billstark001.worldmirror.util.FileTransaction.recover(file.getParent());
            var document = JsonSupport.readDocument(file, ModConfig.class);
            if (document.state() == JsonSupport.ReadState.MISSING) return new ModConfig();
            if (document.failure() != null) throw document.failure();
            config = document.value();
            String before = JsonSupport.toCompactJsonWithNulls(config);
            config.validatePostLoad();
            invalid = !before.equals(JsonSupport.toCompactJsonWithNulls(config));
        } catch (Exception failure) {
            config = new ModConfig();
            config.defaultConflictStrategy = ModConfig.ConflictStrategy.IGNORE;
            invalid = true;
            if (Files.notExists(file)) writable = false;
            WMLogger.warn("Config load failed; automatic start stays disabled and overwrite policy is IGNORE", failure);
        }
        if (invalid && !Files.notExists(file)) {
            try {
                Path backup = Files.createTempFile(file.getParent(), file.getFileName() + ".invalid-", ".json");
                Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (Files.mismatch(file, backup) != -1) throw new java.io.IOException("Config backup mismatch");
                WMLogger.warn("Invalid config preserved at " + backup + "; review normalized settings before enabling download");
            } catch (Exception backupFailure) {
                writable = false;
                WMLogger.warn("Cannot preserve invalid config; saving this config is disabled", backupFailure);
            }
        }
        return config;
    }

    @Override
    public void serialize(ModConfig config) throws SerializationException {
        if (!writable) throw new SerializationException(new java.io.IOException("Invalid original config could not be backed up"));
        config.validatePostLoad();
        try { JsonSupport.writeDocument(file, config); }
        catch (Exception failure) { throw new SerializationException(failure); }
    }
}
