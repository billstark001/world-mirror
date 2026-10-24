package io.github.billstark001.worldmirror.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnumTranslationCoverageTest {
    private static final List<String> LOCALES = List.of("en_us", "zh_cn", "zh_tw", "ja_jp");
    private static final String ROOT_KEY = "text.autoconfig.worldmirror.option";

    @Test
    void exportStatesStagesAndNoticePlaceholdersExistInEveryLocale() throws Exception {
        java.util.Map<String,Integer> expected = new java.util.HashMap<>();
        for (var state : io.github.billstark001.worldmirror.download.ExportStatus.State.values())
            expected.put("screen.worldmirror.export.state."+state.name().toLowerCase(Locale.ROOT),0);
        for (var part : io.github.billstark001.worldmirror.download.ExportStatus.PartState.values())
            expected.put("screen.worldmirror.export.part."+part.name().toLowerCase(Locale.ROOT),0);
        for (var stage : io.github.billstark001.worldmirror.download.ExportStatus.FailureStage.values())
            expected.put("screen.worldmirror.export.stage."+stage.name().toLowerCase(Locale.ROOT),0);
        expected.put("screen.worldmirror.export.state",1);
        expected.put("screen.worldmirror.export.parts",2);
        expected.put("screen.worldmirror.export.metadata",0);
        expected.put("screen.worldmirror.export.unresolved",0);
        expected.put("screen.worldmirror.export.historical",0);
        expected.put("screen.worldmirror.export.health",4);
        expected.put("msg.worldmirror.exportHealth",4);
        expected.put("msg.worldmirror.exportToast.health",2);
        for (String message : List.of("exportFailed","exportPartial","exportRecovered")) {
            expected.put("msg.worldmirror."+message,0);
            expected.put("msg.worldmirror.exportToast."+message,0);
        }
        for (String locale : LOCALES) {
            try (var stream = getClass().getClassLoader().getResourceAsStream("assets/worldmirror/lang/"+locale+".json")) {
                assertNotNull(stream);
                JsonObject translations = JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
                expected.forEach((key,count) -> {
                    assertTrue(translations.has(key),locale+" "+key);
                    org.junit.jupiter.api.Assertions.assertEquals(count.longValue(),
                            java.util.regex.Pattern.compile("%(?:\\d+\\$)?[sd]").matcher(translations.get(key).getAsString()).results().count(),
                            locale+" "+key+" placeholders");
                });
                assertTrue(translations.get("screen.worldmirror.chunkmap.conflict").getAsString().startsWith("§d"));
            }
        }
    }

    @Test
    void everyConfigEnumChoiceHasAFieldSpecificTranslationInEveryLocale() throws Exception {
        for (String locale : LOCALES) {
            String resource = "assets/worldmirror/lang/" + locale + ".json";
            try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(stream, "Missing language resource " + resource);
                JsonObject translations = JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEnumTranslations(ModConfig.class, ROOT_KEY, translations, locale);
            }
        }
    }

    private static void assertEnumTranslations(
            Class<?> configType, String prefix, JsonObject translations, String locale) {
        for (Field field : configType.getDeclaredFields()) {
            if (field.getType().isEnum()) {
                for (Object constant : field.getType().getEnumConstants()) {
                    String value = ((Enum<?>) constant).name().toLowerCase(Locale.ROOT);
                    String key = prefix + "." + field.getName() + "." + value;
                    assertTrue(translations.has(key), () -> "Missing " + locale + " key " + key);
                }
            } else if (field.isAnnotationPresent(ConfigEntry.Gui.CollapsibleObject.class)) {
                assertEnumTranslations(field.getType(), prefix + "." + field.getName(),
                        translations, locale);
            }
        }
    }
}
