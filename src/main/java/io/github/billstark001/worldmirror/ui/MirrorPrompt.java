package io.github.billstark001.worldmirror.ui;

/** Version-neutral description of the small native prompts used by the client. */
public final class MirrorPrompt {
    public record Text(String key, Object... arguments) {}

    public record Confirmation(Text title, Text message, Text accept, Text cancel) {}

    public record Alert(Text title, Text message, Text acknowledge) {}

    public record Upgrade(Text title, Text description, Text upgrade) {}

    public interface ProgressHandle {
        void stage(Text stage);

        void close();
    }

    private MirrorPrompt() {}
}
