package sdlc.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sdlc.core.Ctx;
import sdlc.core.Git;
import sdlc.guard.FileRules;
import sdlc.util.Glob;
import sdlc.util.Proc;
import sdlc.workflow.Checks;
import sdlc.workflow.Gates;
import sdlc.workflow.Verify;

/** git hooks for everyone (humans and agents): pre-commit, commit-msg, pre-push. */
public final class GitHooks {
    private GitHooks() {}

    public static int run(String hook, String[] args, String stdin) throws IOException {
        return switch (hook) {
            case "pre-commit" -> preCommit();
            case "commit-msg" -> commitMsg(Path.of(args[0]));
            case "pre-push" -> prePush(stdin);
            default -> 0;
        };
    }

    static int preCommit() throws IOException {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String branch = Git.branch();
        if (Ctx.cfg.isProtectedBranch(branch)) errors.add("You are on protected branch '" + branch + "'. Commit on a feature branch: git switch -c feature/<KEY>-short-name");
        else if (!branch.isEmpty() && !branch.matches(Ctx.cfg.str("git.branchPattern", ".*"))) errors.add("Branch '" + branch + "' does not match " + Ctx.cfg.str("git.branchPattern"));
        Map<String, List<String>> added = Git.addedLines(Proc.run(Ctx.root, "git", "diff", "--cached", "--unified=0", "--no-color", "--no-ext-diff").out());
        for (String s : Checks.secrets(added)) errors.add("Possible secret: " + s + "  (false positive? add the comment guard:allow-secret on that line)");
        for (String s : Checks.disabledTests(added)) errors.add("Disabled or focused test: " + s + "  (fix the test; do not switch it off)");
        List<String> staged = Git.lines(Proc.run(Ctx.root, "git", "diff", "--cached", "--name-only", "--diff-filter=ACMR").out());
        int maxKb = Ctx.cfg.num("guard.maxFileKb", 1024);
        List<String> guardrails = new ArrayList<>();
        FileRules files = Gates.guard(null).files;
        for (String f : staged) {
            Path p = Ctx.root.resolve(f);
            if (Files.isRegularFile(p) && Files.size(p) > maxKb * 1024L) errors.add("File too large (> " + maxKb + " KB): " + f);
            if (files.isCredential(f)) errors.add("Credential file staged: " + f);
            if (f.startsWith(".sdlc/")) errors.add("Local state staged: " + f + " (add .sdlc/ to .gitignore)");
            if (Glob.any(Ctx.cfg.list("paths.protected"), f)) guardrails.add(f);
        }
        if (!guardrails.isEmpty()) warnings.add(guardrails.size() + " guardrail file(s) changed; a tech lead reviews them (CODEOWNERS)");
        List<String> deleted = Git.lines(Proc.run(Ctx.root, "git", "diff", "--cached", "--name-only", "--diff-filter=D").out());
        for (String b : stagedLockProblems(staged, deleted)) errors.add("Locked acceptance test " + b + ". The code must pass it as QE locked it; if the test is wrong, QE changes it and runs tests lock again.");
        if (Checks.testsMissing(staged)) warnings.add("Main code changed without a test change in this commit.");
        String quick = Ctx.WINDOWS ? Ctx.cfg.str("build.preCommitWindows", Ctx.cfg.str("build.preCommit")) : Ctx.cfg.str("build.preCommit");
        if (errors.isEmpty() && !quick.isBlank()) {
            System.out.println("[sdlc] running: " + quick);
            if (Proc.shell(Ctx.root, quick) != 0) errors.add("Pre-commit command failed: " + quick);
        }
        return report("pre-commit", errors, warnings);
    }

    /**
     * Locked acceptance tests this commit would change or delete, and lock files that cannot be read.
     * The staged content is what is checked (git show :path), not the working tree: that is what gets committed.
     */
    public static List<String> stagedLockProblems(List<String> staged, List<String> deleted) {
        List<String> out = new ArrayList<>(sdlc.core.Locks.damaged(Ctx.root));
        for (sdlc.core.Locks.Entry e : sdlc.core.Locks.locked(Ctx.root).values()) {
            String what = e.path() + " (" + e.story() + ")";
            if (deleted.contains(e.path())) {
                out.add(what + ": deleted");
            } else if (staged.contains(e.path())) {
                Proc blob = Proc.run(Ctx.root, "git", "show", ":" + e.path());
                if (blob.code() != 0) out.add(what + ": unreadable");
                else if (!sdlc.core.Locks.hash(blob.out().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(e.hash())) out.add(what + ": changed");
            }
        }
        return out;
    }

    static int commitMsg(Path file) throws IOException {
        String first = Files.readString(file).lines().filter(l -> !l.startsWith("#")).findFirst().orElse("").trim();
        if (first.startsWith("Merge ") || first.startsWith("Revert \"") || first.startsWith("fixup! ") || first.startsWith("squash! ")) return 0;
        List<String> errors = new ArrayList<>();
        String key = Ctx.cfg.projectKeys().isEmpty() ? "PROJ" : Ctx.cfg.projectKeys().get(0);
        if (!first.matches(Ctx.cfg.str("git.commitPattern", ".*"))) {
            errors.add("Commit subject '" + first + "' must look like:  feat(" + key + "-123): add refund endpoint");
        } else if (!Ctx.cfg.keyPattern().matcher(first).find()) {
            errors.add("Commit subject must name an issue of project(s) " + Ctx.cfg.projectKeys());
        }
        return report("commit-msg", errors, List.of());
    }

    static int prePush(String stdin) {
        List<String> errors = new ArrayList<>();
        boolean pushesBranch = false;
        for (String line : stdin.lines().toList()) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4) continue;
            String remoteRef = f[2];
            if (remoteRef.startsWith("refs/tags/")) {
                errors.add("Pushing tags is the release manager's job: " + remoteRef);
                continue;
            }
            String branch = remoteRef.replaceFirst("^refs/heads/", "");
            if (f[1].matches("0+")) errors.add("Deleting remote branch '" + branch + "' is not allowed from here.");
            else if (Ctx.cfg.isProtectedBranch(branch)) errors.add("Direct push to protected branch '" + branch + "'. Open a pull request.");
            else if (!branch.matches(Ctx.cfg.str("git.branchPattern", ".*"))) errors.add("Branch name '" + branch + "' does not match " + Ctx.cfg.str("git.branchPattern"));
            else pushesBranch = true;
        }
        if (errors.isEmpty() && pushesBranch) {
            Checks.Result r = Checks.prPolicy(null, false);
            errors.addAll(r.fails());
            if (errors.isEmpty() && !Verify.current()) {
                System.out.println("[sdlc] the build was not verified for these files; running it now");
                if (Verify.run() != 0) errors.add("The build failed. Fix it before pushing.");
            }
        }
        return report("pre-push", errors, List.of());
    }

    private static int report(String hook, List<String> errors, List<String> warnings) {
        for (String w : warnings) System.out.println("[sdlc " + hook + "] WARNING " + w);
        for (String e : errors) System.err.println("[sdlc " + hook + "] BLOCKED " + e);
        if (!errors.isEmpty()) System.err.println("[sdlc " + hook + "] Rules: .agent/sdlc.yml");
        return errors.isEmpty() ? 0 : 1;
    }
}
