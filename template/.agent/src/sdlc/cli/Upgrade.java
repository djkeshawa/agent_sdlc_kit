package sdlc.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.util.Glob;
import sdlc.util.Proc;

/**
 * sdlc upgrade --from <kit> [--version <tag>] [--check]: brings this repository's copy of the kit
 * to a pinned version, so twenty repositories do not drift into twenty kits. <kit> is a local
 * checkout (the kit repository, or its template/ folder) or a git URL (cloned at --version).
 * Only kit-owned files are copied; .agent/sdlc.yml, local.env, CODEOWNERS and the pull request
 * template stay yours. Agent model lines are re-applied from sdlc.yml. --check only reports the
 * difference (exit 1 when out of date), e.g. as a CI stage. A human runs it; agents are refused.
 */
public final class Upgrade {
    private Upgrade() {}

    /** Kit-owned paths, relative to the template root. */
    static final List<String> OWNED = List.of(".agent/Sdlc.java", ".agent/src/**", ".agent/bin/**", ".agent/templates/**", ".agent/README.md", ".agent/org.yml",
            ".agent/local.env.example", ".githooks/**", ".github/agents/**", ".github/prompts/**", ".github/skills/**", ".github/instructions/**",
            ".github/hooks/**", ".github/copilot-instructions.md", "AGENTS.md");

    public static int run(String from, String version, boolean check) throws IOException {
        if (from.isBlank()) throw new UsageException("usage: sdlc upgrade --from <kit checkout or git URL> [--version <tag>] [--check]");
        Path tmp = null;
        Path src;
        if (from.matches("^(https?://|git@|ssh://).*")) {
            tmp = Files.createTempDirectory("sdlc-kit");
            List<String> cmd = new ArrayList<>(List.of("git", "clone", "-q", "--depth", "1"));
            if (!version.isBlank()) cmd.addAll(List.of("--branch", version));
            cmd.addAll(List.of("--", from, tmp.toString()));
            Proc r = Proc.run(Ctx.root, cmd.toArray(new String[0]));
            if (r.code() != 0) throw new UsageException("could not clone " + from + (version.isBlank() ? "" : " at " + version) + ": " + (r.out() + r.err()).strip());
            src = tmp;
        } else {
            src = Path.of(from).toAbsolutePath().normalize();
        }
        try {
            Path root = Files.isDirectory(src.resolve("template/.agent")) ? src.resolve("template") : src;
            if (!Files.isRegularFile(root.resolve(".agent/Sdlc.java"))) throw new UsageException(from + " is not the kit (no template/.agent/Sdlc.java or .agent/Sdlc.java)");
            if (root.equals(Ctx.root)) throw new UsageException("--from is this repository");
            List<String> changed = new ArrayList<>(), added = new ArrayList<>(), local = new ArrayList<>();
            List<String> theirs = owned(root), ours = owned(Ctx.root);
            for (String rel : theirs) {
                Path to = Ctx.root.resolve(rel);
                String want = expected(rel, Files.readString(root.resolve(rel)));
                if (!Files.exists(to)) added.add(rel);
                else if (!lf(Files.readString(to)).equals(lf(want))) changed.add(rel); // a checkout may turn *.cmd into CRLF (.gitattributes): same file
                else continue;
                if (check) continue;
                Files.createDirectories(to.getParent());
                Files.writeString(to, want);
                if (rel.startsWith(".agent/bin/") || rel.startsWith(".githooks/")) to.toFile().setExecutable(true);
            }
            for (String rel : ours) if (!theirs.contains(rel)) local.add(rel); // your own agents or skills, or files the kit dropped: kept, listed

            String label = version.isBlank() ? describe(src) : version;
            changed.forEach(f -> System.out.println("changed  " + f));
            added.forEach(f -> System.out.println("added    " + f));
            local.forEach(f -> System.out.println("local    " + f + " (not in the kit; kept)"));
            int n = changed.size() + added.size();
            if (check) {
                System.out.println(n == 0 ? "The kit is up to date with " + label + "." : n + " kit file(s) differ from " + label + ". A human runs: java .agent/Sdlc.java upgrade --from " + from
                        + (version.isBlank() ? "" : " --version " + version));
                return n == 0 ? 0 : 1;
            }
            Store.write(Ctx.root.resolve(".agent/kit.version"), Store.map("version", label, "from", from, "at", Instant.now().toString(), "files", theirs.size()));
            System.out.println(n + " kit file(s) updated to " + label + ". Next: java .agent/Sdlc.java selftest, config apply (if you use Claude Code or Codex), "
                    + "then commit on a branch; CODEOWNERS review the guardrail changes.");
            return 0;
        } finally {
            if (tmp != null) delete(tmp);
        }
    }

    /** The file as this repository should have it: agent files carry this repository's model choice. */
    private static String expected(String rel, String text) {
        if (!rel.startsWith(".github/agents/") || !rel.endsWith(".agent.md")) return text;
        String name = rel.substring(".github/agents/".length(), rel.length() - ".agent.md".length());
        return Setup.setModel(text, Ctx.cfg.str("agents." + name + ".model"));
    }

    private static String lf(String s) {
        return s.replace("\r\n", "\n");
    }

    static List<String> owned(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".agent/.build/") || rel.startsWith(".git/")) continue;
                if (Glob.any(OWNED, rel)) out.add(rel);
            }
        }
        return out;
    }

    private static String describe(Path src) {
        Proc r = Proc.run(src, "git", "describe", "--tags", "--always", "--dirty");
        return r.code() == 0 && !r.out().isBlank() ? r.out().strip() : src.toString();
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
