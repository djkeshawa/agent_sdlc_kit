package sdlc.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.Ctx;
import sdlc.core.Git;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.util.Json;

/** `review record` and `qa record`: file each round's report, count rounds, log mistakes. */
public final class Loops {
    private static final Pattern VERDICT = Pattern.compile("(?m)^\\s*VERDICT:\\s*([A-Z_]+)");
    private static final Pattern COMMIT = Pattern.compile("(?m)^\\s*REVIEWED_COMMIT:\\s*([0-9a-fA-F]{7,40})");
    private static final Pattern FINDING = Pattern.compile("(?m)^\\s*-\\s*\\[(BLOCKING|ADVISORY)\\]\\s*(.+)$");
    private static final Pattern CITES = Pattern.compile("(?i)cites\\s+([A-Za-z]+-?[0-9]*|[a-z][a-z -]+?)(?:\\s+-|\\s*$|,)");
    private static final Pattern LOCATION = Pattern.compile("([\\w./\\\\-]+\\.[A-Za-z]+(?::\\d+)?)");
    private static final Pattern CASE = Pattern.compile("(?m)^\\s*-\\s*(TS-[0-9]+)\\s+(PASS|FAIL|SKIPPED)\\b\\s*-?\\s*(.*)$");

    private Loops() {}

    public static int recordReview(String task, Path report) throws IOException {
        String story = needStory();
        Map<String, Object> state = Work.state(story);
        if (!Work.tasks(state).containsKey(task)) throw new UsageException(task + " is not a task of " + story + " (java .agent/Sdlc.java status)");
        Map<String, Object> t = Work.taskState(state, task);
        List<Object> reviews = Work.list(t, "reviews");
        int max = Ctx.cfg.num("loops.review.max", 5);
        boolean lastApproved = !reviews.isEmpty() && "APPROVE".equals(Json.obj(reviews.get(reviews.size() - 1)).get("verdict"));
        if (reviews.size() >= max && !lastApproved) {
            System.err.println("REFUSED: " + task + " already used all " + max + " review rounds. Hand over to the human (java .agent/Sdlc.java retro " + story + ").");
            return 1;
        }
        String text = Files.readString(report);
        String verdict = match(VERDICT, text);
        String commit = match(COMMIT, text);
        if (!verdict.equals("APPROVE") && !verdict.equals("CHANGES_REQUESTED")) return refuse("the report has no 'VERDICT: APPROVE' or 'VERDICT: CHANGES_REQUESTED' line");
        if (commit.isEmpty() || !Git.head().startsWith(commit.toLowerCase(Locale.ROOT))) return refuse("REVIEWED_COMMIT (" + commit + ") is not the current HEAD (" + Git.shortHead() + "). Review the current commit.");
        List<String> dirty = Git.dirtyFiles().stream().filter(f -> !f.startsWith(".sdlc/")).toList();
        if (!dirty.isEmpty()) return refuse("the working tree has uncommitted changes " + dirty + ". A review covers committed code only (and the reviewer never edits code).");
        List<String[]> blocking = new ArrayList<>();
        Matcher f = FINDING.matcher(text);
        while (f.find()) if (f.group(1).equals("BLOCKING")) blocking.add(new String[] {f.group(2).trim()});
        if (verdict.equals("APPROVE") && !blocking.isEmpty()) return refuse("APPROVE with " + blocking.size() + " BLOCKING finding(s) is contradictory");
        String sha = Verify.sha256(text);
        if (!reviews.isEmpty() && sha.equals(Json.obj(reviews.get(reviews.size() - 1)).get("sha256"))) return refuse("the report is identical to round " + reviews.size() + "'s. Each round is a fresh review by the reviewer.");
        int round = reviews.size() + 1;
        Path filed = file(story, task + "-review-" + round + ".md", text);
        reviews.add(Store.map("round", round, "commit", Git.shortHead(), "verdict", verdict, "blocking", blocking.size(), "file", Ctx.rel(filed),
                "sha256", sha, "at", Instant.now().toString()));
        for (String[] b : blocking) Mistakes.found(story, task, "review", round, cites(b[0]), location(b[0]), b[0]);
        int fixed = 0;
        if (verdict.equals("APPROVE")) {
            t.put("status", "approved");
            fixed = Mistakes.fixAll(story, task, List.of("review", "qa", "ci", "sonar"));
        } else {
            t.put("status", "started");
        }
        Work.save(story, state);
        int min = Ctx.cfg.num("loops.review.min", 2);
        System.out.println("Recorded review round " + round + " of " + task + ": " + verdict + " (" + blocking.size() + " blocking finding(s)" + (fixed > 0 ? ", " + fixed + " earlier mistake(s) marked fixed" : "") + ").");
        if (verdict.equals("APPROVE") && round < min) System.out.println("Minimum is " + min + " rounds: run a confirmation round (implementer re-checks, then review again).");
        else if (!verdict.equals("APPROVE")) System.out.println(round >= max ? "That was the last allowed round: hand over to the human." : "Send the blocking findings to the implementer. Rounds left: " + (max - round) + ".");
        return 0;
    }

    public static int recordQa(String story, Path report) throws IOException {
        if (story.isBlank()) story = needStory();
        Map<String, Object> state = Work.state(story);
        List<Object> qa = Work.list(state, "qa");
        int max = Ctx.cfg.num("loops.qa.max", 3);
        long failed = qa.stream().filter(r -> "FAIL".equals(Json.obj(r).get("verdict"))).count();
        if (failed >= max) {
            System.err.println("REFUSED: the QA loop used all " + max + " failed rounds. Hand over to the human.");
            return 1;
        }
        String text = Files.readString(report);
        String verdict = match(VERDICT, text);
        if (!verdict.equals("PASS") && !verdict.equals("FAIL")) return refuse("the report has no 'VERDICT: PASS' or 'VERDICT: FAIL' line");
        if (!Git.dirtyFiles().stream().filter(x -> !x.startsWith(".sdlc/")).toList().isEmpty()) return refuse("commit the QA tests first; QA is recorded for a commit");
        int round = qa.size() + 1;
        Matcher c = CASE.matcher(text);
        int fails = 0, passes = 0, skipped = 0;
        while (c.find()) {
            switch (c.group(2)) {
                case "FAIL" -> {
                    fails++;
                    Mistakes.found(story, "", "qa", round, c.group(1), "", c.group(1) + " " + c.group(3).trim());
                }
                case "PASS" -> passes++;
                default -> skipped++;
            }
        }
        if (verdict.equals("PASS") && fails > 0) return refuse("VERDICT: PASS with failing cases is contradictory");
        Path filed = file(story, "qa-" + round + ".md", text);
        qa.add(Store.map("round", round, "commit", Git.shortHead(), "verdict", verdict, "pass", passes, "fail", fails, "skipped", skipped, "file", Ctx.rel(filed), "at", Instant.now().toString()));
        if (verdict.equals("PASS")) Mistakes.fixAll(story, null, List.of("qa"));
        Work.save(story, state);
        System.out.println("Recorded QA round " + round + ": " + verdict + " (" + passes + " pass, " + fails + " fail, " + skipped + " skipped).");
        return 0;
    }

    private static Path file(String story, String name, String text) {
        Path p = Work.storyDir(story).resolve("rounds").resolve(name);
        Store.writeText(p, text);
        return p;
    }

    private static int refuse(String why) {
        System.err.println("NOT RECORDED: " + why);
        return 1;
    }

    static String needStory() {
        String s = Work.currentStory();
        if (s.isEmpty()) throw new UsageException("no story in progress (java .agent/Sdlc.java task start <TASK>)");
        return s;
    }

    private static String match(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    public static String cites(String finding) {
        Matcher m = CITES.matcher(finding);
        return m.find() ? m.group(1).trim() : "";
    }

    public static String location(String finding) {
        Matcher m = LOCATION.matcher(finding);
        return m.find() ? m.group(1) : "";
    }
}
