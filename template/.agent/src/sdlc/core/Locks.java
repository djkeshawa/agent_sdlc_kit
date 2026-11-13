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
import sdlc.util.Proc;

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
    private static List<String> cachedDamaged = List.of();

    /** Every locked file of every story, by path. Re-read only when the lock folder changes. */
    public static synchronized Map<String, Entry> locked(Path root) {
        Path dir = root.resolve(DIR);
        if (!Files.isDirectory(dir)) {
            signature = "";
            cachedDamaged = List.of();
            return Map.of();
        }
        List<Path> files;
        StringBuilder sig = new StringBuilder(root.toString());
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path f : files) sig.append('|').append(f.getFileName()).append(':').append(Files.getLastModifiedTime(f).toMillis()).append(':').append(Files.size(f));
        } catch (IOException e) {
            signature = "";
            cached = Map.of();
            cachedDamaged = List.of(DIR + " (lock folder): cannot be read");
            return cached;
        }
        if (sig.toString().equals(signature)) return cached;
        Map<String, Entry> out = new LinkedHashMap<>();
        List<String> damaged = new ArrayList<>();
        for (Path f : files) {
            try {
                out.putAll(parse(Files.readString(f)));
            } catch (IOException | RuntimeException e) {
                // its tests cannot be checked, so it is reported as broken: a damaged lock must not switch the protection off
                damaged.add(DIR + "/" + f.getFileName() + " (lock file): cannot be read");
            }
        }
        signature = sig.toString();
        cached = out;
        cachedDamaged = damaged;
        return out;
    }

    /** Lock files that cannot be read or parsed, as "path (lock file): cannot be read". Every guard treats them as broken locks. */
    public static synchronized List<String> damaged(Path root) {
        locked(root);
        return cachedDamaged;
    }

    static Map<String, Entry> parse(String json) {
        Map<String, Entry> out = new LinkedHashMap<>();
        Object root = Json.parse(json);
        String story = Json.str(root, "story");
        if (!(Json.at(root, "files") instanceof Map<?, ?> m)) throw new IllegalArgumentException("no files");
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), new Entry(String.valueOf(e.getKey()), String.valueOf(e.getValue()), story));
        return out;
    }

    public static boolean isLocked(Path root, String rel) {
        return locked(root).containsKey(rel.replace('\\', '/'));
    }

    /** The story has a lock file that can be read. */
    public static boolean hasStory(Path root, String story) {
        return Files.isRegularFile(root.resolve(DIR).resolve(story + ".json")) && damaged(root).stream().noneMatch(d -> d.startsWith(DIR + "/" + story + ".json "));
    }

    /** Locked files whose content no longer matches the lock (or that are gone), as "path (STORY)", and lock files that cannot be read. */
    public static List<String> broken(Path root) {
        List<String> out = new ArrayList<>(damaged(root));
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

    /**
     * The locks as committed at a git ref, by path. check-pr in CI reads the base branch's locks:
     * a pull request cannot loosen a lock by editing or deleting its own copy. Lock files that
     * cannot be read there are added to {@code damaged}.
     */
    public static Map<String, Entry> lockedAt(Path root, String ref, List<String> damaged) {
        Map<String, Entry> out = new LinkedHashMap<>();
        Proc ls = Proc.run(root, "git", "ls-tree", "-z", "--name-only", ref, DIR + "/");
        if (ls.code() != 0) {
            damaged.add(DIR + " (lock folder at " + ref + "): cannot be read");
            return out;
        }
        for (String f : ls.out().split("\0")) {
            if (!f.endsWith(".json")) continue;
            Proc show = Proc.run(root, "git", "show", ref + ":" + f);
            try {
                if (show.code() != 0) throw new IllegalStateException(show.err());
                out.putAll(parse(show.out()));
            } catch (RuntimeException e) {
                damaged.add(f + " (lock file at " + ref + "): cannot be read");
            }
        }
        return out;
    }

    /**
     * Locks that existed at {@code ref} and that the working tree's lock files removed or re-hashed,
     * as "path (STORY): lock removed" or "path (STORY): lock changed". With the content check in
     * {@link #broken}, this means every file locked on the base branch still has its base content.
     */
    public static List<String> loosened(Path root, String ref) {
        List<String> out = new ArrayList<>();
        Map<String, Entry> now = locked(root);
        for (Entry e : lockedAt(root, ref, out).values()) {
            Entry n = now.get(e.path());
            if (n == null) out.add(e.path() + " (" + e.story() + "): lock removed");
            else if (!n.hash().equals(e.hash())) out.add(e.path() + " (" + e.story() + "): lock changed");
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
