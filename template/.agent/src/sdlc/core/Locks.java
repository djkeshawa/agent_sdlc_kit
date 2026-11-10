package sdlc.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.util.Json;

/**
 * Locked acceptance tests. QE approves a story's acceptance tests by locking them:
 * docs/test-locks/<STORY>.json (committed, CODEOWNERS: QE) holds the SHA-256 of each file.
 * From then on no agent may edit them, and the pre-commit hook, verify, the PR gate and
 * check-pr in CI refuse a change that does not match the lock. The check is on content, not on
 * who edited, so it holds even where a hook cannot tell the implementer from the reviewer.
 */
public final class Locks {
    private Locks() {}

    public static final String DIR = "docs/test-locks";

    /** One locked file: its path, the hash it must have, and the story whose lock names it. */
    public record Entry(String path, String hash, String story) {}

    private static String signature = "";
    private static Map<String, Entry> cached = Map.of();

    /** Every locked file of every story, by path. Re-read only when the lock folder changes. */
    public static synchronized Map<String, Entry> locked(Path root) {
        Path dir = root.resolve(DIR);
        if (!Files.isDirectory(dir)) return Map.of();
        List<Path> files;
        StringBuilder sig = new StringBuilder(root.toString());
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path f : files) sig.append('|').append(f.getFileName()).append(':').append(Files.getLastModifiedTime(f).toMillis()).append(':').append(Files.size(f));
        } catch (IOException e) {
            return Map.of();
        }
        if (sig.toString().equals(signature)) return cached;
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Path f : files) {
            try {
                out.putAll(parse(Files.readString(f)));
            } catch (IOException | RuntimeException e) {
                // a damaged lock file locks nothing; check-pr reports it
            }
        }
        signature = sig.toString();
        cached = out;
        return out;
    }

    static Map<String, Entry> parse(String json) {
        Map<String, Entry> out = new LinkedHashMap<>();
        Object root = Json.parse(json);
        String story = Json.str(root, "story");
        if (Json.at(root, "files") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), new Entry(String.valueOf(e.getKey()), String.valueOf(e.getValue()), story));
        }
        return out;
    }

    public static boolean isLocked(Path root, String rel) {
        return locked(root).containsKey(rel.replace('\\', '/'));
    }

    public static boolean hasStory(Path root, String story) {
        return Files.isRegularFile(root.resolve(DIR).resolve(story + ".json"));
    }

    /** Locked files whose content no longer matches the lock (or that are gone), as "path (STORY)". */
    public static List<String> broken(Path root) {
        List<String> out = new ArrayList<>();
        for (Entry e : locked(root).values()) {
            Path f = root.resolve(e.path());
            try {
                if (!Files.isRegularFile(f)) out.add(e.path() + " (" + e.story() + "): deleted");
                else if (!hash(Files.readAllBytes(f)).equals(e.hash())) out.add(e.path() + " (" + e.story() + "): changed");
            } catch (IOException ex) {
                out.add(e.path() + " (" + e.story() + "): unreadable");
            }
        }
        return out;
    }

    /** SHA-256 of a file's content with line endings made equal (a checkout on Windows is the same file). */
    public static String hash(byte[] content) {
        try {
            String text = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
