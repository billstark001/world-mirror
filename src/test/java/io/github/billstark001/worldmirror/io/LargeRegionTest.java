package io.github.billstark001.worldmirror.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LargeRegionTest {
    @Test
    void regionLargerThanTheHeapIsProcessedWithoutWholeRegionRetention(@TempDir Path root) throws Exception {
        String classpath = System.getProperty("worldmirror.test.classpath");
        assertNotNull(classpath);
        Path arguments = root.resolve("java.args");
        List<String> options = List.of("-Xmx160m", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", classpath, LargeRegionProbe.class.getName(), root.resolve("world").toString());
        Files.write(arguments, options.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList());
        Path log = root.resolve("probe.log");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(javaExecutable.toString(), "@" + arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(150, TimeUnit.SECONDS), "Small-heap probe timed out");
            String output = new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("PASS: 288 MiB"), output);
            assertTrue(output.contains("PASS: 288 MiB conflicts applied"), output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
    }
}
