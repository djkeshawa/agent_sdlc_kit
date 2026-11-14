package sdlc.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.util.Proc;

/** The runners this version has: Maven Surefire and Vitest. */
public final class Runners {
    private Runners() {}

    /** For the self test: where Maven would be started for a module. */
    public static String reactorRootOf(java.nio.file.Path checkout, String module) {
        return Maven.reactorRoot(checkout, module);
    }

    static List<Runner> all() {
        return List.of(new Maven(), new Vitest());
    }

    /** The nearest folder at or above the file's folder (inside the checkout) that the test accepts, relative, "" for the root; null when none. */
    static String nearest(Path checkout, String path, java.util.function.Predicate<Path> accepts) {
        Path dir = checkout.resolve(path).getParent();
        while (dir != null && dir.startsWith(checkout)) {
            if (accepts.test(dir)) {
                String rel = checkout.relativize(dir).toString();
                return Proc.WINDOWS ? rel.replace('\\', '/') : rel; // elsewhere a backslash is part of a name, not a separator
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String tail(String text, int lines) {
        String[] all = text.strip().split("\\R");
        return String.join("\n", List.of(all).subList(Math.max(0, all.length - lines), all.length));
    }

    /** Maven's own first complaint: the first [ERROR] line that says something. */
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

    /** Names that go into a build tool's arguments: letters, digits and the few signs paths and class names need. Nothing a selector or a shell reads as syntax. */
    private static final Pattern PLAIN = Pattern.compile("[\\p{L}\\p{N}_$.][\\p{L}\\p{N}_$./-]*");

    /**
     * Only a failed assertion counts as a failure. Vitest's junit reporter writes every thrown error
     * as a failure element, and a report can name any type there; anything that is not an
     * assertion error is "threw something else".
     */
    private static JUnitXml.Case assertionOrError(JUnitXml.Case c) {
        if (c.status() != JUnitXml.Status.FAILED) return c;
        String type = c.type().toLowerCase(java.util.Locale.ROOT);
        if (type.contains("assert") || type.contains("comparisonfailure") || type.contains("multiplefailures")) return c;
        return new JUnitXml.Case(c.suite(), c.name(), JUnitXml.Status.ERRORED, c.type(), c.message());
    }

    /** True when the folder is a real folder inside the checkout: not a link, and not reached through one. */
    private static boolean inside(Path checkout, Path dir) {
        try {
            return Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS) && dir.toRealPath().startsWith(checkout.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------- Maven Surefire ----------------

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
                // Maven runs where the module's build starts: the topmost pom that lists it, which need not be the repository root
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
                Proc p = Proc.runWithEnv(checkout.resolve(root), Map.of(), null, timeoutSeconds, cmd.toArray(new String[0]));
                List<JUnitXml.Case> cases = new ArrayList<>();
                String unreadable = null;
                if (p.code() == 124) {
                    // a run that was cut off proves nothing, whatever reports it left
                    for (String f : m.getValue()) out.put(f, FileRun.failed("timed out after " + timeoutSeconds + " s"));
                    continue;
                }
                if (Files.exists(reports) && !inside(checkout, reports)) unreadable = "the report folder is a link out of the checkout";
                else if (Files.isDirectory(reports)) {
                    try (Stream<Path> s = Files.list(reports)) {
                        for (Path r : s.filter(x -> x.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) cases.addAll(JUnitXml.read(r));
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
                    else if (text.contains("COMPILATION ERROR") || text.contains("Compilation failure")) out.put(f, FileRun.failed("does not compile"));
                    else if (p.code() == 127) out.put(f, FileRun.failed("Maven could not be started (" + tail(text, 1) + ")"));
                    else out.put(f, FileRun.failed("did not run (Maven exit " + p.code() + ": " + firstError(text) + ")"));
                }
            }
            return out;
        }

        /**
         * The folder Maven is started in for a module: the module itself, or the topmost folder
         * above it whose pom.xml lists it (through any chain of <module> entries). "" is the
         * repository root.
         */
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
                        if (Pattern.compile("<module>\\s*(\\./)?" + Pattern.quote(rel) + "/?\\s*</module>").matcher(Files.readString(pom)).find()) listedBy = above;
                    } catch (IOException e) {
                        // an unreadable pom lists nothing
                    }
                }
                if (listedBy == null) break;
                dir = listedBy;
            }
            return dir;
        }

        /** Removes the reports of an earlier run. False when that cannot be done safely: the folder is a link, or a file stays. */
        private static boolean clear(Path checkout, Path reports) {
            if (!Files.exists(reports, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true;
            if (!inside(checkout, reports)) return false;
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

        /** The Maven wrapper nearest to the build's root (there, or in a folder above it), else mvn; null when neither starts. */
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

    // ---------------- Vitest ----------------

    static final class Vitest implements Runner {
        private static final Pattern TEST_FILE = Pattern.compile(".*\\.(test|spec)\\.[cm]?[jt]sx?");
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

        /** The nearest folder whose package.json names vitest. */
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
                Path report;
                try {
                    report = Files.createTempFile("sdlc-vitest", ".xml");
                } catch (IOException ex) {
                    for (String f : e.getValue()) out.put(f, FileRun.failed("no temporary file: " + ex.getMessage()));
                    continue;
                }
                try {
                    List<String> cmd = new ArrayList<>(List.of("node", entry.toString(), "run", "--reporter=junit", "--outputFile=" + report));
                    List<String> rel = e.getValue().stream().map(f -> e.getKey().isEmpty() ? f : f.substring(e.getKey().length() + 1)).toList();
                    if (rel.stream().anyMatch(r -> r.startsWith("-"))) {
                        // vitest would read such a name as an option
                        for (String f : e.getValue()) out.put(f, FileRun.failed("was not run: a test file's name starts with a dash"));
                        continue;
                    }
                    cmd.addAll(rel);
                    Proc p = Proc.runWithEnv(project, Map.of("CI", "1"), null, timeoutSeconds, cmd.toArray(new String[0]));
                    List<JUnitXml.Case> cases = new ArrayList<>();
                    String unreadable = null;
                    if (p.code() == 124) {
                        for (String f : e.getValue()) out.put(f, FileRun.failed("timed out after " + timeoutSeconds + " s"));
                        continue;
                    }
                    try {
                        if (Files.size(report) > 0) cases.addAll(JUnitXml.read(report));
                    } catch (IOException ex) {
                        unreadable = ex.getMessage();
                    }
                    for (int i = 0; i < rel.size(); i++) {
                        String name = rel.get(i);
                        List<JUnitXml.Case> mine = cases.stream().filter(c -> c.suite().replace('\\', '/').equals(name)).map(Runners::assertionOrError).toList();
                        String f = e.getValue().get(i);
                        if (!mine.isEmpty()) out.put(f, new FileRun(mine, null));
                        else if (unreadable != null) out.put(f, FileRun.failed("its report cannot be read (" + unreadable + ")"));
                        else if (p.code() == 124) out.put(f, FileRun.failed("timed out after " + timeoutSeconds + " s"));
                        else if (p.code() == 127) out.put(f, FileRun.failed("node could not be started (is Node.js on the PATH?)"));
                        else out.put(f, FileRun.failed("did not run (vitest exit " + p.code() + ": " + tail(p.out() + "\n" + p.err(), 3).replaceAll("\\s+", " ") + ")"));
                    }
                } finally {
                    try {
                        Files.deleteIfExists(report);
                    } catch (IOException ignored) {
                        // a stray temp file
                    }
                }
            }
            return out;
        }

        /**
         * A scratch checkout has no node_modules (they are ignored files). Each node_modules folder
         * the repository has at or above the project is linked into the checkout; returns vitest's
         * entry script, or null when the repository has none installed.
         */
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
