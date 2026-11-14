package sdlc.check;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.util.Json;
import sdlc.workflow.Checks;

/**
 * What a change does to the things that are supposed to check it: tests switched off, removed or
 * emptied, secrets, flags that make the build skip tests, and changes to the pipeline, the build,
 * dependencies, migrations, coverage thresholds and the files that steer agents and reviews.
 *
 * Read from the diff alone; nothing is run. A PROBLEM is a fact the text makes certain. Anything
 * a simple reading can only guess at is a NOTE, and says "appears".
 *
 * A test file that moves shows as a deletion and an addition. It is reported as a NOTE ("test file
 * moved") when the same text, or every test case it declared, turns up in a file the change adds
 * or edits; otherwise its deletion is a PROBLEM. A test case that leaves one file for another is
 * treated the same way ("test moved").
 */
public final class Integrity {
    private static final int MAX_DETAIL = 140;

    // ---------------- what counts as a test file (the one place that decides) ----------------

    private static final Pattern TEST_FOLDER = Pattern.compile(".*/(?:__tests__|tests?)/.*");
    private static final Pattern TEST_NAME = Pattern.compile(
            ".*/(?:[^/]*\\.(?:test|spec)\\.[^/]+|[^/]*(?:Test|Tests|IT)\\.(?:java|kt|groovy|scala)|test_[^/]*\\.py|[^/]*_test\\.(?:py|go))");

    /** A test file: under src/test, test, tests or __tests__, or named like one (FooTest.java, foo.test.ts, test_foo.py, foo_test.go). */
    static boolean isTestFile(String path) {
        String p = "/" + path;
        return TEST_FOLDER.matcher(p).matches() || TEST_NAME.matcher(p).matches();
    }

    static boolean testNamed(String path) {
        return TEST_NAME.matcher("/" + path).matches();
    }

    private static boolean isJava(String path) {
        return path.endsWith(".java");
    }

    private static boolean isScript(String path) {
        return path.matches(".*\\.[cm]?[jt]sx?");
    }

    // ---------------- kinds of files ----------------

    private static final Pattern PIPELINE = Pattern.compile(
            "\\.github/workflows/.*|(?:.*/)?Jenkinsfile[^/]*|\\.gitlab-ci\\.yml|(?:.*/)?azure-pipelines[^/]*\\.ya?ml|\\.circleci/.*|bitbucket-pipelines\\.yml|\\.buildkite/.*");
    private static final Pattern BUILD = Pattern.compile(
            "(?:.*/)?(?:pom\\.xml|build\\.gradle(?:\\.kts)?|settings\\.gradle(?:\\.kts)?|gradle\\.properties|tsconfig[^/]*\\.json|vitest\\.config\\.[^/]+|jest\\.config\\.[^/]+|Makefile|Dockerfile[^/]*)");
    private static final Pattern SCRIPT_FILE = Pattern.compile(".*\\.(?:sh|bash|ps1|cmd|bat)|(?:.*/)?\\.mvn/maven\\.config|(?:.*/)?vite\\.config\\.[^/]+");
    private static final Pattern MIGRATION = Pattern.compile(
            "(?:.*/)?(?:db/migration|migrations|alembic/versions|db/changelog)/.*|.*\\.sql|(?:.*/)?[^/]*changelog[^/]*\\.(?:xml|ya?ml|json)");
    private static final Pattern STEERING = Pattern.compile(
            "(?:.*/)?\\.gitattributes|(?:.*/)?CODEOWNERS|(?:.*/)?AGENTS\\.md|(?:.*/)?CLAUDE\\.md|\\.claude/.*|\\.codex/.*|\\.cursor/.*"
                    + "|\\.github/copilot-instructions\\.md|\\.github/instructions/.*|\\.agent/.*|\\.githooks/.*");
    private static final Map<String, List<String>> LOCKS = Map.of(
            "package-lock.json", List.of("package.json"), "npm-shrinkwrap.json", List.of("package.json"), "yarn.lock", List.of("package.json"),
            "pnpm-lock.yaml", List.of("package.json"), "gradle.lockfile", List.of("build.gradle", "build.gradle.kts"), "poetry.lock", List.of("pyproject.toml"),
            "Pipfile.lock", List.of("Pipfile"), "Cargo.lock", List.of("Cargo.toml"), "go.sum", List.of("go.mod"), "Gemfile.lock", List.of("Gemfile"));

    private static boolean isPackageJson(String path) {
        return path.equals("package.json") || path.endsWith("/package.json");
    }

    private static boolean buildOrScript(String path) {
        return BUILD.matcher(path).matches() || PIPELINE.matcher(path).matches() || SCRIPT_FILE.matcher(path).matches() || isPackageJson(path);
    }

    // ---------------- tests switched off, assertions ----------------

    // Switches as a line shows them. White space may stand around each dot and after the @ of an annotation, and other
    // members may stand between (it.concurrent.skip, test.skip.each).
    private static final Pattern SCRIPT_SWITCH = Pattern.compile(
            "\\b(?:it|test|describe|context|suite)(?:\\s*\\.\\s*\\w+)*?\\s*\\.\\s*(?:skip|todo|only|fixme)\\b|\\bx(?:it|test|describe|context)(?=\\s*\\()|\\bf(?:it|describe)(?=\\s*\\()");
    private static final Pattern OTHER_SWITCH = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*)*(?:Disabled|DisabledIf\\w*|Ignore)\\b|\\bassumeTrue\\s*\\([^()]*\\bfalse\\b[^()]*\\)"
            + "|\\bassumeFalse\\s*\\([^()]*\\btrue\\b[^()]*\\)|\\bAssumptions\\s*\\.\\s*abort\\s*\\(|@pytest\\.mark\\.skip|@unittest\\.skip|\\bt\\.Skip(?:Now)?(?=\\s*\\()");

    /**
     * One switch of a Java file: what it does ("@Disabled", "@Ignore", "assume") and the
     * declaration it stands on, by where it stands and what it is called ("CalcTest > subtracts").
     */
    public record Switch(String how, String what) {
        public String shown() {
            return how + (what.isEmpty() ? "" : " on " + (what.length() > 80 ? what.substring(0, 80) + "..." : what));
        }
    }

    /** A switch, the name of the declaration it stands on without the classes around it, and whether that declaration is a test. */
    private record Found(Switch is, String name, boolean test) {}

    /** What tells one switch of a file from another: what it does, the declaration it stands on, and whether that is a test. */
    private record Key(String how, String on, boolean test) {}

    /**
     * Every switch of a Java test file that skips a test: an annotation that switches it off and
     * an assumption that never holds, each on the declaration it belongs to (Declared.java). A
     * switch that is seen is never dropped: one on something that is no test method is kept under
     * that declaration's name. Comments and the insides of strings are not code. Other test files
     * are not read here: the lines a change adds are linted (see lintedSwitch), and the run of the
     * file says which of its tests are skipped.
     */
    private static List<Found> found(String path, String text) {
        List<Found> out = new ArrayList<>();
        if (text == null || !readWhole(path)) return out;
        for (Declared.Item item : Declared.java(path, text)) {
            for (String how : item.switches()) out.add(new Found(new Switch(how, item.where()), item.name(), item.test()));
        }
        return out;
    }

    public static List<Switch> switches(String path, String text) {
        List<Switch> out = new ArrayList<>();
        for (Found f : found(path, text)) out.add(f.is());
        return out;
    }

    private static List<Declared.Item> declarations(String path, String text) {
        return Code.isScript(path) ? Declared.script(path, text) : Declared.java(path, text);
    }

    /** True when a Java file cannot be relied on for what stands where (see Declared.unsure). */
    public static boolean unsure(String path, String text) {
        return text != null && isJava(path) && Declared.unsure(path, text);
    }

    /**
     * True when the switches of a test file are read from the whole file, before and after the
     * change (addedSwitches): Java. Other test files are linted by the lines the change adds
     * (lintedSwitch); for scripts their run is the judge.
     */
    public static boolean readWhole(String path) {
        return isJava(path);
    }

    /**
     * The line lint: true when the code of a line (its comments dropped and the insides of its
     * strings emptied) holds a switch that skips a test, marks it as to do, or focuses on one. A
     * switch written over several lines (it, a line break, .skip) is not seen, and a skipped test
     * that a change moves or gives another title is reported like any other skip on a line the
     * change wrote.
     */
    public static boolean lintedSwitch(String line) {
        String code = code(line);
        return FOCUSED.matcher(code).find() || SCRIPT_SWITCH.matcher(code).find() || OTHER_SWITCH.matcher(code).find();
    }

    /**
     * The switches a change put into a Java file: those the file has after the change and did not
     * have before, each matched with one of the same kind on the same declaration (where it stands
     * and what it is called), and as many times. The file is read whole, so a switch is judged by
     * the declaration it stands on, whatever lines the change wrote: a switch taken off one test
     * does not make up for one put on another, a switch on a line the change did not touch that
     * the change puts onto a test is new, and a line only written differently changes nothing.
     * Where either version could not be read to its end (Declared.unsure), declarations are known
     * by their names alone. A test renamed while it is switched off is a new switch on the test of
     * the new name.
     */
    public static List<Switch> addedSwitches(String path, String before, String after) {
        boolean placed = !unsure(path, before) && !unsure(path, after);
        List<Key> was = new ArrayList<>();
        for (Found f : found(path, before)) was.add(key(f, placed));
        List<Switch> added = new ArrayList<>();
        for (Found f : found(path, after)) if (!was.remove(key(f, placed))) added.add(f.is());
        return added;
    }

    private static Key key(Found f, boolean placed) {
        return new Key(f.is().how(), placed ? f.is().what() : f.name(), f.test());
    }

    /**
     * How often a Java file holds what looks like a switch inside what was read as a comment or a
     * string. Text there switches nothing off. But the reading is of tokens, not of the language,
     * and a file can be written so that it takes code for text; a change that adds such places is
     * therefore said out loud, never passed over.
     */
    public static List<String> switchesInText(String path, String text) {
        List<String> out = new ArrayList<>();
        if (text == null || !readWhole(path)) return out;
        String code = Code.mask(path, text);
        Matcher m = OTHER_SWITCH.matcher(text);
        while (m.find()) if (!code.substring(m.start(), m.end()).equals(m.group())) out.add(m.group().replaceAll("\\s+", ""));
        return out;
    }

    /**
     * A line with its built-in switches taken out. What a configured pattern still finds in it is
     * the repository's own (a helper of its own that skips, a comment directive) and is judged by
     * the line as written; the built-in ones are judged from the whole file or by the line lint.
     */
    public static String withoutBuiltInSwitches(String line) {
        return OTHER_SWITCH.matcher(SCRIPT_SWITCH.matcher(line).replaceAll(" ")).replaceAll(" ");
    }

    /** The places of that kind a file has now and did not have before. */
    public static List<String> addedSwitchesInText(String path, String before, String after) {
        List<String> was = switchesInText(path, before);
        List<String> added = new ArrayList<>();
        for (String s : switchesInText(path, after)) if (!was.remove(s)) added.add(s);
        return added;
    }

    private static final Pattern FOCUSED = Pattern.compile("\\b(it|test|describe|context|suite)(\\.\\w+)*\\.only\\b|\\bf(it|describe)\\s*\\(");
    private static final Pattern JAVA_ASSERT = Pattern.compile(
            "\\b(?:assert|verify)\\w*\\s*\\(|(?i:expect)\\w*\\s*\\(|\\.should\\w*\\s*\\(|\\bfail\\s*\\(|\\bassert\\s|\\bexpected\\s*=");
    private static final Pattern SCRIPT_ASSERT = Pattern.compile("\\bexpect(?:TypeOf)?\\s*[(.]|\\bassert\\w*\\s*[(.]|\\.should\\b|\\bshould\\s*\\(");

    // ---------------- flags that skip tests or ignore their failures ----------------

    private static final Pattern XML_SKIP = Pattern.compile("<(skipTests|skipITs|maven\\.test\\.skip)>\\s*true\\s*<");
    private static final Pattern XML_IGNORE = Pattern.compile("<(testFailureIgnore|maven\\.test\\.failure\\.ignore)>\\s*true\\s*<");
    private static final Pattern D_FLAG = Pattern.compile("-D(skipTests|skipITs|maven\\.test\\.skip|testFailureIgnore|maven\\.test\\.failure\\.ignore)\\b(?:=([^\\s\"']*))?");
    private static final Pattern GRADLE_EXCLUDE = Pattern.compile("(?:^|\\s)(?:-x|--exclude-task)\\s+:?(?:[\\w-]+:)*(?:test|check)\\b");
    private static final Pattern GRADLE_IGNORE = Pattern.compile("\\bignoreFailures\\s*=\\s*true\\b");
    private static final Pattern PASS_EMPTY = Pattern.compile("--passWithNoTests\\b(?!\\s*=\\s*false)|[\"']?passWithNoTests[\"']?\\s*:\\s*true\\b");
    private static final Pattern PLUGIN_SKIP = Pattern.compile("<skip>\\s*true\\s*</skip>");
    private static final Pattern OR_TAIL = Pattern.compile("(?:true|:|exit\\s+0)(?=\\s|$|[\"'`;&)])");
    /** A test command by its own name, and a build tool that is asked to run tests. Both without ".*": every pattern here walks a line once. */
    private static final Pattern TEST_RUNNER = Pattern.compile(
            "\\b(?:npm|yarn|pnpm)\\s+(?:run\\s+)?test\\b|\\b(?:npx\\s+)?(?:jest|vitest|mocha|pytest|karma|ava)\\b|\\bgo\\s+test\\b|\\bcypress\\s+run\\b|\\bplaywright\\s+test\\b");
    private static final Pattern BUILD_TOOL = Pattern.compile("(?:^|[\\s/])(?:mvnw?|mvnw\\.cmd|gradlew?|gradlew\\.bat)(?=\\s|$)");
    private static final Pattern TEST_GOAL = Pattern.compile("\\b(?:test|verify|check|build|install)\\b");

    /** True when the command runs tests: a test runner anywhere in it, or a build tool followed, anywhere later, by a goal that runs tests. */
    private static boolean runsTests(String command) {
        if (TEST_RUNNER.matcher(command).find()) return true;
        Matcher tool = BUILD_TOOL.matcher(command);
        return tool.find() && TEST_GOAL.matcher(command).region(tool.end(), command.length()).find();
    }

    // ---------------- coverage ----------------

    private static final Pattern MINIMUM = Pattern.compile("<minimum>\\s*([\\d.]+)\\s*%?\\s*</minimum>|\\bminimum\\s*=\\s*([\\d.]+)");
    private static final Pattern SCRIPT_THRESHOLD = Pattern.compile("[\"']?\\b(lines|branches|functions|statements)[\"']?\\s*:\\s*(\\d+(?:\\.\\d+)?)");

    private final Change change;
    /** The added lines as the change wrote them; added holds the same lines in pieces of bounded length. */
    private final Map<String, List<String>> whole;
    private final Map<String, List<String>> added;
    private final Map<String, List<String>> removed;
    private final Map<String, String> beforeText = new HashMap<>();
    private final Map<String, String> afterText = new HashMap<>();
    private final Set<Finding> out = new LinkedHashSet<>();
    /** Test files whose edit removed a test case; their assertion count is not compared. */
    private final Set<String> lostCases = new LinkedHashSet<>();
    private Map<String, List<String>> gained;

    /**
     * The scan reads what the change wrote, so its work per line and per file is bounded. Nothing is
     * dropped for that: a long line is scanned in overlapping pieces, and a file too large to read
     * is reported, as a problem when it is a test or a build file.
     */
    private static final int MAX_LINE = 2000;
    private static final int OVERLAP = 300;
    private static final int MAX_FILE = 2_000_000;

    private Integrity(Change change) {
        this.change = change;
        this.whole = change.addedLines();
        this.added = capped(whole);
        this.removed = capped(change.removedLines());
    }

    private static Map<String, List<String>> capped(Map<String, List<String>> lines) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        lines.forEach((path, list) -> {
            List<String> pieces = new ArrayList<>();
            for (String l : list) {
                if (l.length() <= MAX_LINE) {
                    pieces.add(l);
                    continue;
                }
                for (int at = 0; at < l.length(); at += MAX_LINE - OVERLAP) pieces.add(l.substring(at, Math.min(l.length(), at + MAX_LINE)));
            }
            out.put(path, pieces);
        });
        return out;
    }

    /** The findings for a change: problems first, then notes, each in the order of the detectors and the files. */
    public static List<Finding> scan(Change change) {
        Integrity s = new Integrity(change);
        s.switchedOff();
        s.secrets();
        s.deletedTestFiles();
        s.removedTestCases();
        s.skipFlags();
        s.emptyTests();
        s.fewerAssertions();
        s.byPath(PIPELINE, "pipeline changed");
        s.buildConfiguration();
        s.dependencies();
        s.lockFiles();
        s.byPath(MIGRATION, "database migration changed");
        s.coverage();
        s.byPath(STEERING, "agent or review instructions changed");
        List<Finding> all = new ArrayList<>(s.out);
        all.sort(Comparator.comparing(Finding::level));
        return all;
    }

    // ---------------- problems ----------------

    /** The built-in idea of a test file, and the repository's own (paths.test). */
    private static boolean anyTestFile(String path) {
        return isTestFile(path) || sdlc.util.Glob.any(sdlc.core.Ctx.cfg.list("paths.test"), path);
    }

    private void switchedOff() {
        // A file read whole is read before and after: a switch may be written over several lines, and one that is taken
        // off a test does not make up for one put on another. Other test files are linted by the lines the change adds.
        Set<String> read = new LinkedHashSet<>();
        for (String path : change.paths("AM")) {
            if (!anyTestFile(path)) continue;
            if (!readWhole(path)) {
                for (String line : added.getOrDefault(path, List.of())) if (lintedSwitch(line)) switchedOff(path, line.trim());
                continue;
            }
            read.add(path);
            for (Switch s : addedSwitches(path, before(path), after(path))) problem("test switched off", path, s.shown());
            if (unsure(path, after(path))) note("could not be read with confidence", path, "its brackets do not pair up for the reader, or a declaration in it could not be read to its end. "
                    + "Switches in it are still found; which tests it declares, and which are new, is not relied on");
            List<String> inText = addedSwitchesInText(path, before(path), after(path));
            if (!inText.isEmpty()) note("switch in a comment or a string", path, inText.size() + (inText.size() == 1 ? " place looks" : " places look")
                    + " like a skipped or focused test inside a comment or a string (" + inText.get(0) + "). That switches nothing off, unless the file is written so that the reading took code for text: look at it");
        }
        // the repository's own patterns may name more than the built-in reading knows
        for (String hit : Checks.disabledTests(added)) {
            String code = code(hitLine(hit));
            if (!Checks.containsDisabled(code)) continue;
            // a built-in switch in a file that was read above is judged there, with the file around it; what else the
            // repository's patterns find on the line is judged here
            if (read.contains(hitPath(hit)) && !Checks.containsDisabled(withoutBuiltInSwitches(code))) continue;
            switchedOff(hitPath(hit), hitLine(hit));
        }
    }

    /**
     * A line with what is not code taken out: comments are dropped and the inside of each string is
     * emptied, so "@Disabled" in a test's expected text or it.skip( in a comment switches nothing
     * off. Code that follows a comment on the same line stays, and so does code inside ${...} of a
     * template string. Where the line cannot be read with confidence (a quote that never closes),
     * the rest is kept as it is: a doubtful line is scanned, not skipped.
     */
    static String code(String line) {
        return code(line, 0);
    }

    private static String code(String line, int depth) {
        String t = line.stripLeading();
        if (t.startsWith("#")) return ""; // a comment of a script or a Python file
        if (t.startsWith("*")) {
            // the inside of a block comment; what follows its end on the same line is code again
            int end = t.indexOf("*/");
            if (end < 0) return "";
            t = t.substring(end + 2);
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '/') break;
            if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '*') {
                int end = t.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 2;
                out.append(' ');
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                int end = closing(t, i);
                if (end < 0) return out.append(t, i, t.length()).toString();
                out.append(c);
                if (c == '`') {
                    // what a template string computes is code; it is read the same way, strings inside it included
                    int at = t.indexOf("${", i);
                    while (at >= 0 && at < end) {
                        int close = matching(t, at + 1, end);
                        if (close < 0) {
                            out.append(' ').append(t, at + 2, end).append(' '); // braces that do not pair up: kept as written
                            break;
                        }
                        String inner = t.substring(at + 2, close);
                        out.append(' ').append(depth < 4 ? code(inner, depth + 1) : inner).append(' ');
                        at = t.indexOf("${", close);
                    }
                }
                out.append(c);
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** The index of the brace that closes the one at open, before limit, counting braces in between; -1 when there is none. */
    private static int matching(String t, int open, int limit) {
        int depth = 0;
        for (int i = open; i < limit; i++) {
            char c = t.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int end = closing(t, i); // a brace inside a string is text
                if (end < 0 || end >= limit) return -1;
                i = end;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the quote that closes the string starting at from, or -1. */
    private static int closing(String t, int from) {
        char quote = t.charAt(from);
        for (int i = from + 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\') i++;
            else if (c == quote) return i;
        }
        return -1;
    }

    private void switchedOff(String path, String line) {
        if (FOCUSED.matcher(line).find()) problem("test focused", path, "only this test runs, the others in the file are skipped: " + line);
        else problem("test switched off", path, line);
    }

    private static final java.util.regex.Pattern GENERATED = java.util.regex.Pattern.compile(
            "(.*/)?(mvnw|mvnw\\.cmd|gradlew|gradlew\\.bat|package-lock\\.json|npm-shrinkwrap\\.json|yarn\\.lock|pnpm-lock\\.yaml)|(.*/)?(\\.mvn|gradle)/wrapper/.*");

    private static final sdlc.core.Config EVERY_LANGUAGE = sdlc.core.Config.parse(
            "stack: java-maven\ntests:\n  codeExtensions: [java, kt, groovy, scala, js, jsx, ts, tsx, mjs, cjs, vue, svelte, py, go, rb, cs, php, rs]\n"
                    + "paths:\n  test: [\"**/src/test/**\", \"**/*.test.*\", \"**/*.spec.*\", \"**/__tests__/**\", \"**/test/**\", \"**/tests/**\"]\n");

    private void secrets() {
        List<String> hits;
        sdlc.core.Config own = sdlc.core.Ctx.cfg;
        try {
            // without the kit's configuration the scanner would know one stack's file types only
            // (in a pull request build only the base branch's configuration counts: the checkout's own file is the change's to add)
            boolean configured = sdlc.core.Trusted.inCi()
                    ? sdlc.core.Git.refExists(sdlc.core.Trusted.baseRef()) && sdlc.core.Trusted.config(sdlc.core.Trusted.baseRef()) != null
                    : java.nio.file.Files.isRegularFile(sdlc.core.Ctx.root.resolve(".agent/sdlc.yml"));
            if (!configured) sdlc.core.Ctx.cfg = EVERY_LANGUAGE;
            hits = Checks.secrets(added);
        } finally {
            sdlc.core.Ctx.cfg = own;
        }
        for (String hit : hits) {
            // build wrappers and lock files are generated; their look-alikes (a credentials variable in mvnw.cmd) are a note, not a failure
            if (GENERATED.matcher(hitPath(hit)).matches()) note("possible secret in a generated file", hitPath(hit), hitLine(hit));
            else problem("possible secret", hitPath(hit), hitLine(hit));
        }
    }

    /** One declared test: where it stands, what it is called, and its text without the title. */
    private record Case(String where, String title, String body) {}

    /** The tests a file declares. For scripts and Java, read from the declarations; a title computed while the file runs is named by its expression. */
    private static List<Case> cases(String path, String text) {
        List<Case> out = new ArrayList<>();
        if (text == null) return out;
        if (!(Code.isScript(path) || isJava(path))) {
            for (String name : TestNames.declared(path, text)) out.add(new Case(name, name, null));
            return out;
        }
        for (Declared.Item item : declarations(path, text)) {
            if (!item.test()) continue;
            String body = null;
            if (!item.unsure() && item.end() <= text.length() && item.body() <= item.end()) {
                if (isJava(path)) {
                    int annotation = text.lastIndexOf("@Test", item.start());
                    String head = annotation >= 0 && item.start() - annotation < 600 ? text.substring(annotation, item.start()) : ""; // JUnit 4: @Test(expected = ...)
                    body = head + text.substring(item.body(), Math.min(text.length(), item.end() + 1));
                } else {
                    body = text.substring(item.body(), item.end());
                }
            }
            out.add(new Case(item.where(), item.name(), body));
        }
        return out;
    }

    /** The cases of one list that the other does not have, one for one, by where they stand. */
    private static List<Case> without(List<Case> all, List<Case> taken) {
        List<String> left = new ArrayList<>();
        for (Case c : taken) left.add(c.where());
        List<Case> out = new ArrayList<>();
        for (Case c : all) if (!left.remove(c.where())) out.add(c);
        return out;
    }

    private void deletedTestFiles() {
        for (String path : change.paths("D")) {
            if (!isTestFile(path)) continue;
            String was = before(path);
            String twin = was == null ? null : change.paths("A").stream().filter(a -> was.equals(after(a))).findFirst().orElse(null);
            if (twin != null) {
                note("test file moved", path, "the same file is now at " + twin);
                continue;
            }
            List<Case> cases = cases(path, was);
            if (cases.isEmpty() && !testNamed(path)) {
                note("test support file deleted", path, "it declares no test cases that a simple reading can find");
                continue;
            }
            Set<String> lost = new LinkedHashSet<>();
            Set<String> to = new TreeSet<>();
            for (Case c : cases) {
                String dest = movedTo(c.title(), path);
                if (dest == null) lost.add(c.title());
                else to.add(dest);
            }
            if (!cases.isEmpty() && lost.isEmpty()) note("test file moved", path, "all " + cases.size() + " of its test cases are now in " + String.join(", ", to));
            else problem("test file deleted", path, cases.isEmpty() ? "the file is gone"
                    : lost.size() + " of its " + cases.size() + " test cases are not found elsewhere in the change: " + String.join(", ", lost));
        }
    }

    private void removedTestCases() {
        for (String path : change.paths("M")) {
            if (!isTestFile(path)) continue;
            List<Case> was = cases(path, before(path)), now = cases(path, after(path));
            // by where each stands: a test of the same title in another group is another test
            List<Case> gone = without(was, now);
            if (!gone.isEmpty()) lostCases.add(path);
            List<Case> lost = new ArrayList<>();
            for (Case c : gone) {
                String dest = movedTo(c.title(), path);
                if (dest == null) lost.add(c);
                else note("test moved", path, c.title() + " is now in " + dest);
            }
            if (lost.isEmpty()) continue;
            // a rename keeps the test's body under a new name; a new test with another body does not make up for one that is gone
            List<Case> fresh = without(now, was);
            List<String> renamed = new ArrayList<>(), removed = new ArrayList<>();
            for (Case c : lost) {
                String body = squeezed(c.body());
                Case twin = body == null ? null : fresh.stream().filter(f -> body.equals(squeezed(f.body()))).findFirst().orElse(null);
                // named by its title; by where it stands when the title alone would not tell it from another
                boolean titleTells = now.stream().noneMatch(n -> n.title().equals(c.title())) && (twin == null || !twin.title().equals(c.title()));
                if (twin == null) {
                    removed.add(titleTells ? c.title() : c.where());
                } else {
                    renamed.add((titleTells ? c.title() : c.where()) + " is now " + (titleTells ? twin.title() : twin.where()));
                    fresh.remove(twin);
                }
            }
            if (!renamed.isEmpty()) note("test renamed", path, String.join(", ", renamed));
            if (!removed.isEmpty()) problem("test removed", path, removed.size() + (removed.size() == 1 ? " test case" : " test cases") + " removed or replaced: " + String.join(", ", removed));
        }
    }

    /** A test's body with its white space squeezed out, or null when there is none to compare. */
    private static String squeezed(String body) {
        if (body == null) return null;
        String s = body.replaceAll("\\s+", "");
        return s.isEmpty() ? null : s;
    }

    /** The file the change added a test case of this title to, other than the one it left; null when there is none. Each gained case answers once. */
    private String movedTo(String title, String from) {
        if (gained == null) {
            gained = new TreeMap<>();
            for (String path : change.paths("AM")) {
                if (!isTestFile(path)) continue;
                List<String> titles = new ArrayList<>();
                for (Case c : without(cases(path, after(path)), cases(path, before(path)))) titles.add(c.title());
                gained.put(path, titles);
            }
        }
        for (Map.Entry<String, List<String>> e : gained.entrySet()) if (!e.getKey().equals(from) && e.getValue().remove(title)) return e.getKey();
        return null;
    }


    private void skipFlags() {
        // a pom is read as XML, whatever its line breaks; when it cannot be parsed, its added lines are read as text like any other build file
        Set<String> readAsXml = new LinkedHashSet<>();
        for (String path : change.paths("AM")) if ((path.equals("pom.xml") || path.endsWith("/pom.xml")) && pomFlags(path)) readAsXml.add(path);
        for (Map.Entry<String, List<String>> e : added.entrySet()) {
            if (!buildOrScript(e.getKey())) continue;
            for (String line : e.getValue()) skipFlag(e.getKey(), line, readAsXml.contains(e.getKey()));
            for (String line : whole.getOrDefault(e.getKey(), List.of())) madeToPass(e.getKey(), line);
        }
        for (String path : change.paths("AM")) if ((path.equals("pom.xml") || path.endsWith("/pom.xml")) && !readAsXml.contains(path)) pluginSkips(path);
    }

    /** The skip and ignore settings a pom gained, read from its XML. False when the pom cannot be parsed. */
    private boolean pomFlags(String path) {
        Map<String, String> now = pomSettings(after(path));
        if (now == null) return false;
        Map<String, String> was = pomSettings(before(path));
        for (Map.Entry<String, String> e : now.entrySet()) if (was == null || !was.containsKey(e.getKey())) problem(e.getValue(), path, e.getKey());
        return true;
    }

    /** Each setting of a pom that skips tests or ignores their failures, with the kind of finding it is; null when the text is not XML. */
    private static Map<String, String> pomSettings(String pom) {
        Map<String, String> out = new TreeMap<>();
        if (pom == null) return out;
        try {
            org.w3c.dom.NodeList all = SafeXml.parse(pom).getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) all.item(i);
                String name = e.getTagName();
                boolean skip = name.matches("skipTests|skipITs|maven\\.test\\.skip"), ignore = name.matches("testFailureIgnore|maven\\.test\\.failure\\.ignore");
                if (!(skip || ignore || name.equals("skip")) || hasChildElement(e) || !e.getTextContent().trim().equalsIgnoreCase("true")) continue;
                // where the setting sits is part of what it is: the same line in another profile is another setting
                String shown = "<" + name + ">true</" + name + "> in " + where(e);
                if (skip) out.put(shown, "tests skipped by the build");
                else if (ignore) out.put(shown, "test failures ignored by the build");
                else {
                    String plugin = plugin(e);
                    if (plugin != null) out.put(plugin + " is told to skip: " + shown, "tests skipped by the build");
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasChildElement(org.w3c.dom.Element e) {
        for (org.w3c.dom.Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof org.w3c.dom.Element) return true;
        return false;
    }

    /** Where a setting sits in the pom: "properties", "profile ci, properties", "build, plugin maven-surefire-plugin". */
    private static String where(org.w3c.dom.Element e) {
        List<String> parts = new ArrayList<>();
        for (org.w3c.dom.Node p = e.getParentNode(); p instanceof org.w3c.dom.Element parent; p = p.getParentNode()) {
            String tag = parent.getTagName();
            if (tag.equals("profile") || tag.equals("plugin") || tag.equals("execution")) {
                String label = "";
                for (org.w3c.dom.Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof org.w3c.dom.Element child && (child.getTagName().equals("id") || child.getTagName().equals("artifactId")) && label.isEmpty()) label = child.getTextContent().trim();
                }
                parts.add(0, (tag + " " + label).trim());
            } else if (tag.equals("properties") || tag.equals("build") || tag.equals("pluginManagement") || tag.equals("reporting")) {
                parts.add(0, tag);
            }
        }
        return parts.isEmpty() ? "the pom" : String.join(", ", parts);
    }

    /** "Surefire" or "Failsafe" when the element sits in that plugin's block, else null. */
    private static String plugin(org.w3c.dom.Element e) {
        for (org.w3c.dom.Node p = e.getParentNode(); p instanceof org.w3c.dom.Element parent; p = p.getParentNode()) {
            if (!parent.getTagName().equals("plugin")) continue;
            for (org.w3c.dom.Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof org.w3c.dom.Element child && child.getTagName().equals("artifactId")) {
                    String id = child.getTextContent();
                    return id.contains("failsafe") ? "Failsafe" : id.contains("surefire") ? "Surefire" : null;
                }
            }
            return null;
        }
        return null;
    }

    private void skipFlag(String path, String line, boolean readAsXml) {
        String text = line.trim();
        if (!readAsXml && XML_SKIP.matcher(line).find() || GRADLE_EXCLUDE.matcher(line).find()) problem("tests skipped by the build", path, text);
        if (!readAsXml && XML_IGNORE.matcher(line).find() || GRADLE_IGNORE.matcher(line).find()) problem("test failures ignored by the build", path, text);
        Matcher d = D_FLAG.matcher(line);
        while (d.find()) {
            if (d.group(2) != null && !d.group(2).isEmpty() && !d.group(2).equalsIgnoreCase("true")) continue;
            boolean ignore = d.group(1).toLowerCase().contains("ignore");
            problem(ignore ? "test failures ignored by the build" : "tests skipped by the build", path, text);
        }
        if (PASS_EMPTY.matcher(line).find()) problem("tests skipped by the build", path, "the test run passes even when it finds no tests: " + text);
    }

    /**
     * "test command || true". Read on the whole line as the change wrote it, however long: a test
     * command and the "|| true" that hides its failure may be far apart. The walk is one pass, and
     * each command is squeezed and shortened before the pattern sees it.
     */
    private void madeToPass(String path, String line) {
        for (String command : commandsMadeToPass(line)) {
            if (!runsTests(command)) continue;
            String shown = line.trim().replaceAll("\\s+", " ");
            problem("test failures ignored by the build", path, "a failing test command still counts as success: " + shown);
        }
    }

    /** The command just before each "|| true", "|| :" or "|| exit 0" of a line, found by walking the line once. */
    private static List<String> commandsMadeToPass(String line) {
        List<String> out = new ArrayList<>();
        Matcher tail = OR_TAIL.matcher(line);
        int from = 0;
        while (true) {
            int at = line.indexOf("||", from);
            if (at < 0) return out;
            from = at + 2;
            int after = from;
            while (after < line.length() && Character.isWhitespace(line.charAt(after))) after++;
            if (!tail.region(after, line.length()).lookingAt()) continue;
            int start = at;
            while (start > 0 && "|;&\"'`(".indexOf(line.charAt(start - 1)) < 0) start--;
            out.add(line.substring(start, at).trim());
        }
    }

    /** <skip>true</skip> counts only inside the Surefire or Failsafe plugin; elsewhere it skips something else. */
    private void pluginSkips(String path) {
        Map<String, Integer> fresh = new HashMap<>();
        for (String line : added.getOrDefault(path, List.of())) if (PLUGIN_SKIP.matcher(line).find()) fresh.merge(line.trim(), 1, Integer::sum);
        if (fresh.isEmpty()) return;
        String plugin = null;
        boolean inPlugin = false;
        for (String line : after(path).split("\\R")) {
            String t = line.trim();
            if (t.startsWith("<plugin>")) {
                inPlugin = true;
                plugin = null;
            } else if (t.startsWith("</plugin>")) {
                inPlugin = false;
            } else if (inPlugin && plugin == null && t.startsWith("<artifactId>")) {
                plugin = t;
            } else if (PLUGIN_SKIP.matcher(t).find() && plugin != null && inPlugin && plugin.matches(".*(surefire|failsafe).*") && fresh.getOrDefault(t, 0) > 0) {
                fresh.merge(t, -1, Integer::sum);
                problem("tests skipped by the build", path, (plugin.contains("failsafe") ? "Failsafe" : "Surefire") + " is told to skip: " + t);
            }
        }
    }

    // ---------------- notes ----------------

    private void emptyTests() {
        for (String path : change.paths("AM")) {
            if (!isTestFile(path) || !(isJava(path) || isScript(path))) continue;
            List<Case> was = cases(path, before(path));
            for (Case c : cases(path, after(path))) {
                String body = c.body();
                if (body == null || asserts(path, body)) continue;
                if (isScript(path) && !body.contains("=>") && !body.contains("function")) continue; // it.todo('x'): no body to read
                // each old case answers for one new case: two tests of one name are two tests
                Case old = was.stream().filter(w -> w.where().equals(c.where())).findFirst().orElse(null);
                if (old != null) was.remove(old);
                if (old != null && squash(body).equals(squash(old.body()))) continue;
                note("appears to assert nothing", path, c.title() + " (" + (old != null ? "edited" : "new") + "): no assert, expect, verify or fail call that a simple reading can find");
            }
        }
    }

    private void fewerAssertions() {
        for (String path : change.paths("M")) {
            if (!isTestFile(path) || !(isJava(path) || isScript(path)) || lostCases.contains(path)) continue;
            int was = count(assertion(path), before(path));
            int now = count(assertion(path), after(path));
            if (now < was) note("fewer assertions", path, was + " assertions before, " + now + " after");
        }
    }

    private void byPath(Pattern kind, String what) {
        for (Change.File f : change.files) if (kind.matcher(f.path()).matches()) note(what, f.path(), word(f.status()));
    }

    private void buildConfiguration() {
        for (Change.File f : change.files) {
            if (BUILD.matcher(f.path()).matches()) {
                note("build configuration changed", f.path(), word(f.status()));
            } else if (isPackageJson(f.path())) {
                Set<String> scripts = changedKeys(section(before(f.path()), "scripts"), section(after(f.path()), "scripts"));
                if (!scripts.isEmpty()) note("build configuration changed", f.path(), "scripts changed: " + String.join(", ", scripts));
            }
        }
    }

    private void dependencies() {
        for (Change.File f : change.files) {
            String path = f.path();
            if (path.equals("pom.xml") || path.endsWith("/pom.xml")) compare(path, pomDependencies(before(path)), pomDependencies(after(path)));
            if (!isPackageJson(path)) continue;
            for (String s : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
                Map<String, String> was = new TreeMap<>();
                Map<String, String> now = new TreeMap<>();
                section(before(path), s).forEach((k, v) -> was.put(k + " (" + s + ")", String.valueOf(v)));
                section(after(path), s).forEach((k, v) -> now.put(k + " (" + s + ")", String.valueOf(v)));
                compare(path, was, now);
            }
        }
    }

    private void compare(String path, Map<String, String> was, Map<String, String> now) {
        for (Map.Entry<String, String> e : now.entrySet()) {
            String old = was.get(e.getKey());
            if (!was.containsKey(e.getKey())) note("dependency added", path, e.getKey() + version(e.getValue()));
            else if (!old.equals(e.getValue())) note("dependency changed", path, e.getKey() + ": " + orUnset(old) + " to " + orUnset(e.getValue()));
        }
        for (Map.Entry<String, String> e : was.entrySet()) if (!now.containsKey(e.getKey())) note("dependency removed", path, e.getKey() + version(e.getValue()));
    }

    private void lockFiles() {
        Set<String> changed = new LinkedHashSet<>(change.paths("AMD"));
        for (String path : changed) {
            int slash = path.lastIndexOf('/');
            String dir = slash < 0 ? "" : path.substring(0, slash + 1);
            List<String> manifests = LOCKS.get(path.substring(slash + 1));
            if (manifests == null || manifests.stream().anyMatch(m -> changed.contains(dir + m))) continue;
            note("lock file changed without its manifest", path, manifests.get(0) + " beside it did not change, so the resolved versions changed on their own");
        }
    }

    private void coverage() {
        for (Change.File f : change.files) {
            String path = f.path();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.equals("pom.xml") || name.startsWith("build.gradle")) minimums(path);
            else if (name.matches("(?:vitest|vite|jest)\\.config\\.[^/]+|package\\.json")) scriptThresholds(path);
        }
    }

    /** JaCoCo minimums, paired in the order they appear among the removed and the added lines. */
    private void minimums(String path) {
        List<Double> was = numbers(MINIMUM, removed.getOrDefault(path, List.of()));
        List<Double> now = numbers(MINIMUM, added.getOrDefault(path, List.of()));
        for (int i = 0; i < Math.min(was.size(), now.size()); i++) {
            if (now.get(i) < was.get(i)) note("coverage threshold lowered", path, "minimum coverage " + plain(was.get(i)) + " to " + plain(now.get(i)));
        }
    }

    private void scriptThresholds(String path) {
        String now = after(path);
        if (now == null || !now.toLowerCase().contains("threshold")) return;
        Map<String, Double> was = keyed(removed.getOrDefault(path, List.of()));
        for (Map.Entry<String, Double> e : keyed(added.getOrDefault(path, List.of())).entrySet()) {
            Double old = was.get(e.getKey());
            if (old != null && e.getValue() < old) note("coverage threshold lowered", path, e.getKey() + " coverage " + plain(old) + " to " + plain(e.getValue()));
        }
    }

    // ---------------- reading ----------------

    private String before(String path) {
        return beforeText.computeIfAbsent(path, p -> text(p, change.before(p)));
    }

    private String after(String path) {
        return afterText.computeIfAbsent(path, p -> text(p, change.after(p)));
    }

    /** A file too large to read is left unread, and the report says so: silence would read as "nothing found". */
    private String text(String path, String content) {
        if (content == null || content.length() <= MAX_FILE) return content;
        String detail = "larger than " + MAX_FILE / 1_000_000 + " MB, so the scan did not read it";
        if (isTestFile(path) || buildOrScript(path)) problem("too large to scan", path, detail);
        else note("too large to scan", path, detail);
        return null;
    }

    private static Pattern assertion(String path) {
        return isJava(path) ? JAVA_ASSERT : SCRIPT_ASSERT;
    }

    private static boolean asserts(String path, String body) {
        return assertion(path).matcher(body).find();
    }

    private static int count(Pattern p, String text) {
        if (text == null) return 0;
        int n = 0;
        Matcher m = p.matcher(text);
        while (m.find()) n++;
        return n;
    }

    /** The index of the bracket that closes the one at open, skipping strings and comments; -1 when there is none. */
    private static int closing(String t, int open, char in, char out) {
        int depth = 0;
        for (int i = open; i < t.length(); i++) {
            char c = t.charAt(i);
            if (t.startsWith("\"\"\"", i)) {
                int end = t.indexOf("\"\"\"", i + 3);
                i = end < 0 ? t.length() : end + 2;
            } else if (c == '"' || c == '\'' || c == '`') {
                i = endOfString(t, i);
            } else if (t.startsWith("//", i)) {
                int end = t.indexOf('\n', i);
                i = end < 0 ? t.length() : end;
            } else if (t.startsWith("/*", i)) {
                int end = t.indexOf("*/", i + 2);
                i = end < 0 ? t.length() : end + 1;
            } else if (c == in) {
                depth++;
            } else if (c == out && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int endOfString(String t, int start) {
        char quote = t.charAt(start);
        for (int i = start + 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\') i++;
            else if (c == quote || c == '\n' && quote != '`') return i; // an unclosed quote ends at the line end
        }
        return t.length();
    }

    private static String squash(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private static Map<String, Object> section(String packageJson, String key) {
        if (packageJson == null) return Map.of();
        try {
            return Json.map(Json.at(Json.parse(packageJson), key));
        } catch (RuntimeException e) {
            return Map.of(); // not valid JSON: nothing to compare
        }
    }

    private static Set<String> changedKeys(Map<String, Object> was, Map<String, Object> now) {
        Set<String> keys = new TreeSet<>(was.keySet());
        keys.addAll(now.keySet());
        keys.removeIf(k -> String.valueOf(was.get(k)).equals(String.valueOf(now.get(k))));
        return keys;
    }

    /** The text with its XML comments taken out, in one pass. An unclosed comment runs to the end. */
    static String withoutComments(String xml) {
        StringBuilder out = new StringBuilder(xml.length());
        int at = 0;
        while (true) {
            int open = xml.indexOf("<!--", at);
            if (open < 0) return out.append(xml, at, xml.length()).toString();
            out.append(xml, at, open);
            int close = xml.indexOf("-->", open + 4);
            if (close < 0) return out.toString();
            at = close + 3;
        }
    }

    /** artifactId (with groupId when two share it) to version; "" when the version is managed elsewhere. */
    private static Map<String, String> pomDependencies(String pom) {
        Map<String, String> out = new TreeMap<>();
        if (pom == null) return out;
        String text = withoutComments(pom);
        int at = 0;
        while (true) {
            int open = text.indexOf("<dependency>", at);
            int close = open < 0 ? -1 : text.indexOf("</dependency>", open);
            if (close < 0) return out;
            String block = text.substring(open + "<dependency>".length(), close);
            at = close + "</dependency>".length();
            String artifact = tag(block, "artifactId");
            if (artifact.isEmpty()) continue;
            String key = out.containsKey(artifact) && !out.get(artifact).equals(tag(block, "version")) ? tag(block, "groupId") + ":" + artifact : artifact;
            out.put(key, tag(block, "version"));
        }
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private static List<Double> numbers(Pattern p, List<String> lines) {
        List<Double> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = p.matcher(line);
            while (m.find()) {
                Double d = number(m.group(1) != null ? m.group(1) : m.group(2));
                if (d != null) out.add(d);
            }
        }
        return out;
    }

    private static Map<String, Double> keyed(List<String> lines) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = SCRIPT_THRESHOLD.matcher(line);
            while (m.find()) {
                Double d = number(m.group(2));
                if (d != null) out.putIfAbsent(m.group(1), d);
            }
        }
        return out;
    }

    private static Double number(String s) {
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String plain(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String version(String v) {
        return v == null || v.isEmpty() ? "" : " " + v;
    }

    private static String orUnset(String v) {
        return v == null || v.isEmpty() ? "no version" : v;
    }

    private static String word(String status) {
        return switch (status) {
            case "A" -> "added";
            case "D" -> "deleted";
            default -> "edited";
        };
    }

    private static String hitPath(String hit) {
        int at = hit.indexOf(": ");
        return at < 0 ? hit : hit.substring(0, at);
    }

    private static String hitLine(String hit) {
        int at = hit.indexOf(": ");
        return at < 0 ? "" : hit.substring(at + 2);
    }

    // ---------------- findings ----------------

    private void problem(String kind, String path, String detail) {
        out.add(Finding.problem(kind, path, clip(detail)));
    }

    private void note(String kind, String path, String detail) {
        out.add(Finding.note(kind, path, clip(detail)));
    }

    /** One line of at most MAX_DETAIL characters. */
    static String clip(String detail) {
        String line = detail.replaceAll("\\s+", " ").strip();
        return line.length() <= MAX_DETAIL ? line : line.substring(0, MAX_DETAIL - 3) + "...";
    }
}
