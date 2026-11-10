package sdlc.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sdlc.util.Json;

/** JSON state files and append-only JSONL logs under .sdlc/. */
public final class Store {
    private Store() {}

    public static Map<String, Object> read(Path f) {
        try {
            return Files.exists(f) ? Json.obj(Json.parse(Files.readString(f))) : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** Writes through a temp file so a crash never leaves half a file. */
    public static void write(Path f, Object value) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp" + ProcessHandle.current().pid());
            Files.writeString(tmp, Json.pretty(value) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeText(Path f, String text) {
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Appends one event to .sdlc/logs/<stream>.jsonl. Logging never fails the caller. */
    public static void log(String stream, Map<String, Object> fields) {
        if (Ctx.root == null || !Files.exists(Ctx.root.resolve(".git"))) return; // not a repository (e.g. the kit's template folder): keep no state
        try {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ts", Instant.now().toString());
            e.putAll(fields);
            Path f = Ctx.work("logs", stream + ".jsonl");
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.write(e) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // logging is best effort
        }
    }

    public static List<Map<String, Object>> readLog(String stream) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path f = Ctx.work("logs", stream + ".jsonl");
        if (!Files.exists(f)) return out;
        try {
            for (String line : Files.readAllLines(f)) {
                if (line.isBlank()) continue;
                try {
                    out.add(Json.obj(Json.parse(line)));
                } catch (RuntimeException ignored) {
                    // skip a damaged line
                }
            }
        } catch (IOException ignored) {
            // unreadable log counts as empty
        }
        return out;
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
