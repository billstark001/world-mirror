package io.github.billstark001.worldmirror.ui;

/** Version-neutral description of the small native prompts used by the client. */
public final class MirrorPrompt {
    public record Text(String key, Object... arguments) {}

    public record Confirmation(Text title, Text message, Text accept, Text cancel) {}

    public record Alert(Text title, Text message, Text acknowledge) {}

    public record Upgrade(Text title, Text description, Text upgrade) {}

    public interface ProgressHandle {
        void stage(Text stage);

        /** Updates the native progress bar with completed work out of total work. */
        void progress(int completed, int total);

        void close();
    }

    private MirrorPrompt() {}
}
