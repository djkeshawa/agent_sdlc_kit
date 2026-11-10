package sdlc.guard;

import java.nio.file.Path;

import sdlc.core.Git;
import sdlc.util.Proc;

/** The repository as the git command line reports it. */
public final class GitCli implements Repo {
    private final Path root;

    public GitCli(Path root) {
        this.root = root;
    }

    @Override
    public String branch() {
        return Git.branch(root);
    }

    @Override
    public boolean inHead(String rel) {
        return Proc.run(root, "git", "cat-file", "-e", "HEAD:" + rel).ok();
    }

    @Override
    public boolean changedSinceHead(String rel) {
        return !Proc.run(root, "git", "diff", "--quiet", "HEAD", "--", rel).ok();
    }

    @Override
    public String alias(String name) {
        Proc p = Proc.run(root, "git", "config", "--get", "alias." + name);
        return p.ok() ? p.out().trim() : "";
    }
}
