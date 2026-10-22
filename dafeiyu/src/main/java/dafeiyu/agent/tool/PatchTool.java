package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

final class PatchTool implements Tool {

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "patchText": {"type": "string", "description": "The full patch text describing add, update, and delete operations"}
            }, "required": ["patchText"]}""");

    private record Change(String type, Path target, Path moveTo, String content, Charset charset, boolean bom) {}

    @Override
    public String name() {
        return "patch";
    }

    @Override
    public String description() {
        return """
                Use the `patch` tool to edit several files at once. The patch language is a stripped-down, file-oriented diff format designed to be easy to parse and safe to apply:

                *** Begin Patch
                [ one or more file sections ]
                *** End Patch

                Each file section starts with one of three headers:

                *** Add File: <path> - create a new file. Every following line is a + line (the initial contents).
                *** Delete File: <path> - move an existing file to the .trash folder. Nothing follows.
                *** Update File: <path> - patch an existing file in place (optionally with a rename via *** Move to: <path>).

                Inside an Update section, each change starts with @@ (optionally followed by a line of nearby context such as a function name), \
                then lines prefixed with ' ' (unchanged context), '-' (removed) or '+' (added). Include enough context lines to locate the change.

                Example patch:

                *** Begin Patch
                *** Add File: hello.txt
                +Hello world
                *** Update File: src/app.py
                *** Move to: src/main.py
                @@ def greet():
                -print("Hi")
                +print("Hello, world!")
                *** Delete File: obsolete.txt
                *** End Patch

                - You must include a header with your intended action (Add/Delete/Update)
                - You must prefix new lines with `+` even when creating a new file
                - All changes are verified before any file is touched; if one section fails, nothing is written""";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        String text = Args.string(args, "patchText");
        if (text.isBlank()) {
            throw new ToolFailure("patchText is required");
        }
        List<Patch.Hunk> hunks = Patch.parse(text);
        List<Change> changes = new ArrayList<>();
        Map<Path, String> updated = new HashMap<>();
        for (Patch.Hunk hunk : hunks) {
            Path target = ws.resolve(hunk.path());
            String display = ws.relative(target);
            switch (hunk) {
                case Patch.Add add -> {
                    if (Files.isDirectory(target)) {
                        throw new ToolFailure("patch verification failed: " + display + " is a directory");
                    }
                    String content = add.contents().isEmpty() || add.contents().endsWith("\n") ? add.contents() : add.contents() + "\n";
                    changes.add(new Change("A", target, null, content, StandardCharsets.UTF_8, false));
                }
                case Patch.Delete ignored -> {
                    if (!Files.isRegularFile(target)) {
                        throw new ToolFailure("patch verification failed: Failed to delete " + display + ": file does not exist");
                    }
                    changes.add(new Change("D", target, null, null, null, false));
                }
                case Patch.Update update -> {
                    if (!Files.isRegularFile(target)) {
                        throw new ToolFailure("patch verification failed: Failed to read file to update " + display + ": file does not exist");
                    }
                    byte[] bytes = TextFile.readBytes(target, display);
                    if (TextFile.isBinary(bytes)) {
                        throw new ToolFailure("patch verification failed: " + display + " is a binary file");
                    }
                    TextFile.Decoded decoded = TextFile.decode(bytes);
                    String original = updated.getOrDefault(target, decoded.text());
                    String content = Patch.derive(display, update.chunks(), original);
                    Path moveTo = update.movePath() == null ? null : ws.resolve(update.movePath());
                    changes.add(new Change("M", target, moveTo, content, decoded.charset(), decoded.bom()));
                    if (moveTo == null) {
                        updated.put(target, content);
                    }
                }
            }
        }
        List<String> applied = new ArrayList<>();
        for (Change change : changes) {
            ReentrantLock lock = Workspace.lock(change.target());
            lock.lock();
            try {
                switch (change.type()) {
                    case "D" -> {
                        ws.trash(change.target());
                        applied.add("D " + ws.relative(change.target()));
                    }
                    case "A" -> {
                        ws.write(change.target(), TextFile.encode(change.content(), change.charset(), false));
                        applied.add("A " + ws.relative(change.target()));
                    }
                    default -> {
                        Path destination = change.moveTo() == null ? change.target() : change.moveTo();
                        ws.write(destination, TextFile.encode(change.content(), change.charset(), change.bom()));
                        if (change.moveTo() != null && !change.moveTo().equals(change.target())) {
                            Files.deleteIfExists(change.target());
                        }
                        applied.add("M " + ws.relative(destination));
                    }
                }
            } catch (java.io.IOException e) {
                throw new ToolFailure("Failed to apply patch at " + ws.relative(change.target()) + ": " + e.getMessage()
                        + (applied.isEmpty() ? "" : ". Completed before failure: " + String.join(", ", applied)));
            } finally {
                lock.unlock();
            }
        }
        return "Success. Updated the following files:\n" + String.join("\n", applied);
    }
}
