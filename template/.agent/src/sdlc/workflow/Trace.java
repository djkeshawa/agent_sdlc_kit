package sdlc.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.Ctx;
import sdlc.core.UsageException;
import sdlc.core.Work;

/**
 * Test scenarios (TS-..) of the story's tasks must each link to a real test in
 * docs/test-links/<STORY>.md (committed, so reviewers see it):
 *   | TS-01 | src/test/java/.../RefundServiceTest.java#refusesUnpaidOrder |
 *   | TS-04 | src/orders/refund.test.ts#refuses an unpaid order |
 *   | TS-09 | manual: covered by the perf environment run |
 */
public final class Trace {
    private static final Pattern ROW = Pattern.compile("^\\|\\s*(TS-[0-9]+)\\s*\\|\\s*([^|]+?)\\s*\\|.*$");

    private Trace() {}

    static Path linksFile(String story) {
        return Ctx.root.resolve(Ctx.cfg.str("trace.dir", "docs/test-links") + "/" + story + ".md");
    }

    static Set<String> required(String story) {
        Set<String> out = new LinkedHashSet<>();
        for (Object t : Work.tasks(Work.state(story)).values()) {
            if (t instanceof Map<?, ?> m) out.addAll(Work.strings(m.get("scenarios")));
        }
        return out;
    }

    public static List<String> problems(String story) {
        Set<String> needed = required(story);
        List<String> out = new ArrayList<>();
        if (needed.isEmpty()) return out;
        Path links = linksFile(story);
        if (!Files.exists(links)) return List.of("no link file " + Ctx.rel(links) + " (one table row per test scenario)");
        Map<String, String> rows = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(links)) {
                Matcher r = ROW.matcher(line.trim());
                if (r.matches()) rows.put(r.group(1), r.group(2).replace("`", "").trim());
            }
            for (String ts : needed) {
                String target = rows.get(ts);
                if (target == null || target.isBlank()) {
                    out.add(ts + ": no row");
                    continue;
                }
                String lower = target.toLowerCase(Locale.ROOT);
                if (lower.startsWith("manual:") || lower.startsWith("other-repo:") || lower.startsWith("n/a:")) continue;
                String file = target.contains("#") ? target.substring(0, target.indexOf('#')).trim() : target;
                String test = target.contains("#") ? target.substring(target.indexOf('#') + 1).trim() : "";
                Path f = Ctx.root.resolve(file);
                if (!Files.isRegularFile(f)) {
                    out.add(ts + ": test file " + file + " does not exist");
                    continue;
                }
                String body = Files.readString(f);
                boolean found = test.isEmpty() || Pattern.compile("\\b" + Pattern.quote(test) + "\\s*\\(").matcher(body).find() || body.contains("'" + test + "'")
                        || body.contains("\"" + test + "\"") || body.contains("`" + test + "`");
                if (!found) out.add(ts + ": test '" + test + "' not found in " + file);
            }
        } catch (IOException e) {
            out.add("cannot read " + Ctx.rel(links) + ": " + e.getMessage());
        }
        return out;
    }

    public static int run(String story) {
        if (story.isBlank()) story = Work.currentStory();
        if (story.isBlank()) throw new UsageException("usage: sdlc trace <STORY>");
        List<String> p = problems(story);
        p.forEach(x -> System.out.println("MISSING " + x));
        Set<String> req = required(story);
        System.out.println(p.isEmpty() ? "TRACE OK: " + req.size() + " test scenario(s) of " + story + " link to tests." : "TRACE FAILED (" + p.size() + ")");
        return p.isEmpty() ? 0 : 1;
    }
}
