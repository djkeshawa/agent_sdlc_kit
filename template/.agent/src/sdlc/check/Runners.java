package sdlc.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.util.Proc;

public final class Runners {
    private Runners() {}

    public static String reactorRootOf(java.nio.file.Path checkout, String module) {
        return Maven.reactorRoot(checkout, module);
    }

    public static String mavenRun(Path checkout, String file) {
        return said(new Maven().run(checkout, List.of(file), 120).get(file));
    }

    public static String vitestReport(Path report, Path retries, String file) throws IOException {
        return said(Vitest.result(JUnitXml.read(report), file, null, 1, false, "", Vitest.Retries.read(retries)));
    }

    public static String vitestReportedName(String name) {
        return Vitest.Retries.asReported(name, new JUnitXml.Attributes());
    }

    public static String vitestReporter() {
        return Vitest.REPORTER;
    }

    private static String said(Runner.FileRun run) {
        if (run == null) return "no result";
        if (run.problem() != null) return "no result: " + run.problem();
        if (run.cases().isEmpty()) return "ran, no tests";
        return String.join("; ", run.cases().stream().map(c -> c.status() + " " + c.name() + (c.retried() ? " (run again)" : "")).toList())
                + (run.retriesUnknown() == null ? "" : " [retries not known: " + run.retriesUnknown() + "]");
    }

    static List<Runner> all() {
        return List.of(new Maven(), new Vitest());
    }

    static String nearest(Path checkout, String path, java.util.function.Predicate<Path> accepts) {
        Path dir = checkout.resolve(path).getParent();
        while (dir != null && dir.startsWith(checkout)) {
            if (accepts.test(dir)) {
                String rel = checkout.relativize(dir).toString();
                return Proc.WINDOWS ? rel.replace('\\', '/') : rel;
            }
            dir = dir.getParent();
        }
        return null;
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
        }
    }

    private static String tail(String text, int lines) {
        String[] all = text.strip().split("\\R");
        return String.join("\n", List.of(all).subList(Math.max(0, all.length - lines), all.length));
    }

    private static String firstError(String text) {
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (!l.startsWith("[ERROR]")) continue;
            l = l.substring("[ERROR]".length()).strip();
            if (l.isEmpty()) continue;
            return l.length() > 260 ? l.substring(0, 260) + "..." : l;
        }
        return tail(text, 2).replaceAll("\\s+", " ");
    }

    private static final Pattern PLAIN = Pattern.compile("[\\p{L}\\p{N}_$.][\\p{L}\\p{N}_$./-]*");

    // Vitest's junit reporter writes every thrown error as a failure element: only an assertion error is a failure.
    private static JUnitXml.Case assertionOrError(JUnitXml.Case c) {
        if (c.status() != JUnitXml.Status.FAILED) return c;
        String type = c.type().toLowerCase(java.util.Locale.ROOT);
        if (type.contains("assert") || type.contains("comparisonfailure") || type.contains("multiplefailures")) return c;
        return new JUnitXml.Case(c.suite(), c.name(), JUnitXml.Status.ERRORED, c.type(), c.message(), c.retried());
    }

    private static boolean inside(Path checkout, Path dir) {
        try {
            Path real = checkout.toRealPath();
            Path at = dir;
            while (at != null && !Files.exists(at, java.nio.file.LinkOption.NOFOLLOW_LINKS)) at = at.getParent();
            if (at == null || !Files.isDirectory(at, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
            return at.toRealPath().startsWith(real);
        } catch (IOException e) {
            return false;
        }
    }

    static final class Maven implements Runner {
        private static final Pattern TEST_CLASS = Pattern.compile("(Test.*|.*Test|.*Tests|.*TestCase)\\.java");
        private static final String ROOT = "src/test/java/";

        @Override
        public String name() {
            return "Maven Surefire";
        }

        @Override
        public boolean runs(Path checkout, String path) {
            int at = path.indexOf(ROOT);
            if (at < 0 || !TEST_CLASS.matcher(path.substring(path.lastIndexOf('/') + 1)).matches()) return false;
            return nearest(checkout, path, d -> Files.isRegularFile(d.resolve("pom.xml"))) != null;
        }

        @Override
        public boolean support(String path) {
            return path.contains("src/test/");
        }

        private static final Pattern DOES_NOT_COMPILE = Pattern.compile("COMPILATION ERROR|Compilation failure");

        @Override
        public void forgetBuilt(Path checkout, List<String> files) {
            for (String f : files) {
                String module = nearest(checkout, f, d -> Files.isRegularFile(d.resolve("pom.xml")));
                if (module == null) continue;
                for (String built : List.of("target/test-classes", "target/maven-status")) {
                    Path dir = checkout.resolve(module).resolve(built);
                    if (inside(checkout, dir)) deleteTree(dir);
                }
            }
        }

        private static String className(String path) {
            String rel = path.substring(path.indexOf(ROOT) + ROOT.length());
            return rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
        }

        @Override
        public Map<String, FileRun> run(Path checkout, List<String> files, int timeoutSeconds) {
            Map<String, FileRun> out = new LinkedHashMap<>();
            Map<String, List<String>> byModule = new LinkedHashMap<>();
            for (String f : files) {
                String module = nearest(checkout, f, d -> Files.isRegularFile(d.resolve("pom.xml")));
                if (module == null) out.put(f, FileRun.failed("no pom.xml above it"));
                else byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(f);
            }
            for (Map.Entry<String, List<String>> m : byModule.entrySet()) {
                Path reports = checkout.resolve(m.getKey()).resolve("target/surefire-reports");
                String refused = !m.getKey().isEmpty() && !PLAIN.matcher(m.getKey()).matches() ? "its module folder has characters that cannot be passed to Maven safely"
                        : m.getValue().stream().anyMatch(f -> !PLAIN.matcher(className(f)).matches()) ? "a test file's name has characters that cannot be passed to Maven safely"
                        : !clear(checkout, reports) ? "old test reports could not be removed, so a new result could not be told from an old one" : null;
                if (refused != null) {
                    for (String f : m.getValue()) out.put(f, FileRun.failed("was not run: " + refused));
                    continue;
                }
                String root = reactorRoot(checkout, m.getKey());
                String within = root.isEmpty() ? m.getKey() : m.getKey().equals(root) ? "" : m.getKey().substring(root.length() + 1);
                List<String> launcher = launcher(checkout, root);
                if (launcher == null) {
                    for (String f : m.getValue()) out.put(f, FileRun.failed("Maven is not available (no mvnw in the repository, no mvn on the PATH)"));
                    continue;
                }
                List<String> cmd = new ArrayList<>(launcher);
                cmd.addAll(List.of("-B", "-q", "-ntp", "-Dtest=" + String.join(",", m.getValue().stream().map(Maven::className).toList()),
                        "-Dsurefire.failIfNoSpecifiedTests=false", "-DfailIfNoTests=false"));
                if (!within.isEmpty()) cmd.addAll(List.of("-pl", within, "-am"));
                cmd.add("test");
                java.util.concurrent.atomic.AtomicBoolean doesNotCompile = new java.util.concurrent.atomic.AtomicBoolean();
                Proc.Lines seen = (line, endsLine) -> {
                    if (DOES_NOT_COMPILE.matcher(line).find()) doesNotCompile.set(true);
                };
                Proc p = Proc.runBuild(checkout.resolve(root), Map.of(), timeoutSeconds, seen, seen, cmd.toArray(new String[0]));
                List<JUnitXml.Case> cases = new ArrayList<>();
                String unreadable = null;
                if (p.code() == 124) {
                    for (String f : m.getValue()) out.put(f, FileRun.failed("timed out after " + timeoutSeconds + " s"));
                    continue;
                }
                if (Files.exists(reports) && !inside(checkout, reports)) unreadable = "the report folder is a link out of the checkout";
                else if (Files.isDirectory(reports)) {
                    try (Stream<Path> s = Files.list(reports)) {
                        for (Path r : s.filter(x -> sdlc.util.Fs.name(x).matches("TEST-.*\\.xml") && Files.isRegularFile(x, java.nio.file.LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                            cases.addAll(JUnitXml.read(r));
                        }
                    } catch (IOException e) {
                        unreadable = e.getMessage();
                    }
                }
                String text = p.out() + "\n" + p.err();
                for (String f : m.getValue()) {
                    String cls = className(f);
                    List<JUnitXml.Case> mine = cases.stream().filter(c -> c.suite().equals(cls) || c.suite().startsWith(cls + "$")).map(Runners::assertionOrError).toList();
                    if (!mine.isEmpty()) out.put(f, new FileRun(mine, null));
                    else if (unreadable != null) out.put(f, FileRun.failed("its report cannot be read (" + unreadable + ")"));
                    else if (p.code() == 124) out.put(f, FileRun.failed("timed out after " + timeoutSeconds + " s"));
                    else if (doesNotCompile.get()) out.put(f, FileRun.broken(Witness.DOES_NOT_COMPILE));
                    else if (p.code() == 127) out.put(f, FileRun.failed("Maven could not be started (" + tail(text, 1) + ")"));
                    else if (p.code() == 0) out.put(f, new FileRun(List.of(), null));
                    else out.put(f, FileRun.failed("did not run (Maven exit " + p.code() + ": " + firstError(text) + ")"));
                }
            }
            return out;
        }

        static String reactorRoot(Path checkout, String module) {
            String dir = module;
            while (!dir.isEmpty()) {
                String listedBy = null;
                String above = dir;
                while (!above.isEmpty() && listedBy == null) {
                    above = above.contains("/") ? above.substring(0, above.lastIndexOf('/')) : "";
                    Path pom = checkout.resolve(above).resolve("pom.xml");
                    if (!Files.isRegularFile(pom)) continue;
                    String rel = above.isEmpty() ? dir : dir.substring(above.length() + 1);
                    try {
                        if (Pattern.compile("<module>\\s*(\\./)?" + Pattern.quote(rel) + "/?\\s*</module>").matcher(Integrity.withoutComments(Files.readString(pom))).find()) listedBy = above;
                    } catch (IOException e) {
                    }
                }
                if (listedBy == null) break;
                dir = listedBy;
            }
            return dir;
        }

        private static boolean clear(Path checkout, Path reports) {
            if (!inside(checkout, reports)) return false;
            if (!Files.exists(reports, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true;
            try (Stream<Path> s = Files.list(reports)) {
                for (Path r : s.toList()) {
                    if (Files.isDirectory(r, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                    Files.delete(r); // a link is removed, never followed
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private static List<String> launcher(Path checkout, String root) {
            String name = Proc.WINDOWS ? "mvnw.cmd" : "mvnw";
            for (Path dir = checkout.resolve(root); dir != null && dir.startsWith(checkout); dir = dir.getParent()) {
                Path wrapper = dir.resolve(name);
                if (Files.isRegularFile(wrapper)) return Proc.WINDOWS ? List.of("cmd", "/c", wrapper.toString()) : List.of("sh", wrapper.toString());
            }
            List<String> mvn = Proc.WINDOWS ? List.of("cmd", "/c", "mvn") : List.of("mvn");
            List<String> probe = new ArrayList<>(mvn);
            probe.add("-v");
            return Proc.runWithEnv(checkout, Map.of(), null, 60, probe.toArray(new String[0])).ok() ? mvn : null;
        }
    }

    static final class Vitest implements Runner {
        private static final Pattern TEST_FILE = Pattern.compile(".*\\.(test|spec)\\.[cm]?[jt]sx?");
        private static final Pattern DID_NOT_LOAD = Pattern.compile("SyntaxError|Failed to load|Failed to resolve import|Cannot find module|Transform failed|Failed to parse source");
        private static final class LoadFailures implements Proc.Lines {
            private final List<String> files;
            private final Set<String> failed;
            private final java.util.ArrayDeque<Set<String>> last = new java.util.ArrayDeque<>();
            private boolean lineOpen;

            LoadFailures(List<String> files, Set<String> failed) {
                this.files = files;
                this.failed = failed;
            }

            @Override
            public synchronized void accept(String text, boolean endsLine) {
                if (!lineOpen) {
                    last.addLast(new java.util.LinkedHashSet<>());
                    if (last.size() > 6) last.removeFirst();
                }
                lineOpen = !endsLine;
                for (String f : files) if (text.contains(f)) last.getLast().add(f);
                if (DID_NOT_LOAD.matcher(text).find()) for (Set<String> named : last) failed.addAll(named);
            }
        }

        // Vitest writes a file or group without tests as a failed case, exactly as it writes a test's own failure.
        static FileRun result(List<JUnitXml.Case> cases, String name, String unreadable, int exit, boolean didNotLoad, String said, Retries retries) {
            List<JUnitXml.Case> mine = cases.stream().filter(c -> slashes(c.suite()).equals(name)).map(Runners::assertionOrError).toList();
            if (!mine.isEmpty()) return retries.apply(mine, name);
            if (unreadable != null) return FileRun.failed("its report cannot be read (" + unreadable + ")");
            if (exit == 127) return FileRun.failed("node could not be started (is Node.js on the PATH?)");
            if (didNotLoad) return FileRun.broken("did not load (vitest exit " + exit + ": " + said + ")");
            return FileRun.failed("gave no result (vitest exit " + exit + ": " + said + ")");
        }

        private static String slashes(String path) {
            return Proc.WINDOWS ? path.replace('\\', '/') : path;
        }

        private static final String RETRIES_TO = "SDLC_VITEST_RETRIES";

        // Vitest's JUnit report writes a test that passed on a retry as an ordinary pass; only result.retryCount tells.
        // The reporter API is onFinished in vitest 3 and earlier, onTestRunEnd later.
        static final String REPORTER = """
                // sdlc check: for each test vitest ran, its file, its name as the JUnit reporter writes it, its state and its retries
                import { writeFileSync } from 'node:fs';
                import { relative } from 'node:path';
                export default class {
                  onInit(ctx) { this.ctx = ctx; }
                  onFinished(files) { this.write(files); }
                  onTestRunEnd(modules) { this.write((modules || []).map((m) => (m && m.task) || m)); }
                  write(files) {
                    const to = process.env.SDLC_VITEST_RETRIES;
                    if (!to) return;
                    const root = this.ctx && this.ctx.config ? this.ctx.config.root : undefined;
                    const tests = [];
                    const count = (n) => (typeof n === 'number' ? n : null);
                    const walk = (t, base, file) => {
                      if (!t) return;
                      const name = base ? base + ' > ' + t.name : String(t.name);
                      if (t.type === 'suite') { for (const c of t.tasks || []) walk(c, name, file); return; }
                      const r = t.result || {};
                      tests.push({ file, name, state: typeof r.state === 'string' ? r.state : null, retryCount: count(r.retryCount), retry: count(t.retry) });
                    };
                    for (const f of files || []) {
                      if (!f) continue;
                      const file = root && f.filepath ? relative(root, f.filepath) : String(f.name);
                      for (const t of f.tasks || []) walk(t, '', file);
                    }
                    writeFileSync(to, JSON.stringify({ tests }));
                  }
                }
                """;

        private static final String NOT_WRITTEN = "vitest did not write the check's account of its tests (its reporter did not load or did not finish)";

        private static final long MAX_RETRIES_BYTES = 50L * 1024 * 1024;

        record Retries(Map<String, Map<String, List<Ran>>> files, String why) {
            record Ran(String state, Long retryCount, Long retry) {}

            static Retries unknown(String why) {
                return new Retries(Map.of(), why);
            }

            static Retries read(Path json) {
                try {
                    if (!Files.isRegularFile(json, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return unknown(NOT_WRITTEN);
                    if (Files.size(json) > MAX_RETRIES_BYTES) return unknown("vitest's account of its tests is larger than " + (MAX_RETRIES_BYTES >> 20) + " MB");
                    Object root = sdlc.util.Json.parseStrict(Files.readString(json));
                    if (!(root instanceof Map<?, ?> top) || !(top.get("tests") instanceof List<?> tests)) throw new IllegalArgumentException("no list of tests");
                    Map<String, Map<String, List<Ran>>> files = new LinkedHashMap<>();
                    JUnitXml.Attributes xml = new JUnitXml.Attributes();
                    for (Object t : tests) {
                        if (!(t instanceof Map<?, ?> m) || !(m.get("file") instanceof String file) || !(m.get("name") instanceof String name)) throw new IllegalArgumentException("a test without its file or name");
                        Object state = m.get("state");
                        if (state != null && !(state instanceof String)) throw new IllegalArgumentException("a state that is not a word");
                        if (name.length() > MAX_NAME) return unknown("vitest's account of its tests names a test longer than " + MAX_NAME + " characters");
                        String reported = asReported(name, xml);
                        if (reported == null) return unknown("vitest's account of its tests names a test that its JUnit report cannot write as XML");
                        files.computeIfAbsent(slashes(file), k -> new LinkedHashMap<>()).computeIfAbsent(reported, k -> new ArrayList<>())
                                .add(new Ran((String) state, count(m.get("retryCount")), count(m.get("retry"))));
                    }
                    return new Retries(files, null);
                } catch (IOException | RuntimeException e) {
                    return unknown("vitest's account of its tests cannot be read (" + e.getMessage() + ")");
                }
            }

            static final int MAX_NAME = 1_000_000;

            // vitest 3 escapeXML: &, ", ', < and > as references, then XML-forbidden characters removed by UTF-16 code unit (a reference may
            // lose its ';'); Node writes a lone surrogate as U+FFFD; an XML parser then normalises the attribute value.
            static String asReported(String name, JUnitXml.Attributes xml) {
                StringBuilder escaped = new StringBuilder(name.length() + 16);
                for (int i = 0; i < name.length(); i++) {
                    char c = name.charAt(i);
                    switch (c) {
                        case '&' -> escaped.append("&amp;");
                        case '"' -> escaped.append("&quot;");
                        case '\'' -> escaped.append("&apos;");
                        case '<' -> escaped.append("&lt;");
                        case '>' -> escaped.append("&gt;");
                        default -> escaped.append(c);
                    }
                }
                String written = wellFormed(discouragedOut(invalidOut(escaped)));
                return xml.read(written);
            }

            private static boolean high(char c) {
                return c >= 0xD800 && c <= 0xDBFF;
            }

            private static boolean low(char c) {
                return c >= 0xDC00 && c <= 0xDFFF;
            }

            private static String invalidOut(CharSequence s) {
                StringBuilder out = new StringBuilder(s.length());
                int n = s.length();
                for (int i = 0; i < n; ) {
                    char c = s.charAt(i);
                    boolean lowNext = i + 1 < n && low(s.charAt(i + 1));
                    if (c <= 0x08 || c == 0x0B || c == 0x0C || (c >= 0x0E && c <= 0x1F) || c >= 0xFFFD) i++;
                    else if (high(c) && !lowNext) i++;
                    else if (!high(c) && lowNext) i += 2;
                    else if (i == 0 && low(c)) i++;
                    else {
                        out.append(c);
                        i++;
                    }
                }
                return out.toString();
            }

            private static String discouragedOut(String s) {
                StringBuilder out = new StringBuilder(s.length());
                int n = s.length();
                for (int i = 0; i < n; ) {
                    char c = s.charAt(i);
                    if ((c >= 0x7F && c <= 0x84) || (c >= 0x86 && c <= 0x9F) || (c >= 0xFDD0 && c <= 0xFDEF)) {
                        i++;
                        continue;
                    }
                    if (high(c) && (c & 0x3F) == 0x3F && i + 1 < n && (s.charAt(i + 1) == 0xDFFE || s.charAt(i + 1) == 0xDFFF)) {
                        if (c != 0xDBFF) {
                            i += 2;
                            continue;
                        }
                        int after = plane16Tail(s, i + 2);
                        if (after >= 0) {
                            i += 2 + after;
                            continue;
                        }
                    }
                    out.append(c);
                    i++;
                }
                return out.toString();
            }

            private static int plane16Tail(String s, int j) {
                if (j >= s.length()) return -1;
                char d = s.charAt(j);
                boolean lowNext = j + 1 < s.length() && low(s.charAt(j + 1));
                if (d != '\n' && d != '\r' && d != 0x2028 && d != 0x2029 && !high(d) && !low(d)) return 1;
                if (high(d)) return lowNext ? 2 : 1;
                return lowNext ? 2 : -1;
            }

            private static String wellFormed(String s) {
                StringBuilder out = null;
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    boolean pair = high(c) && i + 1 < s.length() && low(s.charAt(i + 1));
                    if (pair) {
                        if (out != null) out.append(c).append(s.charAt(i + 1));
                        i++;
                        continue;
                    }
                    boolean lone = high(c) || low(c);
                    if (lone && out == null) out = new StringBuilder(s.length()).append(s, 0, i);
                    if (out != null) out.append(lone ? '\uFFFD' : c);
                }
                return out == null ? s : out.toString();
            }

            private static Long count(Object o) {
                if (o == null) return null;
                if (!(o instanceof Double d) || d < 0 || d > 1e9 || d != Math.floor(d)) throw new IllegalArgumentException("a count that is not a whole number");
                return d.longValue();
            }

            FileRun apply(List<JUnitXml.Case> mine, String file) {
                String unknown = null;
                Map<String, List<Ran>> ran = why == null ? files.getOrDefault(file, Map.of()) : null;
                Map<String, Integer> listed = new java.util.HashMap<>();
                for (JUnitXml.Case c : mine) listed.merge(c.name(), 1, Integer::sum);
                List<JUnitXml.Case> out = new ArrayList<>();
                for (JUnitXml.Case c : mine) {
                    if (c.status() == JUnitXml.Status.SKIPPED) {
                        out.add(c);
                        continue;
                    }
                    if (ran == null) {
                        if (c.status() == JUnitXml.Status.PASSED && unknown == null) unknown = why;
                        out.add(c);
                        continue;
                    }
                    List<Ran> same = ran.getOrDefault(c.name(), List.of());
                    boolean again = same.stream().anyMatch(r -> r.retryCount() != null && r.retryCount() > 0);
                    if (c.status() != JUnitXml.Status.PASSED) {
                        out.add(again ? with(c, c.status(), true) : c);
                        continue;
                    }
                    if (same.isEmpty()) {
                        if (unknown == null) unknown = "vitest's account of its tests does not name a test its JUnit report lists as passed (" + c.name() + ")";
                        out.add(c);
                    } else if (same.size() > listed.get(c.name())) {
                        if (unknown == null) unknown = "vitest's account of its tests names more tests " + c.name() + " than its JUnit report lists";
                        out.add(c);
                    } else if (same.stream().noneMatch(r -> "pass".equals(r.state()))) {
                        if (same.stream().allMatch(r -> r.state() == null || r.state().equals("skip") || r.state().equals("todo"))) out.add(with(c, JUnitXml.Status.SKIPPED, again));
                        else {
                            if (unknown == null) unknown = "vitest's account of its tests and its JUnit report disagree about " + c.name();
                            out.add(c);
                        }
                    } else {
                        if (unknown == null && same.stream().anyMatch(r -> "pass".equals(r.state()) && r.retryCount() == null && r.retry() != null && r.retry() > 0)) {
                            unknown = "this version of vitest does not say whether a test passed only when it ran it again";
                        }
                        out.add(again ? with(c, c.status(), true) : c);
                    }
                }
                return new FileRun(out, null, false, unknown);
            }

            private static JUnitXml.Case with(JUnitXml.Case c, JUnitXml.Status status, boolean retried) {
                return new JUnitXml.Case(c.suite(), c.name(), status, status == JUnitXml.Status.SKIPPED ? "" : c.type(), status == JUnitXml.Status.SKIPPED ? "" : c.message(), retried);
            }
        }

        private static final Pattern SUPPORT_DIR = Pattern.compile("(^|.*/)(__tests__|__mocks__|__fixtures__|__snapshots__|fixtures|tests?)/.*");

        @Override
        public String name() {
            return "Vitest";
        }

        @Override
        public boolean runs(Path checkout, String path) {
            return TEST_FILE.matcher(path).matches() && project(checkout, path) != null;
        }

        @Override
        public boolean support(String path) {
            return TEST_FILE.matcher(path).matches() || SUPPORT_DIR.matcher(path).matches();
        }

        private static final Pattern PACKAGES = Pattern.compile("(^|.*/)(package\\.json|package-lock\\.json|npm-shrinkwrap\\.json|yarn\\.lock|pnpm-lock\\.yaml|pnpm-workspace\\.yaml|bun\\.lockb?|\\.npmrc|\\.yarnrc(\\.yml)?|\\.pnpmfile\\.cjs)$");

        private static final List<String> INSTALLS = List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies", "bundledDependencies",
                "bundleDependencies", "overrides", "resolutions", "workspaces", "pnpm", "packageManager");

        @Override
        public String sharedBySides(Change change, Path checkout, String file) {
            String project = project(checkout, file);
            if (project == null) return null;
            List<String> packages = new ArrayList<>();
            for (Change.File f : change.files) {
                String path = f.path();
                if (!PACKAGES.matcher(path).matches()) continue;
                String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
                boolean above = dir.isEmpty() || project.equals(dir) || project.startsWith(dir + "/");
                boolean manifest = path.equals("package.json") || path.endsWith("/package.json");
                if (above ? !manifest || installsDiffer(change, path) : manifest && namedBy(checkout, project, change, path) && installsDiffer(change, path)) packages.add(path);
            }
            if (packages.isEmpty()) return null;
            return "the change touches what is installed (" + packages.get(0) + (packages.size() > 1 ? " and " + (packages.size() - 1) + " more" : "")
                    + ") and both sides ran with the packages installed now, so the side before the change was not without all of it";
        }

        private static boolean installsDiffer(Change change, String path) {
            try {
                return !installs(change.before(path)).equals(installs(change.after(path)));
            } catch (RuntimeException e) {
                return true;
            }
        }

        private static Map<String, Object> installs(String text) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (text == null) return out;
            Map<String, Object> json = sdlc.util.Json.map(sdlc.util.Json.parse(text));
            for (String key : INSTALLS) if (json.containsKey(key)) out.put(key, json.get(key));
            return out;
        }

        private static boolean namedBy(Path checkout, String project, Change change, String path) {
            try {
                String text = change.after(path) != null ? change.after(path) : change.before(path);
                String name = text == null ? "" : sdlc.util.Json.str(sdlc.util.Json.parse(text), "name");
                if (name == null || name.isBlank()) return false;
                Map<String, Object> mine = sdlc.util.Json.map(sdlc.util.Json.parse(Files.readString(checkout.resolve(project).resolve("package.json"))));
                for (String list : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
                    if (mine.get(list) instanceof Map<?, ?> packages && packages.containsKey(name)) return true;
                }
                for (String list : List.of("bundledDependencies", "bundleDependencies")) {
                    if (mine.get(list) instanceof List<?> packages && packages.contains(name)) return true;
                }
                return false;
            } catch (IOException | RuntimeException e) {
                return true;
            }
        }

        private static String project(Path checkout, String path) {
            return nearest(checkout, path, d -> {
                try {
                    Path pkg = d.resolve("package.json");
                    return Files.isRegularFile(pkg) && Files.readString(pkg).contains("\"vitest\"");
                } catch (IOException e) {
                    return false;
                }
            });
        }

        @Override
        public Map<String, FileRun> run(Path checkout, List<String> files, int timeoutSeconds) {
            Map<String, FileRun> out = new LinkedHashMap<>();
            Map<String, List<String>> byProject = new LinkedHashMap<>();
            for (String f : files) {
                String project = project(checkout, f);
                if (project == null) out.put(f, FileRun.failed("no package.json with vitest above it"));
                else byProject.computeIfAbsent(project, k -> new ArrayList<>()).add(f);
            }
            for (Map.Entry<String, List<String>> e : byProject.entrySet()) {
                Path project = checkout.resolve(e.getKey());
                Path entry = dependencies(checkout, e.getKey());
                if (entry == null) {
                    for (String f : e.getValue()) out.put(f, FileRun.failed("vitest is not installed in the repository (run npm install there first)"));
                    continue;
                }
                Path tmp, report, retries;
                try {
                    tmp = Files.createTempDirectory("sdlc-vitest");
                    report = tmp.resolve("report.xml");
                    retries = tmp.resolve("retries.json");
                    Files.writeString(tmp.resolve("retries.mjs"), REPORTER);
                } catch (IOException ex) {
                    for (String f : e.getValue()) out.put(f, FileRun.failed("no temporary file: " + ex.getMessage()));
                    continue;
                }
                try {
                    List<String> cmd = new ArrayList<>(List.of("node", entry.toString(), "run", "--reporter=junit", "--reporter=" + tmp.resolve("retries.mjs"),
                            "--outputFile=" + report));
                    List<String> rel = e.getValue().stream().map(f -> e.getKey().isEmpty() ? f : f.substring(e.getKey().length() + 1)).toList();
                    if (rel.stream().anyMatch(r -> r.startsWith("-"))) {
                        for (String f : e.getValue()) out.put(f, FileRun.failed("was not run: a test file's name starts with a dash"));
                        continue;
                    }
                    cmd.addAll(rel);
                    Set<String> didNotLoad = java.util.concurrent.ConcurrentHashMap.newKeySet();
                    LoadFailures reader = new LoadFailures(rel, didNotLoad);
                    Proc p = Proc.runBuild(project, Map.of("CI", "1", RETRIES_TO, retries.toString()), timeoutSeconds, reader, reader, cmd.toArray(new String[0]));
                    List<JUnitXml.Case> cases = new ArrayList<>();
                    String unreadable = null;
                    if (p.code() == 124) {
                        for (String f : e.getValue()) out.put(f, FileRun.failed("timed out after " + timeoutSeconds + " s"));
                        continue;
                    }
                    try {
                        if (Files.exists(report) && Files.size(report) > 0) cases.addAll(JUnitXml.read(report));
                    } catch (IOException ex) {
                        unreadable = ex.getMessage();
                    }
                    Retries again = Retries.read(retries);
                    String said = tail(p.out() + "\n" + p.err(), 3).replaceAll("\\s+", " ");
                    for (int i = 0; i < rel.size(); i++) {
                        out.put(e.getValue().get(i), result(cases, rel.get(i), unreadable, p.code(), didNotLoad.contains(rel.get(i)), said, again));
                    }
                } finally {
                    deleteTree(tmp);
                }
            }
            return out;
        }

        private static Path dependencies(Path checkout, String project) {
            Path entry = null;
            String dir = project;
            while (true) {
                Path real = Ctx.root.resolve(dir).resolve("node_modules");
                Path link = checkout.resolve(dir).resolve("node_modules");
                if (Files.isDirectory(real)) {
                    try {
                        if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, real);
                    } catch (IOException | UnsupportedOperationException e) {
                        return null;
                    }
                    Path candidate = real.resolve("vitest/vitest.mjs");
                    if (entry == null && Files.isRegularFile(candidate)) entry = link.resolve("vitest/vitest.mjs");
                }
                if (dir.isEmpty()) break;
                dir = dir.contains("/") ? dir.substring(0, dir.lastIndexOf('/')) : "";
            }
            return entry;
        }
    }
}
