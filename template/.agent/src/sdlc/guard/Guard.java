package sdlc.guard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

import sdlc.core.Config;
import sdlc.core.Work;

public final class Guard {
    final Path root;
    final Config cfg;
    public final Folders folders;
    final Repo repo;
    final Gate gate;
    public final FileRules files;
    final GitRules git;
    final GhRules gh;
    public final McpRules mcp;
    public final ShellRules shell;

    private final Supplier<Work.Task> currentTask;
    private Work.Task task;
    private boolean taskRead;
    private String branch;

    public Guard(Path root, Config cfg, Path start, Repo repo, Supplier<Work.Task> currentTask, Gate gate) {
        this.root = root;
        this.cfg = cfg;
        this.folders = new Folders(root, start);
        this.repo = repo;
        this.currentTask = currentTask;
        this.gate = gate;
        this.files = new FileRules(this);
        this.git = new GitRules(this);
        this.gh = new GhRules(this);
        this.mcp = new McpRules(this);
        this.shell = new ShellRules(this);
    }

    Work.Task task() {
        if (!taskRead) {
            task = currentTask.get();
            taskRead = true;
        }
        return task;
    }

    String branch() {
        if (branch == null) branch = repo.branch();
        return branch;
    }

    public static Path folder(Path root, Path base, Object dir) {
        if (!(dir instanceof String s) || s.isBlank()) return null;
        try {
            Path p = Path.of(s);
            p = (p.isAbsolute() ? p : base.resolve(p)).normalize();
            if (p.startsWith(root)) return p;
            try {
                return p.toRealPath();
            } catch (IOException e) {
                return p;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }
}
