package sdlc.mandate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import sdlc.core.UsageException;
import sdlc.util.Json;
import sdlc.util.Proc;

/**
 * Approval of a mandate by the person at the keyboard. The record lives in the repository's git
 * folder, outside the working tree: it is not part of any commit, so it cannot be mistaken for
 * an approval by someone else, and a clone does not carry it. It names the blob that was
 * approved; a mandate edited afterwards is another blob, and is not approved.
 */
public final class SelfApproval {
    private SelfApproval() {}

    /** covers is the scenario the approver said covers the "this fails if" sentence, or "" for none. */
    public record Record(String blob, String by, String at, String covers) {}

    static Path store(Path root) {
        Proc p = Proc.run(root, "git", "rev-parse", "--git-path", "sdlc/mandate-approvals.json");
        if (!p.ok() || p.out().isBlank()) throw new UsageException("this is not a git repository: " + root);
        return root.resolve(p.out().strip());
    }

    /** The approval recorded for a mandate, or null. */
    public static Record of(Path root, String id) {
        Object entry = all(root).get(id);
        if (!(entry instanceof Map<?, ?>)) return null;
        return new Record(Json.str(entry, "blob"), Json.str(entry, "by"), Json.str(entry, "at"), Json.str(entry, "covers"));
    }

    static void record(Path root, String id, Record r) {
        Map<String, Object> all = all(root);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("blob", r.blob());
        entry.put("by", r.by());
        entry.put("at", r.at());
        entry.put("covers", r.covers());
        all.put(id, entry);
        Path f = store(root);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.pretty(all) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UsageException("cannot record the approval in " + f + ": " + e.getMessage());
        }
    }

    private static Map<String, Object> all(Path root) {
        Path f = store(root);
        if (!Files.isRegularFile(f)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(Json.map(Json.parse(Files.readString(f, StandardCharsets.UTF_8))));
        } catch (IOException | RuntimeException e) {
            throw new UsageException("the approval record " + f + " cannot be read: " + e.getMessage());
        }
    }
}
