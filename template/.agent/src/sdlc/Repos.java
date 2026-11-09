package sdlc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Shallow, read-only local copies of other teams' repositories in .sdlc/repos/ for agents to read. */
final class Repos {
    private Repos() {}

    static int sync() {
        var repos = Ctx.cfg.maps("github.relatedRepos");
        if (repos.isEmpty()) {
            System.out.println("No github.relatedRepos in .agent/sdlc.yml.");
            return 0;
        }
        int failed = 0;
        for (Map<String, Object> r : repos) {
            String name = Work.str(r.get("name"));
            String url = Work.str(r.get("url"));
            if (url.isBlank()) url = "https://" + Ctx.cfg.str("github.host", "github.com") + "/" + Work.str(r.get("repo")) + ".git";
            String branch = Work.str(r.get("branch"));
            Path dir = Ctx.work("repos", name);
            Proc p;
            if (Files.isDirectory(dir.resolve(".git"))) {
                p = Proc.run(dir, "git", "pull", "--ff-only", "--depth", "1");
            } else {
                if (branch.isBlank()) p = Proc.run(Ctx.root, "git", "clone", "--depth", "1", url, dir.toString());
                else p = Proc.run(Ctx.root, "git", "clone", "--depth", "1", "--branch", branch, url, dir.toString());
            }
            System.out.println((p.ok() ? "OK     " : "FAILED ") + name + "  " + url + (p.ok() ? "" : "  " + p.err().trim()));
            if (!p.ok()) failed++;
        }
        System.out.println("Local copies are in .sdlc/repos/ (read-only for agents; never commit them).");
        return failed == 0 ? 0 : 1;
    }
}
