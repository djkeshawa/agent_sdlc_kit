package sdlc.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.util.Http;
import sdlc.util.Json;
import sdlc.util.Markdown;

/**
 * Hold-out scenarios: black-box tests QE keeps in its own repository and runs in its own Jenkins
 * job (holdout.jenkinsJob) against the PR build. Agents never see their code. This command reads
 * only the test names and their pass/fail status from that job (the error details, which could
 * show the inputs, are never requested) and reports per scenario: id, title, PASS / FAIL.
 */
public final class Holdout {
    private static final Pattern TS = Pattern.compile("TS-[0-9]+");
    private static final Pattern TS_HEADING = Pattern.compile("(?m)^#{2,4}\\s*(TS-[0-9]+)\\s+(.+?)\\s*$");
    private static final Pattern TS_BLOCK = Pattern.compile("(?ms)^#{2,4}\\s*(TS-[0-9]+)\\b.*?$(.*?)(?=^#{2,4}\\s|\\z)");

    private final Workflow w;

    Holdout(Workflow w) {
        this.w = w;
    }

    /** One scenario's result: PASS when every test naming it passed. */
    public record Result(String id, String title, String status) {}

    public int status(String story, String build) {
        if (story.isBlank()) story = Work.currentStory();
        if (story.isBlank()) throw new UsageException("usage: sdlc holdout status <STORY> [--build N]");
        String job = Ctx.cfg.str("holdout.jenkinsJob").replaceAll("/+$", "");
        if (job.isBlank()) throw new UsageException("holdout.jenkinsJob is not set in .agent/sdlc.yml (QE's job that runs the hold-out tests)");
        if (!Ci.trustedJenkins(job)) throw new UsageException("holdout.jenkinsJob must be on ci.jenkins.url, so the Jenkins token goes nowhere else");
        String auth = Http.basic(Ctx.secret("JENKINS_USER", ""), Ctx.secret("JENKINS_TOKEN", ""));
        String b = build.isBlank() ? "lastCompletedBuild" : build.replaceAll("[^0-9]", "");
        Object report = Http.json("GET", job + "/" + b + "/testReport/api/json?tree=suites[cases[className,name,status]]", auth, null); // never errorDetails
        Map<String, String> status = new TreeMap<>();
        int unnamed = 0;
        for (Object s : Json.list(Json.at(report, "suites"))) {
            for (Object c : Json.list(Json.at(s, "cases"))) {
                Matcher m = TS.matcher(Json.str(c, "className") + " " + Json.str(c, "name"));
                if (!m.find()) {
                    unnamed++;
                    continue;
                }
                String st = Json.str(c, "status");
                boolean ok = st.equals("PASSED") || st.equals("FIXED");
                boolean skipped = st.equals("SKIPPED");
                status.merge(m.group(), skipped ? "SKIPPED" : ok ? "PASS" : "FAIL", (x, y) -> x.equals("FAIL") || y.equals("FAIL") ? "FAIL" : x.equals("SKIPPED") || y.equals("SKIPPED") ? "SKIPPED" : "PASS");
            }
        }
        Map<String, String> titles = new LinkedHashMap<>();
        List<String> marked = new ArrayList<>();
        scenarios(story, titles, marked);
        List<Result> results = new ArrayList<>();
        for (Map.Entry<String, String> e : status.entrySet()) results.add(new Result(e.getKey(), titles.getOrDefault(e.getKey(), ""), e.getValue()));
        List<String> missing = marked.stream().filter(id -> !status.containsKey(id)).toList();
        long pass = results.stream().filter(r -> r.status().equals("PASS")).count();
        StringBuilder sb = new StringBuilder("# Hold-out scenarios for " + story + "\n\nQE's job " + job + " build " + b
                + ". Only ids, titles and results are shown here: the hold-out tests and their inputs stay with QE.\n\n| Scenario | Title | Result |\n| --- | --- | --- |\n");
        for (Result r : results) sb.append("| ").append(r.id()).append(" | ").append(r.title()).append(" | ").append(r.status()).append(" |\n");
        for (String id : missing) sb.append("| ").append(id).append(" | ").append(titles.getOrDefault(id, "")).append(" | NOT RUN |\n");
        sb.append("\nHold-out: ").append(pass).append(" of ").append(results.size() + missing.size()).append(" scenario(s) pass");
        sb.append(Verify.current() ? ". Visible tests: the build passed for the current files (verify)." : ". Visible tests: not verified for the current files.");
        if (pass < results.size() + missing.size()) sb.append("\nA gap between visible and hold-out results means the code meets the tests it can see but not the requirement: review the failing scenarios' requirements, not the hidden tests.");
        if (unnamed > 0) sb.append("\n(").append(unnamed).append(" test(s) in the job name no TS-.. id and are not counted.)");
        sb.append('\n');
        Map<String, Object> state = Work.state(story);
        List<Object> rounds = Work.list(state, "holdout");
        rounds.add(Store.map("build", b, "pass", pass, "total", results.size() + missing.size(), "at", java.time.Instant.now().toString()));
        Work.save(story, state);
        Store.writeText(Work.storyDir(story).resolve("rounds").resolve("holdout-" + rounds.size() + ".md"), sb.toString());
        w.console.out(sb.toString());
        return pass == results.size() + missing.size() && !results.isEmpty() ? 0 : 1;
    }

    /** Scenario titles of the story's test design (its own or its epic's), and the ids marked "Hold-out: yes". */
    private void scenarios(String story, Map<String, String> titles, List<String> marked) {
        try {
            Docs.Doc td = w.docs.own(story, "testDesign");
            if (td == null) return;
            String md = Markdown.toMarkdown(w.wiki.page(td.id()).storage());
            Matcher h = TS_HEADING.matcher(md);
            while (h.find()) titles.putIfAbsent(h.group(1), h.group(2));
            Matcher b = TS_BLOCK.matcher(md);
            while (b.find()) if (b.group(2).toLowerCase().matches("(?s).*hold-?out\\W+(yes|true).*")) marked.add(b.group(1));
        } catch (RuntimeException e) {
            w.console.err("[sdlc] could not read the test design for scenario titles: " + e.getMessage());
        }
    }
}
