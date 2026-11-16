package sdlc.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A path's name and parent without a null: Path.getFileName and Path.getParent return null for a root, and getParent for a relative path of one name ("out.png"). */
public final class Fs {
    private Fs() {}

    public static String name(Path p) {
        Path n = p.getFileName();
        return n == null ? "" : n.toString();
    }

    public static Path parent(Path p) {
        Path a = p.toAbsolutePath();
        Path up = a.getParent();
        return up == null ? a : up;
    }

    public static void makeParent(Path p) throws IOException {
        Files.createDirectories(parent(p));
    }
}
