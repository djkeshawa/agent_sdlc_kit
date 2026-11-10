package sdlc.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.integrations.GitHub;
import sdlc.util.Markdown;
import sdlc.util.Yaml;

/**
 * Scoping eval: how well agents pick the components a feature changes. Ground truth comes from
 * history (the repositories that got commits for an epic's Jira keys); answers are what an
 * agent picked from the same inputs. Several runs per case show consistency, not just luck.
 *
 *   <eval.dir>/scoping/cases/<KEY>.md    the input: one pager, HLD and Jira text (agents read it)
 *   .sdlc/hidden/eval/truth/<KEY>.yml    what really changed (hidden from agents, never in git: git show would reveal it)
 *   .sdlc/eval/inbox/<run>/<KEY>.yml     one agent answer: components: [a, b] (or a solution draft)
 */
public final class Eval {
    private final Workflow w;

    Eval(Workflow w) {
        this.w = w;
    }

    public static Path dir() {
        return Ctx.root.resolve(Ctx.cfg.str("eval.dir", "docs/agent/eval")).resolve("scoping");
    }

    /** The ground truth: under .sdlc/hidden (git-ignored), so no git command can show it to an agent. */
    public static Path truthDir() {
        return Ctx.work("hidden", "eval", "truth");
    }

    public static Path answers() {
        return Ctx.work("eval", "inbox");
    }

    // ---------------- collect ----------------

    /** sdlc eval collect <EPIC>...: writes the case input and the ground truth for each epic. */
    public int collect(List<String> epics) throws IOException {
        if (epics.isEmpty()) throw new UsageException("usage: sdlc eval collect <EPIC> [<EPIC> ...]");
        int written = 0;
        for (String epic : epics) {
            if (!Work.isKey(epic)) throw new UsageException("'" + epic + "' is not a Jira key");
            List<String> keys = new ArrayList<>(List.of(epic));
            for (IssueTracker.Issue child : w.issues.children(epic)) keys.add(child.key());
            Map<String, Integer> repos = GitHub.reposFor(keys);
            if (repos.isEmpty()) {
                w.console.err(epic + ": no commits found for " + keys + " (github.org = '" + Ctx.cfg.str("github.org") + "'); skipped");
                continue;
            }
            Set<String> components = new TreeSet<>();
            for (String repo : repos.keySet()) components.add(Catalog.componentOfRepo(repo));
            StringBuilder truth = new StringBuilder("# Ground truth for " + epic + ", collected by `sdlc eval collect`. Review it: remove a repository\n"
                    + "# whose commit only mentioned the key in passing. Hidden from agents; keep a copy outside this repository.\n");
            truth.append("components: [").append(String.join(", ", components)).append("]\nrepos:\n");
            repos.forEach((r, n) -> truth.append("  - {repo: ").append(r).append(", commits: ").append(n).append("}\n"));
            truth.append("keys: [").append(String.join(", ", keys)).append("]\n");
            Store.writeText(truthDir().resolve(epic + ".yml"), truth.toString());
            Store.writeText(dir().resolve("cases").resolve(epic + ".md"), caseText(epic));
            w.console.out(epic + ": " + components.size() + " component(s) " + components + " from " + repos.size() + " repo(s)");
            written++;
        }
        w.console.out("\n" + written + " case(s) in " + Ctx.rel(dir()) + ". Review " + Ctx.rel(truthDir()) + "/*.yml, then let the agents answer (/eval-scoping <run>) and run: java .agent/Sdlc.java eval score");
        return written == epics.size() ? 0 : 1;
    }

    /** The case input: the epic's Jira text and its one pager / HLD pages. Never the code or later designs. */
    private String caseText(String epic) {
        StringBuilder sb = new StringBuilder("# Case " + epic + "\n\nInput for the scoping eval: which components must change for this feature?\n\n");
        try {
            sb.append(w.issues.text(epic, 0)).append("\n\n");
        } catch (RuntimeException e) {
            sb.append("(Jira text unavailable: ").append(e.getMessage()).append(")\n\n");
        }
        for (Docs.Doc d : w.docs.find(epic)) {
            if (!d.type().equals("onePager") && !d.type().equals("hld")) continue; // later documents would give the answer away
            Wiki.Page p = w.wiki.page(d.id());
            sb.append("## ").append(p.title()).append("\n\n").append(Markdown.toMarkdown(p.storage())).append("\n\n");
        }
        return sb.toString();
    }

    // ---------------- score ----------------

    /** One case's scores over the runs that answered it. */
    public record CaseScore(String key, Set<String> truth, List<Set<String>> answers, double precision, double recall, boolean anyExact, boolean allExact, boolean consistent) {}

    public record Report(List<CaseScore> cases, List<String> runs, double precision, double recall, double passAtK, double passHatK, double consistency) {}

    public int score(List<String> runs) throws IOException {
        Report r = report(runs);
        String text = render(r);
        Store.writeText(Ctx.work("hidden", "eval", "report.md"), text);
        w.console.out(text);
        w.console.out("Saved: " + Ctx.rel(Ctx.work("hidden", "eval", "report.md")));
        return 0;
    }

    public static Report report(List<String> wanted) throws IOException {
        Path truthDir = truthDir();
        if (!Files.isDirectory(truthDir)) throw new UsageException("no ground truth in " + Ctx.rel(truthDir) + " (java .agent/Sdlc.java eval collect <EPIC> ...)");
        List<String> runs = new ArrayList<>(wanted);
        if (runs.isEmpty() && Files.isDirectory(answers())) {
            try (Stream<Path> s = Files.list(answers())) {
                s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().forEach(runs::add);
            }
        }
        if (runs.isEmpty()) throw new UsageException("no answers in " + Ctx.rel(answers()) + "/<run>/<KEY>.yml");
        List<CaseScore> cases = new ArrayList<>();
        try (Stream<Path> s = Files.list(truthDir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                String key = f.getFileName().toString().replaceFirst("\\.yml$", "");
                Set<String> truth = components(Files.readString(f));
                List<Set<String>> answers = new ArrayList<>();
                for (String run : runs) {
                    Path a = answer(run, key);
                    answers.add(a == null ? Set.of() : components(Files.readString(a)));
                }
                cases.add(score(key, truth, answers));
            }
        }
        double p = cases.stream().mapToDouble(CaseScore::precision).average().orElse(0);
        double rc = cases.stream().mapToDouble(CaseScore::recall).average().orElse(0);
        double any = cases.stream().filter(CaseScore::anyExact).count() / (double) Math.max(1, cases.size());
        double all = cases.stream().filter(CaseScore::allExact).count() / (double) Math.max(1, cases.size());
        double cons = cases.stream().filter(CaseScore::consistent).count() / (double) Math.max(1, cases.size());
        return new Report(cases, runs, p, rc, any, all, cons);
    }

    private static Path answer(String run, String key) {
        for (String ext : List.of(".yml", ".yaml", ".md")) {
            Path p = answers().resolve(run).resolve(key + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /** Precision and recall averaged over the runs; exact = the run named exactly the true set. */
    public static CaseScore score(String key, Set<String> truth, List<Set<String>> answers) {
        double p = 0, r = 0;
        boolean any = false, all = !answers.isEmpty();
        for (Set<String> a : answers) {
            Set<String> hit = new LinkedHashSet<>(a);
            hit.retainAll(truth);
            p += a.isEmpty() ? (truth.isEmpty() ? 1 : 0) : hit.size() / (double) a.size();
            r += truth.isEmpty() ? 1 : hit.size() / (double) truth.size();
            boolean exact = a.equals(truth);
            any |= exact;
            all &= exact;
        }
        int n = Math.max(1, answers.size());
        boolean consistent = answers.stream().distinct().count() <= 1;
        return new CaseScore(key, truth, answers, p / n, r / n, any, all, consistent);
    }

    private static final Pattern SOLUTION_BLOCK = Pattern.compile("(?s)```ya?ml\\s*\\n(#\\s*sdlc-solution.*?)```");

    /**
     * Component names from an answer or truth file: a YAML file with components: [a, b] (names or
     * mappings with name:), or a Markdown solution draft with its # sdlc-solution block.
     */
    public static Set<String> components(String text) {
        Matcher m = SOLUTION_BLOCK.matcher(text);
        String yaml = m.find() ? m.group(1) : text;
        Set<String> out = new TreeSet<>();
        Object root;
        try {
            root = Yaml.parse(yaml);
        } catch (RuntimeException e) {
            return out;
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("components") instanceof List<?> list)) return out;
        for (Object o : list) {
            String name = o instanceof Map<?, ?> c ? Work.str(c.get("name")) : Work.str(o);
            if (!name.isBlank()) out.add(name.strip().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    static String render(Report r) {
        StringBuilder sb = new StringBuilder("# Scoping eval\n\nRuns: " + r.runs() + " (k = " + r.runs().size() + "), cases: " + r.cases().size() + "\n\n");
        sb.append(String.format(Locale.ROOT, "| Measure | Value |%n| --- | --- |%n| Precision (mean) | %.2f |%n| Recall (mean) | %.2f |%n"
                + "| pass@k (some run exactly right) | %.2f |%n| pass^k (every run exactly right) | %.2f |%n| Consistent (all runs agree) | %.2f |%n%n",
                r.precision(), r.recall(), r.passAtK(), r.passHatK(), r.consistency()));
        sb.append("| Case | Truth | Missed (any run) | Extra (any run) | P | R | Every run right |\n| --- | --- | --- | --- | --- | --- | --- |\n");
        for (CaseScore c : r.cases()) {
            Set<String> missed = new TreeSet<>(), extra = new TreeSet<>();
            for (Set<String> a : c.answers()) {
                for (String t : c.truth()) if (!a.contains(t)) missed.add(t);
                for (String x : a) if (!c.truth().contains(x)) extra.add(x);
            }
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %.2f | %.2f | %s |%n", c.key(), String.join(", ", c.truth()),
                    String.join(", ", missed), String.join(", ", extra), c.precision(), c.recall(), c.allExact() ? "yes" : "no"));
        }
        sb.append("\nRecall matters most: a missed component is found late, in another team's sprint. Read the agents' answers for the misses before changing prompts.\n");
        return sb.toString();
    }
}
