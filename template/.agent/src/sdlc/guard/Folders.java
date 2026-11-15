package sdlc.guard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Folders {
    private final Path root;
    private final Path start;
    private Path cd;
    private final List<Path> visited = new ArrayList<>();

    Folders(Path root, Path start) {
        this.root = root;
        this.start = start;
    }

    Path base() {
        return cd != null ? cd : start != null ? start : root;
    }

    public List<Path> bases() {
        Set<Path> out = new LinkedHashSet<>();
        out.add(base());
        out.add(root);
        if (start != null) out.add(start);
        out.addAll(visited);
        return new ArrayList<>(out);
    }

    public Path cd() {
        return cd;
    }

    void enter(Path to) {
        cd = to;
        if (!visited.contains(to)) visited.add(to);
    }

    void returnTo(Path earlier) {
        cd = earlier;
    }

    void forgetLine() {
        cd = null;
        visited.clear();
    }
}
