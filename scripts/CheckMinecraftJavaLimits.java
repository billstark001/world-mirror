import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import manifold.preprocessor.PreprocessorParser;
import manifold.preprocessor.definitions.Definitions;

final class CheckMinecraftJavaLimits {
    public static void main(String[] versions) throws IOException {
        var check = new CheckJavaLimits();
        check.scan(Path.of("scripts"), (file, source) -> Set.of(source));
        check.scan(Path.of("src"), (file, source) -> variants(file, source, versions));
        check.scan(Path.of("world-mirror-format/src"), (file, source) -> Set.of(source));
        check.finish();
    }

    private static Set<String> variants(Path file, String source, String[] versions) {
        if (!source.contains("#if"))
            return Set.of(source);
        var syntax = new PreprocessorParser(source, token -> {}).parseFile((message, position) -> {
            long line =
                    source.substring(0, position).chars().filter(character -> character == '\n').count() + 1;
            throw new IllegalArgumentException(file + ":" + line + ": " + message);
        });
        if (!syntax.hasPreprocessorDirectives())
            return Set.of(source);
        Set<String> result = new LinkedHashSet<>();
        for (String version : versions) {
            var definitions = new Definitions(null);
            definitions.define("MC_VERSION", version);
            var output = new StringBuilder();
            syntax.execute(output, source, true, definitions);
            result.add(output.toString());
        }
        return result;
    }
}
