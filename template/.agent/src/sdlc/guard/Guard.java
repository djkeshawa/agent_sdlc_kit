package sdlc.guard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

import sdlc.core.Config;
import sdlc.core.Work;

/**
 * Everything the guard rules need to judge one tool call: where the repository is, its
 * configuration, where the agent's shell is, what git says and the task in progress. The rule
 * classes get it through their constructor instead of reading global state, so a rule's
 * answer depends only on the Guard it was built with and the call it is asked about. Git, the
 * task and the workflow's gates come in as a Repo, a Supplier and a Gate, so a test can answer
 * for them from memory. Gates.guard(folder) builds the real one.
 *
 * One Guard per tool call. It is cheap to build and is never shared or kept.
 */
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
        this.shell = new ShellRules(this); // last: it uses the rules above
    }

    /** The task the agent may edit code for, or null. Read once per tool call. */
    Work.Task task() {
        if (!taskRead) {
            task = currentTask.get();
            taskRead = true;
        }
        return task;
    }

    /** The current git branch. Read once per tool call. */
    String branch() {
        if (branch == null) branch = repo.branch();
        return branch;
    }

    /**
     * The folder the host says the shell is in (the hook's cwd, a tool call's workdir), or null
     * when it names none. It may lie outside the repository: relative paths are then read from
     * out there, so `repo/.agent/sdlc.yml` typed one folder above the repository is still seen
     * for what it is.
     */
    public static Path folder(Path root, Path base, Object dir) {
        if (!(dir instanceof String s) || s.isBlank()) return null;
        try {
            Path p = Path.of(s);
            p = (p.isAbsolute() ? p : base.resolve(p)).normalize();
            if (p.startsWith(root)) return p;
            try {
                return p.toRealPath(); // a link into the repository counts as the folder it leads to
            } catch (IOException e) {
                return p;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }
}
