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

public final class Locks {
    private Locks() {}

    public static final String DIR = "docs/test-locks";

    public record Entry(String path, String hash, String story) {}

    private static String signature = "";
    private static Map<String, Entry> cached = Map.of();
    private static List<String> cachedDamaged = List.of();

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
                // a damaged lock must not switch the protection off
                damaged.add(DIR + "/" + f.getFileName() + " (lock file): cannot be read");
            }
        }
        signature = sig.toString();
        cached = out;
        cachedDamaged = damaged;
        return out;
    }

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

    public static boolean hasStory(Path root, String story) {
        return Files.isRegularFile(root.resolve(DIR).resolve(story + ".json")) && damaged(root).stream().noneMatch(d -> d.startsWith(DIR + "/" + story + ".json "));
    }

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

    // Read at the base branch: a pull request cannot loosen a lock by editing or deleting its own copy.
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

    public static String hash(byte[] content) {
        try {
            String text = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
