package sdlc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Where things are: repository root, configuration, local secrets, the .sdlc work folder. */
final class Ctx {
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    static Path root;
    static Config cfg;
    private static Map<String, String> secrets;

    private Ctx() {}

    static void init(Path start) throws IOException {
        root = gitRoot(start);
        cfg = Config.load(root.resolve(".agent/sdlc.yml"));
        secrets = null;
    }

    static Path gitRoot(Path start) {
        Proc p = Proc.run(start, "git", "rev-parse", "--show-toplevel");
        return p.ok() ? Path.of(p.out().trim()) : start.toAbsolutePath();
    }

    /** .sdlc/ - local, git-ignored working folder. */
    static Path work(String... parts) {
        Path p = root.resolve(".sdlc");
        for (String part : parts) p = p.resolve(part);
        return p;
    }

    /** Environment variables win over .agent/local.env. */
    static String secret(String key, String fallback) {
        if (secrets == null) secrets = loadSecrets();
        String v = secrets.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    static String need(String key, String hint) {
        String v = secret(key, "");
        if (v.isBlank()) throw new Main.UsageException(key + " is not set. Put it in .agent/local.env (" + hint + ").");
        return v;
    }

    private static Map<String, String> loadSecrets() {
        Map<String, String> out = new LinkedHashMap<>();
        Path f = root == null ? null : root.resolve(".agent/local.env");
        if (f != null && Files.exists(f)) {
            try {
                for (String line : Files.readAllLines(f)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
                    String k = t.substring(0, t.indexOf('=')).trim().replaceFirst("^export\\s+", "");
                    String v = t.substring(t.indexOf('=') + 1).trim();
                    if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) v = v.substring(1, v.length() - 1);
                    out.put(k, v);
                }
            } catch (IOException ignored) {
                // unreadable file counts as empty
            }
        }
        System.getenv().forEach((k, v) -> {
            if (!v.isBlank()) out.put(k, v);
        });
        return out;
    }

    static String javaBinary() {
        Path java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
        return Files.exists(java) ? java.toString() : "java";
    }

    static String rel(Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
