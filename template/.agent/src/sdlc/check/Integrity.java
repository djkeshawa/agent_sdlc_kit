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
import java.util.function.Predicate;
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
 * Test files of every language are read the same way: only the lines a change adds are linted for
 * the usual ways of writing a switch (lintedSwitches). That lint is a helper that catches mistakes
 * and plain attempts early, not a guard: text can be written to hide a switch from a reader of
 * text. Which tests a file holds, and how many that ran do not run now, is read from its run
 * (Witness), not from its source; the run is the guard.
 *
 * A test file that moves shows as a deletion and an addition. It is reported as a NOTE ("test file
 * moved") when a file the change adds has the same text, or (a JVM class) the same text but its
 * package line; as a NOTE too when only its package and import lines differ; otherwise its
 * deletion is a PROBLEM (see deletedTestFiles).
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

    // Switches as a line shows them: the usual ways of writing one, to catch mistakes and plain attempts early, in commands that
    // run no test. Not a guard: an author who sets out to hide a switch from a reader of text can (see lintedSwitches); the run
    // of a test file (sdlc check) is the guard. White space may stand around each dot (and ?. for a dot) and after the @ of an
    // annotation, other members may stand between (it.concurrent.skip, test.skip.each), a qualifier before
    // (@org.junit.jupiter.api.Disabled), and type arguments before or after a method's name (Assumptions.<Void>abort, Kotlin's
    // abort<Unit>). abort is a switch as Assumptions.abort, on the line that imports it, and called alone (statically imported):
    // not a call of another object's abort (controller . abort()), nor a declaration of a method of that name (see bareAborts).
    // An assumption is a switch when one of its arguments is a plain false (assumeTrue) or true (assumeFalse), whatever the
    // others compute (see assumptions).
    private static final String DOT = "\\s*\\??\\.\\s*";
    private static final Pattern SCRIPT_SWITCH = Pattern.compile(
            "\\b(?:it|test|describe|context|suite)(?:" + DOT + "\\w+){0,8}?" + DOT + "(?:skip|todo|only|fixme)\\b|\\bx(?:it|test|describe|context)(?=\\s*\\()|\\bf(?:it|describe)(?=\\s*\\()");
    private static final String TYPE_ARGUMENTS = "(?:<[^()<>]{0,200}>\\s*)?";
    private static final Pattern OTHER_SWITCH = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(?:Disabled|DisabledIf\\w*|Ignore)\\b"
            + "|\\bAssumptions\\s*\\.\\s*" + TYPE_ARGUMENTS + "abort\\b"
            + "|@\\s*\\(?\\s*pytest\\s*\\.\\s*mark\\s*\\.\\s*skip|@\\s*\\(?\\s*unittest\\s*\\.\\s*skip|\\bt\\s*\\.\\s*Skip(?:Now)?(?=\\s*\\()");
    private static final Pattern ABORT = Pattern.compile("\\babort\\s*" + TYPE_ARGUMENTS + "\\(");
    private static final Pattern ASSUME = Pattern.compile("\\bassume(True|False)\\s*\\(");
    /** Words after which abort( is still called alone: return abort(...), else abort(...). */
    private static final Set<String> CALLED_AFTER = Set.of("return", "await", "else", "yield", "do");

    /**
     * The line lint: true when a line holds a switch that skips a test, marks it as to do, or
     * focuses on one (seen). A skipped test that a change moves or gives another title is reported
     * like any other skip on a line the change wrote.
     */
    public static boolean lintedSwitch(String line) {
        return readings(line).stream().anyMatch(Integrity::hit);
    }

    /**
     * True when a line shows what the test finds, in one of its readings (see readings): what a
     * line shows as written is never hidden by the masking, so the cost is that a switch's words
     * inside a string, a trailing comment or a block comment's line are reported too.
     */
    static boolean seen(String line, Predicate<String> test) {
        return readings(line).stream().anyMatch(test);
    }

    /** A line wholly a comment: it starts with // or with a # that no name follows (in JavaScript #name is code). */
    static boolean commentLine(String line) {
        String t = line.stripLeading();
        if (t.startsWith("//")) return true;
        return t.startsWith("#") && (t.length() == 1 || !Character.isJavaIdentifierPart(t.charAt(1)));
    }

    /** Unicode escapes (a backslash, one u or more, four hex digits; in JavaScript also a backslash, u, and hex digits in braces): read before the words of a line. */
    private static final Pattern ESCAPE = Pattern.compile("\\\\u+([0-9a-fA-F]{4})|\\\\u\\{([0-9a-fA-F]{1,6})\\}");
    /** Line terminators a line can hold once its escapes are read, or as JavaScript reads them (U+2028, U+2029). */
    private static final Pattern BREAK = Pattern.compile("[\\n\\r\\u2028\\u2029\\u0085]");
    /** White space a language allows between words besides the ASCII kind (JavaScript: a no-break space, U+FEFF, ...). */
    private static final Pattern WIDE_SPACE = Pattern.compile("[\\u000B\\u000C\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000\\uFEFF]");
    private static final Pattern RUN_OF_SPACE = Pattern.compile("[ \\t]{2,}");

    /**
     * The ways one line is read, each with its runs of white space made one space (white space of
     * any kind a language allows): its code (comments dropped, string insides emptied); and, unless
     * the whole line is a comment, the line as written and the line with its block comments taken
     * out without regard to quotes (for a line whose quotes the masking cannot tell, as a regular
     * expression with a quote in it, where a comment between the words of a switch would hide it
     * from both). A line with Unicode escapes is read again with them decoded, and a line that
     * holds a line terminator (a decoded escape of a line feed, a U+2028) is read again piece by piece.
     */
    private static List<String> readings(String line) {
        Set<String> out = new LinkedHashSet<>();
        List<String> forms = new ArrayList<>(List.of(line));
        if (line.contains("\\u")) forms.add(decoded(line));
        for (String form : forms) {
            String spaced = WIDE_SPACE.matcher(form).replaceAll(" ");
            read(spaced, out);
            if (!BREAK.matcher(spaced).find()) continue;
            for (String piece : BREAK.split(spaced)) read(piece, out);
            read(BREAK.matcher(spaced).replaceAll(" "), out);
        }
        List<String> squeezed = new ArrayList<>();
        for (String r : out) squeezed.add(RUN_OF_SPACE.matcher(r).replaceAll(" "));
        return List.copyOf(new LinkedHashSet<>(squeezed));
    }

    private static void read(String line, Set<String> out) {
        out.add(code(line));
        if (commentLine(line)) return;
        out.add(line);
        out.add(BLOCK_COMMENT.matcher(line).replaceAll(" "));
    }

    /** A line with its Unicode escapes decoded. */
    private static String decoded(String line) {
        Matcher m = ESCAPE.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2), 16);
            m.appendReplacement(out, Matcher.quoteReplacement(Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/");

    /** A word every switch the lint knows holds: a reading without one is not linted further. */
    private static final Pattern KEY = Pattern.compile("skip|todo|only|fixme|Disabled|Ignore|abort|assume|Skip|xit|xtest|xdescribe|xcontext|fit|fdescribe");

    private static boolean lintedCode(String code) {
        if (!KEY.matcher(code).find()) return false;
        return FOCUSED.matcher(code).find() || SCRIPT_SWITCH.matcher(code).find() || OTHER_SWITCH.matcher(code).find() || !bareAborts(code).isEmpty()
                || !assumptions(code).isEmpty();
    }

    /** How far the arguments of one call are read: a call whose end is further is read to there. */
    private static final int SCAN = 1000;

    /** How much of one reading is linted at once, and by how much the pieces of a longer one overlap: the work per line is bounded. */
    private static final int READ = 2000, EDGE = 300;

    /**
     * True when a reading holds a switch. A reading longer than READ is linted in pieces that
     * overlap by EDGE, each cut where a word ends, so that no piece makes a word of a part of one.
     * A switch is a few words, and its white space is one space here, so it fits in the overlap
     * unless its words stand more than EDGE characters apart.
     */
    private static boolean hit(String r) {
        if (r.length() <= READ || !KEY.matcher(r).find()) return lintedCode(r);
        for (int at = 0; at < r.length(); at += READ - EDGE) {
            int from = at == 0 ? 0 : wordEdge(r, at), to = Math.min(r.length(), at + READ);
            while (to < r.length() && to > from && word(r.charAt(to - 1)) && word(r.charAt(to))) to--;
            if (from < to && lintedCode(r.substring(from, to))) return true;
            if (at + READ >= r.length()) break;
        }
        return false;
    }

    private static boolean word(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /** The first index at or after at that does not cut a word. */
    private static int wordEdge(String r, int at) {
        while (at < r.length() && at > 0 && word(r.charAt(at - 1)) && word(r.charAt(at))) at++;
        return at;
    }

    /** The last EDGE characters of a reading, from where a word starts. */
    private static String tail(String r) {
        return r.length() <= EDGE ? r : r.substring(wordEdge(r, r.length() - EDGE));
    }

    /** The first EDGE characters of a reading, to where a word ends. */
    private static String head(String r) {
        if (r.length() <= EDGE) return r;
        int to = EDGE;
        while (to > 0 && word(r.charAt(to - 1)) && word(r.charAt(to))) to--;
        return r.substring(0, to);
    }

    /**
     * Where abort( is called alone, as a statically imported Assumptions.abort is: not after a dot
     * (controller.abort(), x?.abort()), a # or a name (a declaration: function abort(), void
     * abort(), def abort(); but return abort(...) is a call), and not followed by a body (abort()
     * { ... }, a method declared in a class or an object). Each as [start, end).
     */
    private static List<int[]> bareAborts(String s) {
        List<int[]> out = new ArrayList<>();
        Matcher m = ABORT.matcher(s);
        while (m.find()) {
            int p = m.start() - 1;
            while (p >= 0 && Character.isWhitespace(s.charAt(p))) p--;
            if (p >= 0) {
                char c = s.charAt(p);
                if (c == '.' || c == '#' || c == '>' || c == '@') continue;
                if (word(c)) {
                    int q = p;
                    while (q >= 0 && word(s.charAt(q))) q--;
                    if (!CALLED_AFTER.contains(s.substring(q + 1, p + 1))) continue;
                }
            }
            int close = closing(s, m.end() - 1, ')');
            if (close >= 0) {
                int k = close + 1;
                while (k < s.length() && Character.isWhitespace(s.charAt(k))) k++;
                if (k < s.length() && s.charAt(k) == '{') continue;
            }
            out.add(new int[] {m.start(), close >= 0 ? close + 1 : s.length()});
        }
        return out;
    }

    /**
     * Where an assumption never holds: assumeTrue with an argument that is a plain false, or
     * assumeFalse with one that is a plain true, whatever the other arguments are (a message, a
     * lambda, String.format(...)): the arguments are told apart by their parentheses, brackets
     * and braces, and strings. A call whose end is not on the line runs to the end of it. Each as
     * [start, end).
     */
    private static List<int[]> assumptions(String s) {
        List<int[]> out = new ArrayList<>();
        Matcher m = ASSUME.matcher(s);
        while (m.find()) {
            String never = m.group(1).equals("True") ? "false" : "true";
            int depth = 0, from = m.end(), i = m.end(), stop = Math.min(s.length(), m.end() + SCAN);
            boolean plain = false;
            for (; i < stop; i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\'' || c == '`') {
                    int end = closing(s, i);
                    if (end < 0 || end >= stop) {
                        i = stop;
                        break;
                    }
                    i = end;
                } else if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                    depth--;
                } else if (depth == 0 && (c == ',' || c == ')')) {
                    plain |= s.substring(from, i).strip().equals(never);
                    from = i + 1;
                    if (c == ')') break;
                }
            }
            if (i >= stop) plain |= s.substring(Math.min(from, stop), stop).strip().equals(never);
            if (plain) out.add(new int[] {m.start(), Math.min(s.length(), i + 1)});
        }
        return out;
    }

    /** The index of the bracket that closes the one at open (a parenthesis), counting brackets and skipping strings; -1 when it is not on the line. */
    private static int closing(String s, int open, char close) {
        int depth = 0;
        for (int i = open, stop = Math.min(s.length(), open + SCAN); i < stop; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int end = closing(s, i);
                if (end < 0 || end >= stop) return -1;
                i = end;
            } else if (c == '(') {
                depth++;
            } else if (c == close && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The line lint over the lines a change adds to one file, in their order, as the change wrote
     * them: each line that holds a switch (lintedSwitch), as it is written, and each run of two or
     * three added lines that hold one only together (it, a line break, .skip; @, a line break,
     * Disabled), joined by a space: each line read every way (readings) and every way of joining
     * them tried, and the lines joined as written and then read (a block comment that opens on one
     * line and closes on the next, a Python line that ends in a backslash). Where they are joined,
     * only the last EDGE characters of a line and the first EDGE of the next count. Without the
     * second part, breaking a switch over two lines would hide it from a command that runs no test
     * (check-pr). The lines are taken as neighbours as the diff lists them; lines of two places of
     * the file that only together read as a switch are reported too, which errs on the safe side.
     *
     * It finds the usual ways of writing a switch, to catch mistakes and plain attempts early. It
     * is not a guard: an author who sets out to hide a switch from a reader of text can (a quote
     * in a regular expression before a comment between the switch's words, a switch reached
     * through another name, one of which the change wrote only a part, one over more than three
     * lines). The run of the file says which tests that ran before the change do not run now,
     * whatever the source says.
     */
    public static List<String> lintedSwitches(List<String> addedLines) {
        List<String> out = new ArrayList<>();
        List<List<String>> read = new ArrayList<>();
        boolean[] told = new boolean[addedLines.size()];
        for (int i = 0; i < told.length; i++) {
            read.add(readings(addedLines.get(i)));
            if (read.get(i).stream().noneMatch(Integrity::hit)) continue;
            told[i] = true;
            out.add(addedLines.get(i).trim());
        }
        for (int width = 2; width <= 3; width++) {
            for (int i = 0; i + width <= told.length; i++) {
                boolean fresh = true;
                StringBuilder shown = new StringBuilder(), written = new StringBuilder();
                for (int k = i; k < i + width; k++) {
                    fresh &= !told[k];
                    shown.append(k == i ? "" : " ").append(addedLines.get(k).trim());
                    String line = addedLines.get(k).stripTrailing();
                    if (line.endsWith("\\")) line = line.substring(0, line.length() - 1); // a Python line continued on the next
                    written.append(k == i ? "" : " ").append(k == i ? tail(line) : k == i + width - 1 ? head(line) : line.length() > 2 * EDGE ? head(line) : line);
                }
                if (!fresh || !joinedHit(read.subList(i, i + width), "") && readings(written.toString()).stream().noneMatch(Integrity::hit)) continue;
                for (int k = i; k < i + width; k++) told[k] = true;
                out.add(shown.toString());
            }
        }
        return out;
    }

    /**
     * True when some way of joining the readings of these lines, one of each after what is joined
     * so far, holds a switch: the end of each line with the start of the next (see tail, head).
     */
    private static boolean joinedHit(List<List<String>> lines, String so) {
        if (lines.isEmpty()) return lintedCode(so);
        boolean last = lines.size() == 1;
        for (String r : lines.get(0)) {
            String part = so.isEmpty() ? tail(r) : last ? head(r) : r.length() > 2 * EDGE ? head(r) : r;
            if (joinedHit(lines.subList(1, lines.size()), so.isEmpty() ? part : so + " " + part)) return true;
        }
        return false;
    }

    /**
     * A line with its built-in switches taken out. What a configured pattern still finds in it is
     * the repository's own (a helper of its own that skips, a comment directive) and is judged by
     * the line as written; the built-in ones are judged by the line lint.
     */
    public static String withoutBuiltInSwitches(String line) {
        List<int[]> spans = new ArrayList<>(assumptions(line));
        spans.addAll(bareAborts(line));
        spans.sort(Comparator.comparingInt((int[] x) -> x[0]).reversed());
        StringBuilder out = new StringBuilder(line);
        int limit = line.length();
        for (int[] x : spans) {
            if (x[1] > limit) continue; // within one already taken out
            out.replace(x[0], x[1], " ");
            limit = x[0];
        }
        return OTHER_SWITCH.matcher(SCRIPT_SWITCH.matcher(out.toString()).replaceAll(" ")).replaceAll(" ");
    }

    private static final Pattern FOCUSED = Pattern.compile("\\b(it|test|describe|context|suite)(" + DOT + "\\w+){0,8}" + DOT + "only\\b|\\bf(it|describe)\\s*\\(");
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
    /** The added and removed lines as the change wrote them; added and removed hold the same lines in pieces of bounded length. */
    private final Map<String, List<String>> whole;
    private final Map<String, List<String>> wholeRemoved;
    private final Map<String, List<String>> added;
    private final Map<String, List<String>> removed;
    private final Map<String, String> beforeText = new HashMap<>();
    private final Map<String, String> afterText = new HashMap<>();
    private final Set<Finding> out = new LinkedHashSet<>();
    /**
     * The scan reads what the change wrote, so its work per line and per file is bounded. Nothing is
     * dropped for that: a long line is scanned in overlapping pieces. Test files are read by the
     * lines the change adds and removes, never whole; only build and configuration files are read
     * whole (a pom, package.json, a coverage configuration), and one too large to read is reported.
     */
    private static final int MAX_LINE = 2000;
    private static final int OVERLAP = 300;
    private static final int MAX_FILE = 2_000_000;

    private Integrity(Change change) {
        this.change = change;
        this.whole = change.addedLines();
        this.added = capped(whole);
        this.wholeRemoved = change.removedLines();
        this.removed = capped(wholeRemoved);
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
        s.skipFlags();
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
        // every test file alike: the lines the change adds, as it wrote them, are linted alone and two or three together (the lint
        // bounds its own work on a long line, so no line is cut into pieces that would count as lines of their own)
        for (String path : change.paths("AM")) {
            if (!anyTestFile(path)) continue;
            for (String hit : lintedSwitches(whole.getOrDefault(path, List.of()))) switchedOff(path, hit);
        }
        // the repository's own patterns may name more than the built-in lint knows; a line is read as the lint reads it
        for (String hit : Checks.disabledTests(added)) {
            if (!seen(hitLine(hit), Checks::containsDisabled)) continue;
            switchedOff(hitPath(hit), hitLine(hit));
        }
    }

    /**
     * A line with what looks like no code taken out: comments are dropped (a block comment becomes a
     * space, so "@", a block comment and "Disabled" read as "@ Disabled") and the inside of each string is
     * emptied. Code that follows a comment on the same line stays, and so does code inside ${...}
     * of a template string. Where a quote never closes, the rest is kept as it is. One line cannot
     * tell its quotes for certain, so this is never the only reading of a line (see seen).
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

    private static final Pattern TEST_CALL = Pattern.compile("\\b[xf]?(?:it|test|describe|context|suite)(?:" + DOT + "\\w+){0,8}\\s*(?:\\?\\.\\s*)?\\(|^\\s*(?:async\\s+)?def\\s+test|\\bfunc\\s+Test"
            + "|@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    /** Source files, whose code can declare tests; the lines of any other file (a .json, a .txt fixture) are data. */
    private static final Pattern SOURCE = Pattern.compile(".*\\.(?:java|kt|kts|groovy|scala|[cm]?[jt]sx?|vue|svelte|py|go|rb|cs|php|rs|swift)");
    private static final Pattern PACKAGE_LINE = Pattern.compile("\\s*package\\s+[\\w.]+\\s*;?\\s*");
    private static final Pattern IMPORT_LINE = Pattern.compile("\\s*import\\s+(?:static\\s+)?[\\w.]+(?:\\s*\\.\\s*\\*)?\\s*;?\\s*");
    private static final Pattern JVM = Pattern.compile(".*\\.(?:java|kt|groovy|scala)");

    /**
     * What the lines of a deleted test file say of the tests it declared: "code" when a line's
     * code calls it, test, describe or their kin, defines a test function (Python, Go), or carries
     * a JUnit test annotation; "text" when only a comment or a string of a line does (a helper
     * whose comment shows an example, a fixture that holds sample source); null when none does.
     * The conservative readings of the switch lint (a line as written) are for finding switches,
     * not for proving that a file declared tests, so only the code reading proves it. A file that
     * is not source (see SOURCE) declares nothing. A lint of lines: which tests it held is not read.
     */
    private static String declaresTests(String path, List<String> lines) {
        if (!SOURCE.matcher(path).matches()) return null;
        String found = null;
        for (String line : lines) {
            if (TEST_CALL.matcher(code(line)).find()) return "code";
            if (found == null && seen(line, l -> TEST_CALL.matcher(l).find())) found = "text";
        }
        return found;
    }

    /** The lines that are not package lines (and, with imports, not import lines either). */
    private static List<String> body(List<String> lines, boolean imports) {
        return lines.stream().filter(l -> !PACKAGE_LINE.matcher(l).matches() && !(imports && IMPORT_LINE.matcher(l).matches())).toList();
    }

    /**
     * A deleted test file (built-in or by paths.test) is read by the lines the change removes,
     * which are all of its lines. It moved when a file the change adds has exactly those lines,
     * or (a JVM source) those lines but its package line: a class moved to another package. A JVM
     * test file whose lines differ from an added one's only in its package and import lines moved
     * too, and its imports changed: a note, as an import can change what a name in it means, and
     * the run of the moved file is its judge now (sdlc check runs it as a new file). It is a test
     * file deleted when it is named like a test file or the code of a line declares tests; a note
     * when only a comment or a string mentions a test; otherwise it is a support file (a fixture,
     * a helper).
     */
    private void deletedTestFiles() {
        for (String path : change.paths("D")) {
            if (!anyTestFile(path)) continue;
            List<String> was = wholeRemoved.getOrDefault(path, List.of());
            String twin = change.paths("A").stream().filter(a -> was.equals(whole.getOrDefault(a, List.of()))).findFirst().orElse(null);
            boolean jvm = JVM.matcher(path).matches();
            String repackaged = twin != null || !jvm ? null : change.paths("A").stream().filter(a -> JVM.matcher(a).matches()
                    && body(was, false).equals(body(whole.getOrDefault(a, List.of()), false))).findFirst().orElse(null);
            String reimported = twin != null || repackaged != null || !jvm ? null : change.paths("A").stream().filter(a -> JVM.matcher(a).matches()
                    && body(was, true).equals(body(whole.getOrDefault(a, List.of()), true))).findFirst().orElse(null);
            String declared = declaresTests(path, was);
            if (twin != null) note("test file moved", path, "the same file is now at " + twin);
            else if (repackaged != null) note("test file moved", path, "the same file is now at " + repackaged + ", in another package");
            else if (reimported != null) note("test file moved and its imports changed", path, "the same tests are now at " + reimported + ", with other imports: its run judges it");
            else if (testNamed(path) || "code".equals(declared)) problem("test file deleted", path, "the file is gone");
            else if ("text".equals(declared)) note("test support file deleted", path, "only a comment or a string in it mentions a test; no line of its code declares one");
            else note("test support file deleted", path, "it declares no test cases that a simple reading can find");
        }
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

    /**
     * A plain count, not a reading of tests: the calls that look like assertions on the lines an
     * edit of a Java or script test file removes, against those on the lines it adds. Comments and
     * strings count as written.
     */
    private void fewerAssertions() {
        for (String path : change.paths("M")) {
            if (!isTestFile(path) || !(isJava(path) || isScript(path))) continue;
            int was = count(assertion(path), wholeRemoved.getOrDefault(path, List.of()));
            int now = count(assertion(path), whole.getOrDefault(path, List.of()));
            if (now < was) note("fewer assertions", path, "the lines the change removes hold " + was + (was == 1 ? " assertion" : " assertions") + ", the lines it adds " + now);
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

    /**
     * A file too large to read is left unread, and the report says so: silence would read as
     * "nothing found". Only build and configuration files are read whole (before, after).
     */
    private String text(String path, String content) {
        if (content == null || content.length() <= MAX_FILE) return content;
        String detail = "larger than " + MAX_FILE / 1_000_000 + " MB, so the scan did not read it";
        if (buildOrScript(path)) problem("too large to scan", path, detail);
        else note("too large to scan", path, detail);
        return null;
    }

    private static Pattern assertion(String path) {
        return isJava(path) ? JAVA_ASSERT : SCRIPT_ASSERT;
    }

    private static int count(Pattern p, List<String> lines) {
        int n = 0;
        for (String line : lines) {
            Matcher m = p.matcher(line);
            while (m.find()) n++;
        }
        return n;
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
