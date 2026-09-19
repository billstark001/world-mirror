package io.github.billstark001.worldmirror.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;

/** Shared JSON serialization policy for model-backed World Mirror documents. */
public final class JsonSupport {
    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Gson COMPACT_WITH_NULLS = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private JsonSupport() { }

    public static <T> T fromJson(Reader source, Class<T> modelType) {
        return PRETTY.fromJson(source, modelType);
    }

    public static String toPrettyJson(Object model) {
        return PRETTY.toJson(model);
    }

    public static void writePrettyJson(Writer destination, Object model) {
        PRETTY.toJson(model, destination);
    }

    public static String toCompactJsonWithNulls(Object model) {
        return COMPACT_WITH_NULLS.toJson(model);
    }
}
