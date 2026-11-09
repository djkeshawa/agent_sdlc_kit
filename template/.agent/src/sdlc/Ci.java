package sdlc;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the existing CI of a pull request without changing it: the PR's checks from GitHub (gh),
 * the failed Jenkins build's stage, failed tests and error lines (Jenkins REST API), and the
 * SonarQube quality gate and new issues of the PR (SonarQube Web API). Writes a digest for the
 * ci-fixer agent, counts CI rounds and logs each failure in the mistakes log.
 */
final class Ci {
    private static final Pattern ERROR_LINE = Pattern.compile("(?i)(\\[ERROR\\]|BUILD FAILURE|FAILED|Tests run:.*(Failures: [1-9]|Errors: [1-9])|^ERROR:|npm ERR!|error TS\\d+|Error:|✕|● |FAIL |Exception|COMPILATION ERROR|Quality Gate|exit code [1-9])");
    private static final Pattern STAGE = Pattern.compile("\\[Pipeline\\] \\{ \\((.+?)\\)");

    private Ci() {}

    record Check(String name, String bucket, String link, String description) {}

    static List<Check> checks(String pr) {
        Proc p = Proc.run(Ctx.root, "gh", "pr", "checks", pr, "--json", "name,state,bucket,link,description");
        if (p.out().isBlank()) {
            if (p.err().contains("no checks")) return List.of();
            throw new Main.UsageException("gh pr checks failed: " + p.err().trim() + " (is gh installed and logged in? gh 2.40+ needed for --json)");
        }
        List<Check> out = new ArrayList<>();
        for (Object o : Json.list(Json.parse(p.out()))) out.add(new Check(Json.str(o, "name"), Json.str(o, "bucket"), Json.str(o, "link"), Json.str(o, "description")));
        return out;
    }

    static String prNumber(Map<String, String> opt) {
        if (opt.containsKey("pr")) return opt.get("pr");
        Map<String, Object> pr = Sync.pr();
        if (pr == null) throw new Main.UsageException("no pull request for branch " + Git.branch() + " (pass --pr <number>)");
        String head = String.valueOf(pr.get("headRefOid"));
        if (!head.isBlank() && !head.equals("null") && !Git.head().equals(head)) {
            System.err.println("[sdlc] WARNING the PR head (" + head.substring(0, 7) + ") is not your local HEAD (" + Git.shortHead() + "): push first, or the checks are for an older commit.");
        }
        return Json.str(pr, "number");
    }

    /** sdlc ci wait: polls until no check is pending, then digests. Exit 0 green, 1 failed, 2 still pending. */
    static int waitFor(Map<String, String> opt) throws InterruptedException {
        String pr = prNumber(opt);
        long deadline = System.currentTimeMillis() + Ctx.cfg.num("ci.waitMinutes", 60) * 60_000L;
        int poll = Math.max(10, Ctx.cfg.num("ci.pollSeconds", 30));
        System.out.println("Waiting for the checks of PR #" + pr + " (up to " + Ctx.cfg.num("ci.waitMinutes", 60) + " min) ...");
        int empty = 0;
        while (true) {
            List<Check> cs = checks(pr);
            long pending = cs.stream().filter(c -> c.bucket().equals("pending")).count();
            if (cs.isEmpty()) empty++;
            if (!cs.isEmpty() && pending == 0) return digest(pr, cs, true);
            if (cs.isEmpty() && empty > 10) {
                System.err.println("No checks reported on PR #" + pr + " after " + empty * poll + " s. Is Jenkins building pull requests?");
                return 2;
            }
            if (System.currentTimeMillis() > deadline) {
                System.err.println("Still " + pending + " check(s) pending after the wait limit (ci.waitMinutes). Run again later: java .agent/Sdlc.java ci wait");
                return 2;
            }
            Thread.sleep(poll * 1000L);
        }
    }

    static int failures(Map<String, String> opt) {
        String pr = prNumber(opt);
        return digest(pr, checks(pr), false);
    }

    static int status(Map<String, String> opt) {
        String pr = prNumber(opt);
        for (Check c : checks(pr)) System.out.printf("%-8s %-40s %s%n", c.bucket(), c.name(), c.link());
        return 0;
    }

    private static int digest(String pr, List<Check> cs, boolean record) {
        List<Check> failed = cs.stream().filter(c -> c.bucket().equals("fail") || c.bucket().equals("cancel")).toList();
        StringBuilder sb = new StringBuilder("# CI result for PR #" + pr + " at " + Git.shortHead() + "\n\n");
        for (Check c : cs) sb.append("- ").append(c.bucket().toUpperCase(Locale.ROOT)).append(" ").append(c.name()).append(c.link().isBlank() ? "" : " - " + c.link()).append('\n');
        List<String[]> mistakes = new ArrayList<>(); // source, category, location, summary
        for (Check c : failed) {
            sb.append("\n## Failed: ").append(c.name()).append("\n\n");
            if (!c.description().isBlank()) sb.append(c.description()).append("\n\n");
            if (isJenkins(c.link())) jenkins(c, sb, mistakes);
            else mistakes.add(new String[] {"ci", "check", c.name(), c.name() + " failed: " + c.description()});
        }
        sonar(pr, sb, mistakes);
        boolean green = failed.isEmpty() && mistakes.stream().noneMatch(m -> m[0].equals("sonar-gate"));
        sb.append("\n").append(green ? "RESULT: GREEN" : "RESULT: FAILED - fix the causes above (never the checks), get the fix reviewed, then push.").append('\n');
        String story = Work.currentStory();
        if (record && !story.isEmpty()) {
            Map<String, Object> state = Work.state(story);
            List<Object> rounds = Work.list(state, "ci");
            int round = rounds.size() + 1;
            Path file = Work.storyDir(story).resolve("inbox").resolve("ci-round-" + round + ".md");
            Store.writeText(file, sb.toString());
            rounds.add(Store.map("round", round, "commit", Git.shortHead(), "result", green ? "green" : "failed", "pr", pr, "file", Ctx.rel(file), "at", Instant.now().toString()));
            Work.save(story, state);
            for (String[] m : mistakes) Mistakes.found(story, "", m[0].startsWith("sonar") ? "sonar" : "ci", round, m[1], m[2], m[3]);
            if (green) Mistakes.fixAll(story, null, List.of("ci", "sonar"));
            sb.append("\n(Saved as ").append(Ctx.rel(file)).append("; CI round ").append(round).append(", failed rounds used: ").append(rounds.stream().filter(r -> "failed".equals(Json.obj(r).get("result"))).count())
                    .append(" of ").append(Ctx.cfg.num("loops.ci.max", 3)).append(".)\n");
            try {
                Jira.comment(story, Approvals.BOT_MARK + " CI round " + round + " for PR #" + pr + ": " + (green ? "GREEN" : "FAILED (" + failed.size() + " check(s), " + mistakes.size() + " finding(s)); the agent is fixing it."), false);
            } catch (RuntimeException e) {
                sb.append("(Could not comment on Jira: ").append(e.getMessage()).append(")\n");
            }
        }
        System.out.println(sb);
        return green ? 0 : 1;
    }

    private static boolean isJenkins(String link) {
        String j = Ctx.cfg.str("ci.jenkins.url");
        return !link.isBlank() && (!j.isBlank() && link.startsWith(j.replaceAll("/+$", "")) || link.contains("/job/"));
    }

    /** Build URL from a GitHub status link: .../job/x/job/PR-12/5/display/redirect -> .../job/x/job/PR-12/5/ */
    static String buildUrl(String link) {
        String u = link.replaceAll("/display/redirect/?$", "/").replaceAll("/console(Full|Text)?/?$", "/");
        Matcher m = Pattern.compile("^(.*/job/[^/]+/\\d+)/?").matcher(u);
        return m.find() ? m.group(1) + "/" : u.endsWith("/") ? u : u + "/";
    }

    /**
     * True only when a link points at the configured Jenkins (same scheme, host and port, under its
     * path). Check links are set by whoever reports a status on the PR, so the Jenkins token is
     * sent nowhere else.
     */
    static boolean trustedJenkins(String link) {
        String j = Ctx.cfg.str("ci.jenkins.url").replaceAll("/+$", "");
        if (j.isBlank() || link.isBlank()) return false;
        try {
            URI a = URI.create(link);
            URI b = URI.create(j);
            if (a.getScheme() == null || b.getScheme() == null || a.getHost() == null || b.getHost() == null || a.getUserInfo() != null) return false;
            String pa = a.getPath() == null ? "" : a.getPath();
            String pb = b.getPath() == null ? "" : b.getPath().replaceAll("/+$", "");
            return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b)
                    && (pb.isEmpty() || pa.equals(pb) || pa.startsWith(pb + "/"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int port(URI u) {
        if (u.getPort() > 0) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static void jenkins(Check c, StringBuilder sb, List<String[]> mistakes) {
        String user = Ctx.secret("JENKINS_USER", "");
        String token = Ctx.secret("JENKINS_TOKEN", "");
        String url = buildUrl(c.link());
        if (!trustedJenkins(url)) {
            sb.append("(Not reading this log: ").append(Http.host(url)).append(" is not ci.jenkins.url in .agent/sdlc.yml, so the Jenkins token is not sent there. Open: ").append(url).append("console)\n");
            mistakes.add(new String[] {"ci", "jenkins", c.name(), c.name() + " failed"});
            return;
        }
        if (token.isBlank()) {
            sb.append("(Set JENKINS_USER and JENKINS_TOKEN in .agent/local.env to read the log here. Open: ").append(url).append("console)\n");
            mistakes.add(new String[] {"ci", "jenkins", c.name(), c.name() + " failed"});
            return;
        }
        String auth = Http.basic(user, token);
        boolean anyTest = false;
        try {
            Object report = Http.json("GET", url + "testReport/api/json?tree=suites[cases[className,name,status,errorDetails]]", auth, null);
            for (Object s : Json.list(Json.at(report, "suites"))) {
                for (Object tc : Json.list(Json.at(s, "cases"))) {
                    String st = Json.str(tc, "status");
                    if (!st.equals("FAILED") && !st.equals("REGRESSION")) continue;
                    anyTest = true;
                    String name = Json.str(tc, "className") + "#" + Json.str(tc, "name");
                    String err = Http.abbreviate(Json.str(tc, "errorDetails").replaceAll("\\s+", " "), 300);
                    sb.append("- Failed test `").append(name).append("`: ").append(err).append('\n');
                    mistakes.add(new String[] {"ci", "test", name, "test " + name + " failed: " + err});
                }
            }
        } catch (Http.ApiException e) {
            if (e.status != 404) sb.append("(test report: ").append(e.getMessage()).append(")\n");
        }
        try {
            String log = Http.text("GET", url + "consoleText", auth, null, "text/plain");
            if (log.length() > 600_000) log = log.substring(log.length() - 600_000);
            String stage = "";
            String failingStage = "";
            List<String> errors = new ArrayList<>();
            for (String line : log.split("\n")) {
                Matcher st = STAGE.matcher(line);
                if (st.find()) stage = st.group(1);
                if (ERROR_LINE.matcher(line).find() && !line.contains("[Pipeline]")) {
                    if (failingStage.isEmpty()) failingStage = stage;
                    if (errors.size() < 60) errors.add(line.length() > 300 ? line.substring(0, 300) : line);
                }
            }
            if (!failingStage.isEmpty()) sb.append("\nFirst failing stage: **").append(failingStage).append("**\n");
            if (!errors.isEmpty()) sb.append("\n```text\n").append(String.join("\n", errors)).append("\n```\n");
            else sb.append("\nLast lines of the log:\n```text\n").append(String.join("\n", tail(log, 40))).append("\n```\n");
            if (!anyTest) mistakes.add(new String[] {"ci", failingStage.isEmpty() ? "jenkins" : "stage:" + failingStage, c.name(), c.name() + " failed" + (errors.isEmpty() ? "" : ": " + Http.abbreviate(firstError(errors).trim(), 200))});
        } catch (Http.ApiException e) {
            sb.append("(console log: ").append(e.getMessage()).append(")\n");
            if (!anyTest) mistakes.add(new String[] {"ci", "jenkins", c.name(), c.name() + " failed"});
        }
    }

    private static List<String> tail(String text, int n) {
        String[] lines = text.split("\n");
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) out.add(lines[i]);
        return out;
    }

    /** The most telling log line: an ERROR/FAILED line before INFO lines that only mention a check. */
    static String firstError(List<String> lines) {
        for (String l : lines) if (l.matches("(?i).*(\\[ERROR\\]|\\bERROR\\b|\\bFAILED\\b|BUILD FAILURE|COMPILATION ERROR).*")) return l;
        return lines.get(0);
    }

    /** A measure's value: "period.value" (SonarQube 10+), "periods[0].value" (SonarQube Cloud, older servers) or "value". */
    static String measureValue(Object m) {
        if (!Json.str(m, "period.value").isBlank()) return Json.str(m, "period.value");
        List<Object> periods = Json.list(Json.at(m, "periods"));
        if (!periods.isEmpty() && !Json.str(periods.get(0), "value").isBlank()) return Json.str(periods.get(0), "value");
        return Json.str(m, "value");
    }

    /** "com.acme:payments:src/Foo.java" -> "src/Foo.java": project keys often contain a colon. */
    static String componentPath(String component, String projectKey) {
        if (component.startsWith(projectKey + ":")) return component.substring(projectKey.length() + 1);
        int colon = component.lastIndexOf(':');
        return colon < 0 ? component : component.substring(colon + 1);
    }

    /** SonarQube quality gate, conditions, new issues and coverage gaps for the PR. */
    private static void sonar(String pr, StringBuilder sb, List<String[]> mistakes) {
        String url = Ctx.cfg.str("ci.sonar.url").replaceAll("/+$", "");
        String key = Ctx.cfg.str("ci.sonar.projectKey");
        String token = Ctx.secret("SONAR_TOKEN", "");
        if (url.isBlank() || key.isBlank()) return;
        String auth = token.isBlank() ? null : Http.basic(token, ""); // public SonarQube Cloud projects need no token
        Map<String, String> q = new LinkedHashMap<>();
        q.put("projectKey", key);
        q.put("pullRequest", pr);
        try {
            Object gate = Http.json("GET", url + "/api/qualitygates/project_status" + Http.query(q), auth, null);
            String status = Json.str(gate, "projectStatus.status");
            sb.append("\n## SonarQube quality gate: ").append(status).append("\n\n");
            for (Object c : Json.list(Json.at(gate, "projectStatus.conditions"))) {
                if (!Json.str(c, "status").equals("ERROR")) continue;
                String line = Json.str(c, "metricKey") + " = " + Json.str(c, "actualValue") + " (threshold " + Json.str(c, "comparator") + " " + Json.str(c, "errorThreshold") + ")";
                sb.append("- FAILED condition: ").append(line).append('\n');
                mistakes.add(new String[] {"sonar-gate", Json.str(c, "metricKey"), "", "quality gate condition failed: " + line});
            }
            Map<String, String> iq = new LinkedHashMap<>();
            iq.put("componentKeys", key);
            iq.put("pullRequest", pr);
            iq.put("resolved", "false");
            iq.put("ps", "100");
            Object issues = Http.json("GET", url + "/api/issues/search" + Http.query(iq), auth, null);
            List<Object> list = Json.list(Json.at(issues, "issues"));
            if (!list.isEmpty()) sb.append("\nNew issues (").append(list.size()).append("):\n");
            for (Object i : list) {
                String comp = componentPath(Json.str(i, "component"), key);
                String loc = comp + (Json.str(i, "line").isBlank() ? "" : ":" + Json.str(i, "line"));
                sb.append("- [").append(Json.str(i, "severity")).append(" ").append(Json.str(i, "type")).append(" ").append(Json.str(i, "rule")).append("] ")
                        .append(loc).append(" - ").append(Json.str(i, "message")).append('\n');
                mistakes.add(new String[] {"sonar", Json.str(i, "rule"), loc, Json.str(i, "message")});
            }
            Map<String, String> mq = new LinkedHashMap<>();
            mq.put("component", key);
            mq.put("pullRequest", pr);
            mq.put("metricKeys", "new_coverage,new_uncovered_lines,new_duplicated_lines_density");
            mq.put("strategy", "leaves");
            mq.put("s", "metricPeriod"); // new_* metrics sort by their period, or the server returns nothing
            mq.put("metricSort", "new_uncovered_lines");
            mq.put("metricPeriodSort", "1");
            mq.put("metricSortFilter", "withMeasuresOnly");
            mq.put("asc", "false");
            mq.put("ps", "15");
            Object tree = Http.json("GET", url + "/api/measures/component_tree" + Http.query(mq), auth, null);
            List<Object> comps = Json.list(Json.at(tree, "components"));
            if (!comps.isEmpty() && status.equals("ERROR")) {
                sb.append("\nFiles with uncovered new lines:\n");
                for (Object c : comps) {
                    StringBuilder ms = new StringBuilder();
                    String uncovered = "";
                    for (Object m : Json.list(Json.at(c, "measures"))) {
                        String v = measureValue(m);
                        if (Json.str(m, "metric").equals("new_uncovered_lines")) uncovered = v;
                        ms.append(Json.str(m, "metric")).append('=').append(v).append(' ');
                    }
                    if (uncovered.isBlank() || uncovered.matches("0+(\\.0+)?")) continue; // only files that need tests
                    sb.append("- ").append(Json.str(c, "path")).append(": ").append(ms.toString().trim()).append('\n');
                }
            }
        } catch (Http.ApiException e) {
            sb.append("\n(SonarQube: ").append(e.getMessage()).append(token.isBlank() ? " Private project? Set SONAR_TOKEN in .agent/local.env." : "").append(")\n");
        }
    }
}
