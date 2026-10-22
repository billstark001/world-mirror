package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.util.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MirrorMappingPathTest {
    @ParameterizedTest
    @CsvSource({"local:.,unnamed", "local:..,unnamed", "server:CON,_CON", "local:aux.txt,_aux.txt",
            "local:nul,_nul", "local:COM1,_COM1", "local:LPT9.foo,_LPT9.foo", "local:world.,world"})
    void generatedNamesAreUsableSingleFolderSegments(String sourceId, String expected, @TempDir Path root)
            throws Exception {
        String name = new MirrorMapping().previewBaseFolderName(sourceId);
        assertEquals(expected, name);
        assertEquals(root, Files.createDirectory(root.resolve(name)).getParent());
    }

    @Test
    void ignoresUnsafePersistedNamesWithoutRewritingTheMapping() {
        String document = """
                {"entries":{"server:test":"../outside"},
                 "resolvedFolderNames":{"server:test\\u0000SAVES":"C:\\\\outside"}}
                """;
        MirrorMapping mapping = JsonSupport.fromJson(new StringReader(document), MirrorMapping.class);

        assertEquals("test", mapping.previewBaseFolderName("server:test"));
        assertNull(mapping.previewResolvedFolderName("server:test", "SAVES"));
    }
}
