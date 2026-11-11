package io.github.billstark001.worldmirror.config;

import me.shedaniel.autoconfig.AutoConfigClient;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.util.Utils;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.DropdownMenuBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.LongStream;

/** Installs the project's Cloth Config widgets for enum choices and explicit field opt-ins. */
@Environment(EnvType.CLIENT)
public final class EnumDropdownGui {
    private static boolean registered;

    private EnumDropdownGui() { }

    /** Enum fields use the select; booleans and short integer ranges opt in explicitly. */
    public static synchronized void register() {
        if (registered) return;
        var registry = AutoConfigClient.getGuiRegistry(ModConfig.class);
        registry.registerPredicateProvider(
                EnumDropdownGui::createEntry,
                field -> isConfigurable(field) && field.getType().isEnum());
        registry.registerPredicateProvider(
                EnumDropdownGui::createBooleanEntry,
                field -> isConfigurable(field) && field.isAnnotationPresent(SelectDropdown.class)
                        && (field.getType() == boolean.class || field.getType() == Boolean.class));
        registry.registerPredicateProvider(
                EnumDropdownGui::createSmallIntegerEntry, EnumDropdownGui::isSmallIntegerRange);
        registry.registerPredicateProvider(
                EnumDropdownGui::createIntegerInputEntry,
                EnumDropdownGui::isDirectIntegerInput);
        registered = true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<AbstractConfigListEntry> createEntry(
            String translationKey, Field field, Object config, Object defaults, Object registry) {
        List<Enum> values = Arrays.asList((Enum[]) field.getType().getEnumConstants());
        Enum current = Utils.getUnsafely(field, config);
        Function<Enum, Component> label = value -> valueLabel(translationKey, value);

        return selectEntry(translationKey, field, config, defaults,
                values, label, current);
    }

    private static List<AbstractConfigListEntry> createBooleanEntry(
            String translationKey, Field field, Object config, Object defaults, Object registry) {
        Boolean current = Utils.getUnsafely(field, config);
        return selectEntry(translationKey, field, config, defaults,
                List.of(false, true),
                value -> Component.translatable(value ? "gui.yes" : "gui.no"), current);
    }

    private static List<AbstractConfigListEntry> createSmallIntegerEntry(
            String translationKey, Field field, Object config, Object defaults, Object registry) {
        ConfigEntry.BoundedDiscrete range = field.getAnnotation(ConfigEntry.BoundedDiscrete.class);
        Integer current = Utils.getUnsafely(field, config);
        List<Integer> values = LongStream.rangeClosed(range.min(), range.max())
                .mapToInt(Math::toIntExact).boxed().toList();
        return selectEntry(translationKey, field, config, defaults,
                values, value -> Component.literal(value.toString()), current);
    }

    private static List<AbstractConfigListEntry> createIntegerInputEntry(
            String translationKey, Field field, Object config, Object defaults, Object registry) {
        ConfigEntry.BoundedDiscrete range = field.getAnnotation(ConfigEntry.BoundedDiscrete.class);
        Integer current = Utils.getUnsafely(field, config);
        var entry = ConfigEntryBuilder.create()
                .startIntField(Component.translatable(translationKey), current)
                .setMin(Math.toIntExact(range.min()))
                .setMax(Math.toIntExact(range.max()))
                .setDefaultValue(() -> Utils.getUnsafely(field, defaults))
                .setSaveConsumer(value -> Utils.setUnsafely(field, config, value))
                .setTooltipSupplier(tooltipFor(translationKey, field))
                .build();
        return Collections.singletonList(entry);
    }

    private static boolean isSmallIntegerRange(Field field) {
        if (!isConfigurable(field) || !field.isAnnotationPresent(SelectDropdown.class)
                || field.isAnnotationPresent(DirectIntegerInput.class)) return false;
        if (field.getType() != int.class && field.getType() != Integer.class) return false;
        ConfigEntry.BoundedDiscrete range = field.getAnnotation(ConfigEntry.BoundedDiscrete.class);
        return range != null && range.min() >= Integer.MIN_VALUE
                && range.max() <= Integer.MAX_VALUE && range.max() >= range.min()
                && (long) range.max() - range.min() <= 15;
    }

    private static boolean isDirectIntegerInput(Field field) {
        if (!isConfigurable(field) || !field.isAnnotationPresent(DirectIntegerInput.class)) return false;
        if (field.getType() != int.class && field.getType() != Integer.class) return false;
        ConfigEntry.BoundedDiscrete range = field.getAnnotation(ConfigEntry.BoundedDiscrete.class);
        return range != null && range.min() >= Integer.MIN_VALUE
                && range.max() <= Integer.MAX_VALUE && range.max() >= range.min();
    }

    private static boolean isConfigurable(Field field) {
        return !Modifier.isStatic(field.getModifiers())
                && !field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class);
    }

    private static <T> List<AbstractConfigListEntry> selectEntry(
            String translationKey, Field field, Object config, Object defaults,
            List<T> values, Function<T, Component> label, T current) {
        ClosingDropdownEntry<T> entry = new ClosingDropdownEntry<>(
                Component.translatable(translationKey),
                ConfigEntryBuilder.create().getResetButtonKey(),
                tooltipFor(translationKey, field),
                () -> Utils.getUnsafely(field, defaults),
                value -> Utils.setUnsafely(field, config, value),
                values,
                EnumDropdownGuiApi.createTopCell(current, label),
                DropdownMenuBuilder.CellCreatorBuilder.of(label));
        return Collections.singletonList(entry);
    }

    private static Supplier<Optional<Component[]>> tooltipFor(String translationKey, Field field) {
        return field.isAnnotationPresent(ConfigEntry.Gui.Tooltip.class)
                ? () -> Optional.of(new Component[]{
                        Component.translatable(translationKey + ".@Tooltip")})
                : Optional::empty;
    }

    /** Uses AutoConfig's field-specific value keys, with a readable fallback for future enums. */
    public static Component valueLabel(String translationKey, Enum<?> value) {
        String valueKey = translationKey + "." + value.name().toLowerCase(Locale.ROOT);
        if (!I18n.get(valueKey).equals(valueKey)) return Component.translatable(valueKey);
        String[] words = value.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder fallback = new StringBuilder();
        for (String word : words) {
            if (!fallback.isEmpty()) fallback.append(' ');
            fallback.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return Component.literal(fallback.toString());
    }

}
