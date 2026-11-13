package io.github.billstark001.worldmirror.legacy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.JsonSupport.Document;
import io.github.billstark001.worldmirror.util.JsonSupport.ReadState;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;

import java.io.IOException;
import java.nio.file.Path;

/** The initial schema 2 world graph used AUTO for the preferred stable-ID policy. */
public final class LegacyGraphPolicy {
    private static final Gson GSON = new Gson();

    private LegacyGraphPolicy() {}

    public record Read(Document<WorldGraphConfig.Snapshot> document, FileProof.Version version) {}

    /** Call under the graph lease with the version obtained before reading. */
    public static Read readAndUpgrade(Path file, FileProof.Version version) throws IOException {
        Document<JsonElement> document = JsonSupport.readDocument(file, JsonElement.class);
        if (document.state() != ReadState.VALID) {
            return new Read(new Document<>(document.state(), null, document.failure()), version);
        }
        var graph = document.value();
        boolean changed;
        WorldGraphConfig.Snapshot snapshot;
        try {
            changed = graph.isJsonObject() && normalizePolicies(graph);
            snapshot = GSON.fromJson(graph, WorldGraphConfig.Snapshot.class);
        } catch (RuntimeException invalid) {
            return new Read(new Document<>(ReadState.INVALID, null, invalid), version);
        }
        if (changed) {
            version = JsonSupport.compareAndWriteDocument(file, graph, version);
            WMLogger.info("Legacy world identity policies upgraded config=" + file);
        }
        return new Read(new Document<>(ReadState.VALID, snapshot, null), version);
    }

    private static boolean normalizePolicies(JsonElement graph) {
        JsonElement rules = graph.getAsJsonObject().get("endpointRules");
        if (rules == null || !rules.isJsonObject())
            return false;
        boolean changed = false;
        for (JsonElement rule : rules.getAsJsonObject().asMap().values()) {
            if (!rule.isJsonObject()) {
                continue;
            }
            var fields = rule.getAsJsonObject();
            JsonElement policy = fields.get("idPolicy");
            if (policy != null && policy.isJsonPrimitive() && policy.getAsJsonPrimitive().isString()
                    && "AUTO".equals(policy.getAsString())) {
                fields.addProperty("idPolicy", WorldGraphConfig.IdPolicy.PREFER_BOUND_ID.name());
                changed = true;
            }
        }
        return changed;
    }
}
