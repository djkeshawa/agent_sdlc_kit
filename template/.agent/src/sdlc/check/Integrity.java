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
import java.util.function.IntSupplier;
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
 * the usual ways of writing a switch (lint), each line no longer than LONGEST: a PROBLEM where the
 * lines show it for sure, a NOTE where a condition or more context may decide it. That lint
 * is a helper that catches mistakes and plain attempts early, not a guard: text can be written to
 * hide a switch from a reader of text. Which tests a file holds, and how many that ran do not run
 * now, is read from its run (Witness), not from its source; the run is the guard.
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
    /** Read on a file's name alone (no "/" in it), so each alternative's [^/]* runs to the name's end once. */
    private static final Pattern TEST_NAME = Pattern.compile(
            "[^/]*\\.(?:test|spec)\\.[^/]+|[^/]*(?:Test|Tests|IT)\\.(?:java|kt|groovy|scala)|test_[^/]*\\.py|[^/]*_test\\.(?:py|go)");

    /** A test file: under src/test, test, tests or __tests__, or named like one (FooTest.java, foo.test.ts, test_foo.py, foo_test.go). */
    static boolean isTestFile(String path) {
        return TEST_FOLDER.matcher("/" + path).matches() || testNamed(path);
    }

    static boolean testNamed(String path) {
        return TEST_NAME.matcher(path.substring(path.lastIndexOf('/') + 1)).matches();
    }

    /**
     * A test file as the scan knows one: by the built-in idea (see isTestFile), or by the
     * repository's own test paths (paths.test). check-pr lints the same files as sdlc check.
     */
    public static boolean testFile(String path, List<String> testPaths) {
        return isTestFile(path) || sdlc.util.Glob.any(testPaths, path);
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
    private static final Pattern MIGRATION = Pattern.compile("(?:.*/)?(?:db/migration|migrations|alembic/versions|db/changelog)/.*|.*\\.sql");
    /** The extension of a changelog file (Liquibase): its name holds "changelog" before it. */
    private static final Pattern CHANGELOG_EXTENSION = Pattern.compile("\\.(?:xml|ya?ml|json)$");

    /**
     * A database migration: in a migration folder, a .sql file, or a changelog file. The changelog
     * is read by its name, not by a pattern: "[^/]*changelog[^/]*" would try each place in a long
     * name for both of its parts.
     */
    private static boolean migration(String path) {
        if (MIGRATION.matcher(path).matches()) return true;
        String name = path.substring(path.lastIndexOf('/') + 1);
        Matcher ext = CHANGELOG_EXTENSION.matcher(name);
        return ext.find() && name.substring(0, ext.start()).contains("changelog");
    }

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

    // The switch lint: the usual ways of writing a switch that skips a test, marks it as to do or focuses on one, looked for on
    // the lines a change adds to test files. A helper that catches mistakes and plain attempts early, in commands that run no
    // test; not a guard: an author who sets out to hide a switch from a reader of text can (see lint). The run of a test file
    // (sdlc check) is the guard.
    //
    // It says what it finds at one of two levels (see lint). Sure, a problem: a switch whose whole meaning stands in what it
    // read, with no condition possible (an annotation that switches off, a declaration with its title, an assumption that
    // never holds). Anything else in which it sees a switch's words is a note, never a failure: whether a skip from inside a
    // test runs under a condition, whether a computed first argument is a condition or a title, whether abort( is the one
    // that skips, needs more than the added lines hold, and the run of the file says what happened. A call is never judged
    // from a part of it: one whose arguments a line leaves open is judged with the lines after it. No pattern says sure by
    // itself: every sure form goes through the readers of one gate (see the table in lint): a complete argument list, a
    // literal that is the whole argument, a title that is a string for certain, an option that is a top-level property, a
    // call that registers a test, a decorator or an annotation that is the whole expression; and then through the gate
    // itself (see sure), the one place where a form becomes sure: it stands on its own (a statement, a declaration's
    // annotation, a decorator), and nothing holds it (a condition before it on its reading, or on the added line before).
    // The written reading decides (see judged): the code reading, whose strings are emptied and comments dropped, never
    // makes a form sure.
    //
    // Its work is bounded and linear in what it is given. An added line longer than LONGEST is not read at all (the report says
    // how many were not). A line is read two ways (see reading), and so is a run of two or three added lines, joined (see
    // joined); nothing else. Each reading is made in one forward pass (decoded, code, fold). No pattern below can match one
    // stretch of text in two ways that both go on (each repetition holds a character the next part cannot start with), so each
    // walks a reading about once from each place it can start. The arguments of a call are read at most SCAN characters far,
    // and all the calls of one reading together at most a few times its length (see Budget): a line of nothing but calls
    // (abort(abort(abort(...) costs no more than another.

    /** The longest added line the switch lint reads: a longer one is not read, and the report says so. */
    public static final int LONGEST = 2000;
    /** How far the arguments of one call are read. */
    private static final int SCAN = 500;

    private static final String DOT = "\\s*\\??\\.\\s*";
    /** it.skip, test.only, describe.todo, test.fixme; also it.concurrent.skip, it?.skip. Group 1 is the modifier. */
    private static final Pattern SCRIPT_SWITCH = Pattern.compile(
            "(?=[itdcs])\\b(?:it|test|describe|context|suite|specify)(?:" + DOT + "\\w+){0,8}?" + DOT + "(skip|todo|only|fixme)\\b");
    /** Vitest's conditions: it.skipIf(...), describe.runIf(...), to their opening parenthesis. Group 1: skipIf or runIf. */
    private static final Pattern CONDITIONAL_CALL = Pattern.compile(
            "(?=[itdcs])\\b(?:it|test|describe|context|suite)(?:" + DOT + "\\w+){0,8}?" + DOT + "(skipIf|runIf)\\s*\\(");
    /**
     * xit, xtest, xdescribe, xcontext, xspecify, fdescribe and fit, as names of their own: not
     * another object's (model.fit(x)) nor a declaration (def fit(...)). What follows decides (see
     * aliases).
     */
    private static final Pattern ALIAS = Pattern.compile("(?=[xf])(?<![\\w$.])(?<!def )(?<!function )(?<!fun )"
            + "(?:x(?:it|test|describe|context|specify)|fdescribe|fit)(?![\\w$])");
    private static final String TYPE_ARGUMENTS = "(?:<[^()<>]{0,200}>\\s*)?";
    /** @Disabled, @Ignore, @Test, @DisabledIf and @EnabledIf, also qualified, each as the whole name. Group 1: the name. */
    private static final Pattern ANNOTATION = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(Disabled|Ignore|Test|DisabledIf|EnabledIf)(?![\\w$])");
    /** An element of an annotation's arguments by its name (enabled = false). Group 1: the name. */
    private static final Pattern ELEMENT = Pattern.compile("([\\w$]+)\\s*=(?!=)");
    /** A string literal that is the whole value of a condition: "true", "false", with white space inside. Group 1: the word. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"\\s*(true|false)\\s*\"");
    /** Assumptions.abort (also Assumptions.<Void>abort), and the line that imports it statically. */
    private static final Pattern ABORTS = Pattern.compile("(?=A)\\bAssumptions\\s*\\.\\s*" + TYPE_ARGUMENTS + "abort\\b");
    /** A mark of pytest (group 1: skip, skipif or xfail) or of unittest (group 2: skip, skipIf or skipUnless), as the whole name. */
    private static final Pattern PY_MARK = Pattern.compile(
            "(?=[pu])\\b(?:pytest\\s*\\.\\s*mark\\s*\\.\\s*(skip|skipif|xfail)|unittest\\s*\\.\\s*(skip|skipIf|skipUnless))(?![\\w$])");
    /** Where a mark applies to tests: a decorator's @ and pytestmark =; and pytest.param, whose marks= is read (see param). */
    private static final Pattern PY_CONTEXT = Pattern.compile("(?=[@p])(?:@|\\bpytestmark\\s*=(?!=))");
    private static final Pattern PARAM = Pattern.compile("(?=p)\\bpytest\\s*\\.\\s*param\\s*\\(");
    /**
     * A skip from inside a test, as a statement of its own: pytest.skip(...), self.skipTest(...),
     * raise unittest.SkipTest(...), Mocha's this.skip() (no argument), Go's t.Skip, t.Skipf, t.SkipNow,
     * Jasmine's pending('reason').
     */
    private static final Pattern BODY_SKIP = Pattern.compile("(?:^|[;{}:])\\s*(?:(?:pytest\\s*\\.\\s*skip|self\\s*\\.\\s*skipTest|raise\\s+(?:unittest\\s*\\.\\s*)?SkipTest"
            + "|t\\s*\\.\\s*Skip(?:f|Now)?)\\s*\\(|this\\s*\\.\\s*skip\\s*\\(\\s*\\)|pending\\s*\\(\\s*(?:['\"`]|\\)))");
    /** A condition before an assumption on its reading (if (x) assumeTrue(false)): if, else, a ternary, &&, || and their kin. */
    private static final Pattern CONDITION = Pattern.compile("(?=[iuewcf?&|])(?:\\b(?:if|unless|elif|else|when|except|catch|case|while|for)\\b|\\?(?![.?])|&&|\\|\\|)");
    /** The start of a test declared without a body, its whole declaration a reading of one line: it('pending'); (see pending). */
    private static final Pattern PENDING = Pattern.compile("(?:it|test|specify)\\s*+\\(");
    /** A test declaration, for its option object (Vitest: test('x', { skip: true }, fn)); not another object's (regex.test(x)). */
    private static final Pattern DECLARATION = Pattern.compile("(?=[itdsc])(?<![\\w$.])(?<!\\.\\s)\\b(?:it|test|describe|suite|context|specify)(?:" + DOT + "\\w+){0,3}?\\s*\\(");
    /** A reading that may hold an option that switches (skip:, only:, todo:): only such a reading is read for option objects. */
    private static final Pattern OPTION_KEY = Pattern.compile("(?=[sot])\\b(?:skip|only|todo)['\"]?\\s*:");
    private static final List<String> OPTIONS = List.of("skip", "only", "todo");
    /** A property of an object by its name, plain or quoted (skip: ..., 'skip': ...). Group 1, 2 or 3: the name. */
    private static final Pattern PROPERTY = Pattern.compile("(?:([\\w$]+)|'([\\w$]*)'|\"([\\w$]*)\")\\s*:(?!:)");
    private static final Pattern ABORT = Pattern.compile("(?=a)\\babort\\s*" + TYPE_ARGUMENTS + "\\(");
    private static final Pattern ASSUME = Pattern.compile("(?=a)\\bassume(True|False)\\s*\\(");
    /** A keyword argument of a Python call (reason="x"), not a comparison. Group 1: its name. */
    private static final Pattern KEYWORD = Pattern.compile("([A-Za-z_]\\w*)\\s*=(?!=)");
    /** Words after which abort( is still called alone: return abort(...), else abort(...). */
    private static final Set<String> CALLED_AFTER = Set.of("return", "await", "else", "yield", "do");
    /**
     * A word every switch the lint knows holds (but a pending test). Which of them a line holds
     * says which kinds of switch are looked for in it (see keys): a line with none is not linted
     * further. A reading never makes a word its line does not hold (it drops comments and the
     * insides of strings, and makes white space one space), so the line's words stand for its readings'.
     */
    private static final Pattern KEY = Pattern.compile(
            "(?=[sStofDIEaxfepr])(?:skip|Skip|todo|only|fixme|runIf|pending|Disabled|Ignore|EnabledIf|enabled|abort|assume|xfail|xit|xtest|xdescribe|xcontext|xspecify|fit|fdescribe)");
    private static final int SKIP = 1, MODIFIER = 2, ANNOTATED = 4, ABORTED = 8, ASSUMED = 16, XFAIL = 32, ALIASED = 64;
    /**
     * What the lint makes of a reading, in order: nothing; a note; a call whose arguments the reading leaves open before they
     * say what it is (judged with the lines after it, see lint); a switch it is sure of.
     */
    private static final int NONE = 0, NOTE = 1, OPEN = 2, SURE = 3;
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d_.]*");

    /** The kinds of switch a line's words allow (see KEY), in one pass. */
    private static int keys(String decodedLine) {
        int keys = 0;
        for (Matcher k = KEY.matcher(new Counted(decodedLine)); k.find();) {
            keys |= switch (k.group()) {
                case "skip", "Skip", "pending" -> SKIP | MODIFIER;
                case "todo", "only", "fixme", "runIf" -> MODIFIER;
                case "Disabled", "Ignore", "EnabledIf", "enabled" -> ANNOTATED;
                case "abort" -> ABORTED;
                case "assume" -> ASSUMED;
                case "xfail" -> XFAIL;
                default -> ALIASED;
            };
        }
        return keys;
    }

    /** The characters the switch lint has read, its patterns' reads included: the self test bounds its work by what it is given. */
    private static long work;

    public static long lintWork() {
        return work;
    }

    /** A reading as the patterns read it: each character they look at is counted (see work). */
    private record Counted(String text) implements CharSequence {
        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int i) {
            work++;
            return text.charAt(i);
        }

        @Override
        public CharSequence subSequence(int from, int to) {
            return new Counted(text.substring(from, to));
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * How many characters the calls of one reading may still read for their arguments: a few
     * times the reading's length. A call read when none are left has its end out of reach.
     */
    private static final class Budget {
        private long left;

        Budget(int length) {
            left = 4L * length + 4L * SCAN;
        }

        boolean spend() {
            return --left >= 0;
        }
    }

    /** As much as a line of LONGEST characters can need: for a line the lint does not bound (withoutBuiltInSwitches). */
    private static Budget unbounded() {
        return new Budget(Integer.MAX_VALUE / 8);
    }

    /** True when one line holds a switch the lint is sure of (see lint), read without the lines around it. */
    public static boolean lintedSwitch(String line) {
        return level(line) == SURE;
    }

    /** True when one line, read without the lines around it, holds what the lint only notes (see lint), a call it leaves open too. */
    public static boolean lintedNote(String line) {
        int level = level(line);
        return level == NOTE || level == OPEN;
    }

    private static int level(String line) {
        if (line.length() > LONGEST) return NONE;
        String d = decoded(line);
        return judged(readDecoded(d), keys(d), true, () -> FREE).level();
    }

    /** True when a line shows what the test finds, in one of its two readings (see reading). */
    static boolean seen(String line, Predicate<String> test) {
        Reading r = reading(line);
        return test.test(r.code()) || test.test(r.written());
    }

    /** A line wholly a comment: it starts with // or with a # that no name follows (in JavaScript #name is code). */
    static boolean commentLine(String line) {
        String t = line.stripLeading();
        if (t.startsWith("//")) return true;
        return t.startsWith("#") && (t.length() == 1 || !Character.isJavaIdentifierPart(t.charAt(1)));
    }

    /** Where what is written after a line wholly a comment begins: after its first line break (a decoded one ends the comment), else at its end. */
    private static int afterComment(String d) {
        for (int i = 0; i < d.length(); i++) if (lineBreak(d.charAt(i))) return i + 1;
        return d.length();
    }

    /**
     * The two ways a line, or a run of lines, is read, each with its white space of any kind made
     * one space: its code (see code), and the text as written (of a line wholly a comment, only
     * what a decoded line break in it leaves after the comment: mostly nothing). Both are read with
     * their Unicode escapes decoded (see decoded). One line cannot tell its quotes for certain (a
     * quote in a regular expression), so the code is never the only reading: what a line shows as
     * written is not hidden by the masking, and a switch's words inside a string or a trailing
     * comment are read too. The written reading decides what is sure (see judged). codeAt and
     * writtenAt say, for each character of a reading, where it stands in what was read (the
     * decoded line, or the decoded lines joined by line breaks), so a form one reading finds is
     * found in the other by where it starts.
     */
    private record Reading(String code, int[] codeAt, String written, int[] writtenAt) {}

    private static Reading reading(String line) {
        return readDecoded(decoded(line));
    }

    private static Reading readDecoded(String d) {
        int n = d.length();
        int[] raw = new int[n + 1], codeAt = new int[n + 1], writtenAt = new int[n + 1];
        String code = fold(code(d, raw), raw, codeAt);
        int from = commentLine(d) ? afterComment(d) : 0;
        int[] shifted = null;
        if (from > 0) {
            shifted = new int[n - from + 1];
            for (int i = 0; i < n - from; i++) shifted[i] = from + i;
        }
        return new Reading(code, codeAt, fold(from > 0 ? d.substring(from) : d, shifted, writtenAt), writtenAt);
    }

    /**
     * Two or three added lines, read together: their code, read across the line breaks (a block
     * comment that closes on the next line, a Python line that ends in a backslash), and the
     * lines as written, joined by a space (a line wholly a comment counts as empty, a backslash
     * that ends a line is dropped).
     */
    private static Reading joined(List<String> lines) {
        List<String> decodedLines = new ArrayList<>(lines.size());
        int size = lines.size() - 1;
        for (String l : lines) {
            String d = decoded(l);
            decodedLines.add(d);
            size += d.length();
        }
        StringBuilder all = new StringBuilder(size), written = new StringBuilder(size);
        int[] writtenRaw = new int[size + 1];
        for (int k = 0; k < decodedLines.size(); k++) {
            String d = decodedLines.get(k);
            if (k > 0) {
                writtenRaw[written.length()] = all.length();
                all.append('\n');
                written.append(' ');
            }
            int offset = all.length(), from = commentLine(d) ? afterComment(d) : 0, to = d.length();
            all.append(d);
            while (to > from && blank(d.charAt(to - 1))) to--;
            if (to > from && d.charAt(to - 1) == '\\') to--;
            for (int j = from; j < to; j++) {
                writtenRaw[written.length()] = offset + j;
                written.append(d.charAt(j));
            }
        }
        int[] raw = new int[size + 1], codeAt = new int[size + 1], writtenAt = new int[size + 1];
        String code = fold(code(all.toString(), raw), raw, codeAt);
        return new Reading(code, codeAt, fold(written, writtenRaw, writtenAt), writtenAt);
    }

    /**
     * A line with its Unicode escapes decoded, in one pass: a backslash, one u or more and four hex
     * digits (Java), or a backslash, u and one to six hex digits in braces (JavaScript). A decoded
     * line break is a line break (it ends a comment, see code).
     */
    static String decoded(String line) {
        if (line.indexOf("\\u") < 0) return line;
        int n = line.length();
        work += n;
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (c != '\\' || i + 1 >= n || line.charAt(i + 1) != 'u') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < n && line.charAt(j) == 'u') j++;
            if (j + 4 <= n && hex(line, j, j + 4)) {
                out.append((char) Integer.parseInt(line, j, j + 4, 16));
                i = j + 4;
                continue;
            }
            if (j == i + 2 && j < n && line.charAt(j) == '{') {
                int k = j + 1;
                while (k < n && k <= j + 6 && Character.digit(line.charAt(k), 16) >= 0) k++;
                if (k > j + 1 && k < n && line.charAt(k) == '}') {
                    int cp = Integer.parseInt(line, j + 1, k, 16);
                    if (Character.isValidCodePoint(cp)) {
                        out.appendCodePoint(cp);
                        i = k + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean hex(String s, int from, int to) {
        for (int i = from; i < to; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    /** White space of any kind a language allows between words: ASCII, a no-break space, U+FEFF, the line and paragraph separators, ... */
    private static boolean blank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF';
    }

    /** A text with each run of white space made one space, and none at its ends, in one pass. */
    private static String fold(CharSequence s) {
        return fold(s, null, null);
    }

    /**
     * The same; with at, at[k] says where the k-th character of the result stands: in[i] for the
     * i-th character of s (i itself without in). A run of white space stands where it starts.
     */
    private static String fold(CharSequence s, int[] in, int[] at) {
        int n = s.length();
        work += n;
        StringBuilder out = new StringBuilder(n);
        int space = -1; // where the run of white space before the next character starts, or -1
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (blank(c)) {
                if (space < 0) space = i;
                continue;
            }
            if (space >= 0 && out.length() > 0) {
                if (at != null) at[out.length()] = in == null ? space : in[space];
                out.append(' ');
            }
            space = -1;
            if (at != null) at[out.length()] = in == null ? i : in[i];
            out.append(c);
        }
        return out.toString();
    }

    /** The added lines longer than LONGEST, which the switch lint does not read. */
    public static int unlinted(List<String> addedLines) {
        int n = 0;
        for (String line : addedLines) if (line.length() > LONGEST) n++;
        return n;
    }

    /** What the lint found on the lines added to one file: the switches it is sure of (problems), and what it only notes. */
    public record Lint(List<String> sure, List<String> notes) {}

    /** The switches the lint is sure of (see lint). */
    public static List<String> lintedSwitches(List<String> addedLines) {
        return lint(addedLines).sure();
    }

    /**
     * The line lint over the lines a change adds to one file, in their order, as the change wrote
     * them. Each line is read alone, and each run of two or three added lines is read joined by a
     * space, for what only they hold together (it, a line break, .skip; @, a line break,
     * Disabled). A line longer than LONGEST is not read, nor joined (see unlinted). The lines are
     * taken as neighbours as the diff lists them.
     *
     * What it finds is said at one of two levels, each as the line, or the lines joined:
     *
     * Sure (a problem): what switches a test off or focuses one wherever it stands, its whole
     * meaning proven from the text as written (see judged). No pattern says sure by itself: each
     * sure form is proven by the readers of the gate, then placed by the gate itself (see sure),
     * and a form they cannot prove is a note:
     *
     *   form                                              proven by
     *   @Disabled, @Ignore (also qualified, with          annotation: arguments complete; the whole
     *     arguments)                                        annotation (javaEnds); declared
     *   @Test(enabled = false)                            annotation + element: a whole false
     *   @DisabledIf("true"), @EnabledIf("false"), also    annotation + annotationCondition: a whole
     *     value = / expression =                            string literal
     *   @pytest.mark.skip, @unittest.skip (bare or        decorator: the whole decorator (also in
     *     called)                                           parentheses), arguments complete; decorated
     *   pytestmark = pytest.mark.skip, alone or in a      markValue: the whole value or list element,
     *     list; pytest.param(..., marks=...)                the list complete; a statement or an element
     *   pytest skipif: a whole True among its             decorator/markValue + markLevel: every
     *     conditions, or none (bare too)                    condition read, pytest's rule for condition=
     *   pytest xfail(run=False), no condition or a True   markLevel: run a whole False
     *   unittest skipIf(True), skipUnless(False)          markLevel: a whole literal, by place or keyword
     *   it.skip('x', ...), describe.only, test.todo,      registering + title: the call, its first
     *     test.fixme, specify.skip, it.concurrent.skip      argument ended and a title; a statement
     *   it.skip.each(table)('x', ...), also `table`       registering: the table complete, then the
     *                                                       call with a title; a statement
     *   xit, xtest, xdescribe, xcontext, xspecify,        aliases + registering + title; a statement
     *     fdescribe, fit ('x', ...), also .each
     *   it.skipIf(true)('x'), describe.runIf(false)('x')  conditionalCall: arguments complete, a whole
     *                                                       literal; then registering + title; a statement
     *   test('x', { skip: true }, fn), only:, todo:       options + optionObject: a top-level
     *                                                       property, a whole true, nothing after it
     *                                                       that may change it, the whole argument;
     *                                                       a title first; a statement
     *   it('x'); a test without a body                    pending: one string literal, the whole line
     *                                                       the declaration; a statement
     *   assumeTrue(false), assumeFalse(true)              assumption: an argument that is a whole
     *                                                       literal and ended, the call closed and its
     *                                                       statement ended there; a statement
     *
     * The gate (see sure): declared, an annotation after nothing on its statement but complete
     * annotations and modifiers; decorated, a decorator after decorators only, from the start of
     * its reading; a statement, at the start of its statement (not an argument, a value or a
     * lambda's body), and held by nothing: no condition before it on its reading (Read.held), nor
     * an added line just before the reading that opens a block or a statement under a condition,
     * or ends where an expression goes on (see holdsNext); an element likewise, or an element of a
     * list. Annotations and decorators take no condition, so they are not held.
     *
     * A note: everything else where it sees a switch's words, and where it would need more than
     * the added lines hold to know that it is a switch, or that no condition holds it. A skip
     * from inside a test (pytest.skip, self.skipTest, raise SkipTest, this.skip(), t.Skip,
     * Jasmine's pending('x'), Playwright's test.skip() and test.skip(true, ...)), which a
     * condition on another line may hold; a modifier with a computed first argument (a
     * Playwright condition, test.skip(isMobile, REASON), or a computed title, it.skip(name, ...),
     * xit(name, ...)); a modifier that is referred to, not called (const later = it.skip, cond ?
     * test.skip.each : test.each); a condition that is computed (skipif(sys.platform == ...),
     * @Test(enabled = RUN), @DisabledIf("isWindows"), it.skipIf(isWindows)); an option whose value
     * is computed or that a spread or a quoted key after it may change; a mark or an annotation
     * inside a larger expression (@(pytest.mark.skip if IS_CI else identity), skip_slow =
     * pytest.mark.skip(...), "@Disabled" in a string); a sure form that is not a statement or a
     * declaration of its own (() -> assumeTrue(false), f(it.skip('x', fn)), x = @Disabled), or
     * after a condition on its reading (if (x) it.skip('x', ...)), or on the line after an added
     * line that holds it (if (!db) {, then describe.skip('db', ...): see holdsNext; a condition
     * further away is not seen); a form the code reading proves but the text as written does not
     * (a comment between its words: @, a comment, Disabled); Assumptions.abort and abort( called
     * alone; a call whose arguments end in none of its readings. Nothing: the same forms with the
     * literal that runs (skipif(False), test.skip(false, ...), @Test(enabled = true),
     * @DisabledIf("false"), a skip option inside another property), and an annotation's name
     * right after a name (an address: ci@Disabled.example).
     *
     * A call is judged where its arguments are: one whose arguments a line leaves open before
     * they say what it is (test.skip(, a line break, false,) is judged with the next line, then
     * with the next two, and is a note when they do not close it either. No line is said from a
     * part of a call.
     *
     * It finds the usual ways of writing a switch, to catch mistakes and plain attempts early. It
     * is not a guard: an author who sets out to hide a switch from a reader of text can (a switch
     * reached through another name, one of which the change wrote only a part, one over more than
     * three lines or on a line longer than LONGEST, a quote in a regular expression before a
     * comment between a switch's words). The run of the file says which tests that ran before
     * the change do not run now, whatever the source says.
     */
    public static Lint lint(List<String> addedLines) {
        int n = addedLines.size();
        List<String> sure = new ArrayList<>();
        TreeMap<Integer, String> notes = new TreeMap<>();
        boolean[] read = new boolean[n], told = new boolean[n], byLine = new boolean[n];
        int[] keys = new int[n], level = new int[n];
        byte[] before = new byte[n]; // whether the added line before line i holds it (see holdsNext): 0 not read yet, 1 no, 2 yes
        for (int i = 0; i < n; i++) {
            String line = addedLines.get(i);
            read[i] = line.length() <= LONGEST;
            if (!read[i]) continue;
            String d = decoded(line);
            keys[i] = keys(d);
            Verdict v = judged(readDecoded(d), keys[i], true, lineBefore(addedLines, read, before, i));
            level[i] = v.level();
            byLine[i] = v.lineBefore();
            if (level[i] == SURE) {
                told[i] = true;
                sure.add(line.trim());
            }
        }
        // a call a line leaves open is judged where its arguments end: with the next line, or the next two; a note when neither closes it
        for (int i = 0; i < n; i++) {
            if (level[i] != OPEN || told[i]) continue;
            int width = 1;
            Verdict judged = new Verdict(OPEN, false);
            for (int w = 2; w <= 3 && judged.level() == OPEN && fresh(read, told, i, w); w++) {
                judged = judged(addedLines, keys, i, w, lineBefore(addedLines, read, before, i));
                width = w;
            }
            if (judged.level() == OPEN) {
                width = 1;
                judged = new Verdict(NOTE, false);
            }
            told[i] = true;
            if (judged.level() == NONE) continue; // the call closes as no switch; the lines after it are read on their own
            for (int k = i; k < i + width; k++) told[k] = true;
            if (judged.level() == SURE) sure.add(shown(addedLines, i, width));
            else noted(notes, addedLines, i, width, judged.lineBefore());
        }
        // what only two or three lines hold together; a run whose call stays open is a note at the end, unless a wider run closes it
        int[] open = new int[n];
        for (int width = 2; width <= 3; width++) {
            for (int i = 0; i + width <= n; i++) {
                if (!fresh(read, told, i, width)) continue;
                int key = 0;
                boolean alone = false;
                for (int k = i; k < i + width; k++) {
                    key |= keys[k];
                    alone |= level[k] != NONE;
                }
                // every word a switch needs is on one line (a join puts a space between lines), so a run with none holds none
                if (key == 0) continue;
                Verdict judged = judged(addedLines, keys, i, width, lineBefore(addedLines, read, before, i));
                open[i] = judged.level() == OPEN ? width : 0;
                if (judged.level() == SURE) {
                    for (int k = i; k < i + width; k++) told[k] = true;
                    sure.add(shown(addedLines, i, width));
                } else if (judged.level() == NOTE && !alone) { // a line that is a note alone says it itself
                    for (int k = i; k < i + width; k++) told[k] = true;
                    noted(notes, addedLines, i, width, judged.lineBefore());
                }
            }
        }
        for (int width = 2; width <= 3; width++) {
            for (int i = 0; i < n; i++) {
                if (open[i] != width || !fresh(read, told, i, width)) continue;
                for (int k = i; k < i + width; k++) told[k] = true;
                notes.putIfAbsent(i, shown(addedLines, i, width));
            }
        }
        for (int i = 0; i < n; i++) if (level[i] == NOTE && !told[i]) noted(notes, addedLines, i, 1, byLine[i]);
        return new Lint(sure, new ArrayList<>(notes.values()));
    }

    /**
     * A note for the lines from i, width of them, said where they start; shown with the added line
     * before them when that line is what holds them (see holdsNext), which may be a note of its own.
     */
    private static void noted(TreeMap<Integer, String> notes, List<String> lines, int i, int width, boolean lineBefore) {
        notes.putIfAbsent(i, lineBefore && i > 0 ? shown(lines, i - 1, width + 1) : shown(lines, i, width));
    }

    /** How the added line before line i holds it (see holdsNext), read when the gate asks, once. */
    private static IntSupplier lineBefore(List<String> lines, boolean[] read, byte[] memo, int i) {
        return () -> {
            if (i == 0 || !read[i - 1]) return FREE;
            if (memo[i] == 0) memo[i] = (byte) (holdsNext(lines.get(i - 1)) + 1);
            return memo[i] - 1;
        };
    }

    /** How an added line holds the one after it (see holdsNext): not at all; as part of a list or a call; wholly. */
    private static final int FREE = 0, IN_BRACKETS = 1, HELD = 2;
    /** Words after which a line goes on: a condition, a value or an operand follows (else, return, and, ...). */
    private static final Set<String> ENDS_GOING_ON = Set.of("if", "elif", "else", "unless", "when", "while", "for", "do", "case", "return", "throw", "yield",
            "await", "typeof", "void", "new", "delete", "in", "of", "instanceof", "is", "and", "or", "not", "lambda");

    /**
     * How an added line holds the one after it. HELD when what follows may not run, or not as a
     * statement of its own: its code opens a block or a statement under a condition (it holds a
     * condition and ends with {, ) or :: if (ci) {, except ImportError:), or it ends where an
     * expression goes on: an operator (?, &&, ||, =, =>, ->, +, ...), a backslash, or a word such
     * as else, return or and. IN_BRACKETS when it ends with an opening bracket or a comma: what
     * follows is an argument or an element (a pytest.param may be one; a statement is not).
     */
    private static int holdsNext(String line) {
        String c = fold(code(decoded(line)));
        if (c.isEmpty()) return FREE;
        char last = c.charAt(c.length() - 1);
        if ((last == '{' || last == ')' || last == ':') && CONDITION.matcher(new Counted(c)).find()) return HELD;
        if (last == '(' || last == '[' || last == ',') return IN_BRACKETS;
        if ("?=&|+-*/%!<>^~.\\".indexOf(last) >= 0) return HELD;
        int w = c.length();
        while (w > 0 && word(c.charAt(w - 1))) w--;
        return w < c.length() && ENDS_GOING_ON.contains(c.substring(w)) ? HELD : FREE;
    }

    /** True when the lines from i, width of them, are read and not yet said. */
    private static boolean fresh(boolean[] read, boolean[] told, int i, int width) {
        if (i + width > read.length) return false;
        for (int k = i; k < i + width; k++) if (!read[k] || told[k]) return false;
        return true;
    }

    private static String shown(List<String> lines, int i, int width) {
        return String.join(" ", lines.subList(i, i + width).stream().map(String::trim).toList());
    }

    /** What the lint makes of a reading: its level, and whether the added line before it is what keeps it from being sure. */
    private record Verdict(int level, boolean lineBefore) {}

    private static final Verdict NOTHING_FOUND = new Verdict(NONE, false), FOUND_SURE = new Verdict(SURE, false);

    /** What the lint makes of the lines from i, width of them, read together (see joined). */
    private static Verdict judged(List<String> lines, int[] keys, int i, int width, IntSupplier lineBefore) {
        int key = 0;
        for (int k = i; k < i + width; k++) key |= keys[k];
        return judged(joined(lines.subList(i, i + width)), key, false, lineBefore);
    }

    /**
     * What the lint makes of a reading of one line or of lines read together (see Reading). The
     * written reading decides: a form is sure only when the gate proves it from the text as
     * written. The code reading (strings emptied, comments dropped) lost text, so it never makes a
     * form sure; it adds what only it sees (a switch's words with a comment between them:
     * @, a comment, Disabled; a skip from inside a test), as a note at most. Each form it judges is found
     * in the written reading by its kind and where it starts: where the written reading judges the
     * same form, its level stands (@DisabledIf("false") is nothing, though the code reading
     * cannot read the emptied string), and a form the code reading proves sure but the written
     * reading does not is a note (test('x', { skip: true, 'skip': false }, fn): the emptied
     * 'skip' hid the override). single is false for lines read together, where a pending test (a
     * whole declaration on one line) is not looked for. lineBefore: see sure.
     */
    private static Verdict judged(Reading r, int keys, boolean single, IntSupplier lineBefore) {
        if (keys == 0 && !single) return NOTHING_FOUND;
        Read w = judge(r.written(), keys, false, single, lineBefore);
        if (w.level == SURE) return FOUND_SURE;
        Read c = judge(r.code(), keys, true, single, lineBefore);
        int level = w.level;
        if (!c.forms.isEmpty()) {
            Map<Long, Integer> twins = new HashMap<>();
            for (int[] f : w.forms) twins.merge(place(f[0], r.writtenAt()[f[1]]), f[2], Math::max);
            for (int[] f : c.forms) {
                Integer twin = twins.get(place(f[0], r.codeAt()[f[1]]));
                if (twin == null) level = Math.max(level, f[2] == SURE ? NOTE : f[2]); // only the code reading sees it
                else if (f[2] == SURE && twin < SURE) level = Math.max(level, NOTE);
            }
        }
        return level == NONE ? NOTHING_FOUND : new Verdict(level, level == NOTE && (w.heldByLineBefore || c.heldByLineBefore));
    }

    /** A form's kind and where it starts in what was read, as one key. */
    private static long place(int kind, int at) {
        return (long) kind << 32 | at;
    }

    /** What one reading holds, each form said through the gate (Read.form); keys are its line's (see keys): each kind of switch is looked for only where its word stands. */
    private static Read judge(String text, int keys, boolean code, boolean single, IntSupplier lineBefore) {
        Read r = new Read(new Counted(text), lineBefore);
        if (single) pending(r);
        if (keys == 0) return r;
        if (r.level < SURE && (keys & ANNOTATED) != 0) annotations(r);
        if (r.level < SURE && (keys & ALIASED) != 0) aliases(r);
        if (r.level < SURE && (keys & MODIFIER) != 0) options(r);
        if (r.level < SURE && (keys & MODIFIER) != 0) scriptSwitch(r);
        if (r.level < SURE && (keys & (SKIP | XFAIL)) != 0) python(r);
        if (r.level < SURE && (keys & ASSUMED) != 0) assumption(r);
        // the rest are notes: a skip from inside a test (read in the code only), abort
        if (r.level == NONE && code && (keys & SKIP) != 0) {
            Matcher b = BODY_SKIP.matcher(r.s);
            if (b.find()) r.form(K_BODY, b.start(), NOTE, AS_STATEMENT);
        }
        if (r.level == NONE && (keys & ABORTED) != 0) {
            Matcher a = ABORTS.matcher(r.s);
            List<int[]> bare = a.find() ? null : bareAborts(r.s, r.budget, true);
            if (bare == null) r.form(K_ABORT, a.start(), NOTE, AS_STATEMENT);
            else if (!bare.isEmpty()) r.form(K_ABORT, bare.get(0)[0], aborted(bare), AS_STATEMENT);
        }
        return r;
    }

    // ---------------- the gate: what proves a switch sure ----------------
    //
    // No pattern says "sure" by itself. A pattern finds where a switch's words stand; the readers below then prove, from the
    // reading alone, the whole meaning those words claim, or the find is a note (or nothing, where the reading shows that it
    // switches nothing). Each form a reader judges is said through Read.form, and only the gate there (see sure) can let it
    // be sure: it reads where the form stands. The readers, each bounded (see Budget):
    //   arguments    a bracketed list, read to its closing bracket, strings skipped; an argument counts where it ends, before
    //                a comma of the list or at its closing bracket. A form that needs the whole list is OPEN until it closes.
    //   whole        a literal (true, false, True, False, "true") that is the whole argument, nothing else in it
    //   title        a whole argument that is a string for certain (see title)
    //   optionObject the top-level properties of an object that is a whole argument of a test declaration
    //   registering  the call that registers a test after a modifier: (title, ...), or .each(table)(title, ...)
    //   ends         what follows a decorator, an annotation or an assumption: the end of its expression or statement
    // and in the gate:
    //   starts       where each token of the reading stands: at a statement's start, after annotations and modifiers only, ...
    //   held         a condition (if, else, ?, &&, ||, ...) before the form on its reading
    //   holdsNext    the added line before the reading's first line opens a condition, or an expression the form goes on

    /** The kinds of form the readers judge, so that a form of one reading is found in the other (see judged). */
    private static final int K_PENDING = 1, K_ANNOTATION = 2, K_ALIAS = 3, K_OPTIONS = 4, K_CONDITIONAL = 5, K_MODIFIER = 6, K_DECORATOR = 7,
            K_PYTESTMARK = 8, K_PARAM = 9, K_MARK = 10, K_ASSUME = 11, K_BODY = 12, K_ABORT = 13;
    /** Where a form must stand to be sure (see sure). */
    private static final int AS_STATEMENT = 0, AS_ANNOTATION = 1, AS_DECORATOR = 2, AS_ELEMENT = 3;
    /** What may stand before a token on its reading (see starts): nothing on its statement; annotations and modifiers; decorators from the start. */
    private static final int AT_STATEMENT = 1, AT_DECLARATION = 2, AT_DECORATOR = 4;
    private static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static", "final", "abstract", "default", "synchronized");

    /** One reading being judged: its text, the budget of its calls, the forms judged on it and the most any of them holds. */
    private static final class Read {
        final CharSequence s;
        final Budget budget;
        private final IntSupplier lineBefore;
        /** Each form judged on this reading: {its kind, where it starts, its level}. */
        final List<int[]> forms = new ArrayList<>(2);
        int level = NONE;
        /** True when the gate kept a form from being sure only for the added line before (see sure). */
        boolean heldByLineBefore;
        private int condition = -2;
        private byte[] starts;

        Read(CharSequence s, IntSupplier lineBefore) {
            this.s = s;
            this.lineBefore = lineBefore;
            budget = new Budget(s.length());
        }

        /** True when a condition (if, else, a ternary, &&, ||, ...) stands before at on the reading: what follows it may not run. */
        boolean held(int at) {
            if (condition == -2) {
                Matcher c = CONDITION.matcher(s);
                condition = c.find() ? c.start() : -1;
            }
            return condition >= 0 && condition < at;
        }

        /** True when the token at at stands where what says (AT_STATEMENT, ...: see starts). */
        boolean startsAt(int at, int what) {
            if (starts == null) starts = starts(s);
            return (starts[at] & what) != 0;
        }

        /** What a reader made of the form of this kind at at: said here, and sure only when the gate says so (see sure). */
        void form(int kind, int at, int level, int stands) {
            if (level == SURE) level = sure(this, at, stands);
            forms.add(new int[] {kind, at, level});
            this.level = Math.max(this.level, level);
        }
    }

    /**
     * The gate: the one place where a form becomes sure. A reader has proven what the form's
     * words mean, read whole (they switch); the gate reads where the form stands, and lets it be
     * sure only where such a form stands on its own:
     *   an annotation  after nothing on its statement but complete annotations and modifiers
     *                  (@Test @Disabled, public @Disabled; not f(@Disabled), x = @Disabled, a
     *                  word or a quote before it);
     *   a decorator    at the start of its reading, after complete decorators only;
     *   a statement    (a declaration, an option, an assumption, pytestmark =, a pending test) at
     *                  the start of its statement: the reading's start, or after ;, {, } or the
     *                  end of a comment, nothing between (not after =, (, ->, =>, return, a word
     *                  or a quote: an argument, a value, a deferred body);
     *   an element     (pytest.param with marks=) at the start of its statement, or an element of
     *                  a list or a group that is no call's argument list;
     * and, a statement and an element, held by nothing: no condition before it on its reading
     * (Read.held), and the added line before the reading's first one does not hold it (see
     * holdsNext). Annotations and decorators take no condition: Java has no conditional
     * annotation, and a Python function defined under a condition is skipped wherever it is
     * defined. Anything else is a note.
     */
    private static int sure(Read r, int at, int stands) {
        if (stands == AS_ANNOTATION) return r.startsAt(at, AT_DECLARATION) ? SURE : NOTE;
        if (stands == AS_DECORATOR) return r.startsAt(at, AT_DECORATOR) ? SURE : NOTE;
        if (!(stands == AS_STATEMENT ? r.startsAt(at, AT_STATEMENT) : element(r, at)) || r.held(at)) return NOTE;
        int before = r.lineBefore.getAsInt();
        if (before == HELD || before == IN_BRACKETS && stands == AS_STATEMENT) {
            r.heldByLineBefore = true;
            return NOTE;
        }
        return SURE;
    }

    /**
     * Where each token of a reading stands (see AT_STATEMENT), in one forward pass: AT_STATEMENT
     * when nothing stands before it on its statement (the reading's start, or ;, {, } or the end
     * of a comment before it), AT_DECLARATION when only complete annotations and modifiers do,
     * AT_DECORATOR when only complete annotations do, from the reading's start. Any other token
     * (a word, a quote, an operator) ends all three until the next ;, { or }. Strings are not
     * skipped: a quote is a token like any other, since a reading cannot tell every quote for
     * certain. Linear: each character is passed once, and the arguments of the annotations it
     * reads are bounded by a budget of its own.
     */
    private static byte[] starts(CharSequence s) {
        int n = s.length();
        byte[] out = new byte[n + 1];
        Budget budget = new Budget(n);
        int state = AT_STATEMENT | AT_DECLARATION | AT_DECORATOR;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (blank(c)) {
                i++;
                continue;
            }
            out[i] = (byte) state;
            char next = i + 1 < n ? s.charAt(i + 1) : '\0';
            if (c == ';' || c == '{' || c == '}') {
                state = AT_STATEMENT | AT_DECLARATION;
                i++;
            } else if (c == '*' && next == '/') {
                state = AT_STATEMENT | AT_DECLARATION;
                i += 2;
            } else if (c == '@') {
                int end = (state & (AT_DECLARATION | AT_DECORATOR)) != 0 ? annotationEnd(s, i, budget) : -1;
                if (end > i) {
                    state &= AT_DECLARATION | AT_DECORATOR;
                    i = end;
                } else {
                    state = 0;
                    i++;
                }
            } else if (Character.isJavaIdentifierStart(c)) {
                int w = wordEnd(s, i);
                state = (state & AT_DECLARATION) != 0 && MODIFIERS.contains(s.subSequence(i, w).toString()) ? AT_DECLARATION : 0;
                i = w;
            } else {
                state = 0;
                i++;
            }
        }
        return out;
    }

    /**
     * The index after the complete annotation or decorator whose @ is at at: a name, qualified or
     * not, with an argument list that closes if one follows (@Test, @Tag("a"), @pytest.mark.skip(...)),
     * or a group (@(...)); -1 when it is not complete on the reading.
     */
    private static int annotationEnd(CharSequence s, int at, Budget budget) {
        int n = s.length(), i = blanks(s, at + 1);
        if (i < n && s.charAt(i) == '(') {
            Arguments group = arguments(s, i, budget);
            return group.closed() ? group.end() : -1;
        }
        if (i >= n || !Character.isJavaIdentifierStart(s.charAt(i))) return -1;
        int w = wordEnd(s, i);
        while (true) {
            int j = blanks(s, w);
            if (j < n && s.charAt(j) == '.') {
                int k = blanks(s, j + 1), v = wordEnd(s, k);
                if (v == k) return -1;
                w = v;
            } else if (j < n && s.charAt(j) == '(') {
                Arguments a = arguments(s, j, budget);
                return a.closed() ? a.end() : -1;
            } else {
                return w;
            }
        }
    }

    /** An element of a list (pytest.param in a parametrize list): at its statement's start, after [ or a comma, or in a group that is no call's. */
    private static boolean element(Read r, int at) {
        if (r.startsAt(at, AT_STATEMENT)) return true;
        CharSequence s = r.s;
        int p = at - 1;
        while (p >= 0 && blank(s.charAt(p))) p--;
        if (p < 0) return true;
        char c = s.charAt(p);
        if (c == '[' || c == ',') return true;
        if (c != '(') return false;
        int q = p - 1;
        while (q >= 0 && blank(s.charAt(q))) q--;
        return q < 0 || !word(s.charAt(q)) && s.charAt(q) != ')' && s.charAt(q) != ']';
    }

    /** Where a call's qualified name starts (Assumptions.assumeTrue, org.junit.Assume.assumeTrue), its name starting at at. */
    private static int qualified(CharSequence s, int at) {
        int start = at;
        while (true) {
            int p = start - 1;
            while (p >= 0 && blank(s.charAt(p))) p--;
            if (p < 0 || s.charAt(p) != '.') return start;
            p--;
            while (p >= 0 && blank(s.charAt(p))) p--;
            int q = p;
            while (q >= 0 && word(s.charAt(q))) q--;
            if (q == p) return start;
            start = q + 1;
        }
    }

    /** True when a statement ends at at: the reading ends, or ;, } or a comment follows. */
    private static boolean endsStatement(CharSequence s, int at) {
        int i = blanks(s, at);
        if (i >= s.length()) return true;
        char c = s.charAt(i), next = i + 1 < s.length() ? s.charAt(i + 1) : ' ';
        return c == ';' || c == '}' || c == '#' || c == '/' && (next == '/' || next == '*');
    }

    private static int blanks(CharSequence s, int i) {
        while (i < s.length() && blank(s.charAt(i))) i++;
        return i;
    }

    private static int wordEnd(CharSequence s, int i) {
        while (i < s.length() && Character.isJavaIdentifierPart(s.charAt(i))) i++;
        return i;
    }

    /** True when the word that starts at i is this one, and ends there. */
    private static boolean wordAt(CharSequence s, int i, String word) {
        int end = wordEnd(s, i);
        return end - i == word.length() && s.subSequence(i, end).toString().equals(word);
    }

    /** SURE when the argument is the literal that switches, NONE when it is the literal that does not, else (computed) NOTE. */
    private static int whole(String argument, String switches, String runs) {
        return argument.equals(switches) ? SURE : argument.equals(runs) ? NONE : NOTE;
    }

    /**
     * A test declared without a body, its whole declaration the reading of one line: it, test or
     * specify called with one string literal (a template without ${...}) and nothing after its
     * closing parenthesis but a semicolon (it('pending');). Mocha and Vitest report it as pending.
     */
    private static void pending(Read r) {
        Matcher m = PENDING.matcher(r.s);
        if (!m.lookingAt()) return;
        Arguments a = arguments(r.s, m.end() - 1, r.budget);
        int i = a.closed() ? blanks(r.s, a.end()) : 0;
        if (i < r.s.length() && r.s.charAt(i) == ';') i = blanks(r.s, i + 1);
        boolean whole = a.closed() && i >= r.s.length() && a.list().size() == 1 && literal(a.list().get(0));
        r.form(K_PENDING, 0, whole ? SURE : NONE, AS_STATEMENT);
    }

    /** A string literal and nothing else: '...', "..." or a template without ${...}. */
    private static boolean literal(String arg) {
        if (arg.isEmpty() || "'\"`".indexOf(arg.charAt(0)) < 0) return false;
        work += arg.length();
        return stringEnd(arg, 0) == arg.length() && (arg.charAt(0) != '`' || !arg.contains("${"));
    }

    /**
     * it.skip, test.only and their kin (test.describe.skip, it.concurrent.skip, it?.skip), and
     * Vitest's it.skipIf(...) and runIf(...): judged by the call that registers a test after them
     * (see registered, conditionalCall).
     */
    private static void scriptSwitch(Read r) {
        for (Matcher m = CONDITIONAL_CALL.matcher(r.s); r.level < SURE && m.find();) r.form(K_CONDITIONAL, m.start(), conditionalCall(r, m), AS_STATEMENT);
        for (Matcher m = SCRIPT_SWITCH.matcher(r.s); r.level < SURE && m.find();) {
            String modifier = m.group(1);
            r.form(K_MODIFIER, m.start(), registered(r, m.end(), modifier.equals("skip") || modifier.equals("fixme"), true, true), AS_STATEMENT);
        }
    }

    /**
     * Vitest's it.skipIf(condition) and runIf(condition), whose parenthesis ends m: sure when the
     * condition is the whole literal that skips (true for skipIf, false for runIf) and a test is
     * registered with it (see registered); nothing when it is the literal that runs; a note when
     * it is computed; OPEN while its parentheses do not close.
     */
    private static int conditionalCall(Read r, Matcher m) {
        Arguments a = arguments(r.s, m.end() - 1, r.budget);
        if (!a.closed()) return OPEN;
        boolean skipIf = m.group(1).equals("skipIf");
        int level = whole(a.list().size() == 1 ? a.list().get(0) : "", skipIf ? "true" : "false", skipIf ? "false" : "true");
        return level == SURE ? registered(r, a.end(), false, true, true) : level;
    }

    /**
     * xit, xtest, xdescribe, xcontext, xspecify, fdescribe, fit: judged as a modifier is (see
     * registered); one that is not called is nothing (an import names it). fit is called by other
     * functions too (fit(data)): only a call of it with a title says anything.
     */
    private static void aliases(Read r) {
        for (Matcher m = ALIAS.matcher(r.s); r.level < SURE && m.find();) {
            boolean fit = m.group().equals("fit");
            r.form(K_ALIAS, m.start(), registered(r, m.end(), false, false, !fit), AS_STATEMENT);
        }
    }

    /**
     * A modifier whose words end at after, judged by the call that registers a test after it (see
     * registering). Sure (for the gate to place): called with a title for its first argument (see
     * title), directly or after .each. OPEN
     * while the reading ends before the first argument does. A skip or a fixme called directly with
     * a plain false or a number is nothing (Playwright's test.skip(false, ...)). Anything else is a
     * note (a first argument that is computed: a Playwright condition or a computed title; a
     * modifier that is referred to, not called), or nothing where referenced or computed says so.
     */
    private static int registered(Read r, int after, boolean skips, boolean referenced, boolean computed) {
        Call call = registering(r, after);
        if (call.args() == null) return call.level() == NOTE && !referenced ? NONE : call.level();
        Arguments a = call.args();
        if (a.list().isEmpty()) return !a.closed() ? OPEN : computed ? NOTE : NONE; // test.skip(): a skip from inside a test
        if (a.ended() == 0) return OPEN; // its first argument may go on
        String first = a.list().get(0);
        if (title(first)) return SURE;
        if (skips && !call.each() && (first.equals("false") || NUMBER.matcher(first).matches())) return NONE;
        return computed ? NOTE : NONE;
    }

    /** The call that registers a test after a modifier: its arguments, or (args null) OPEN or NOTE; each when it follows .each(...). */
    private record Call(int level, Arguments args, boolean each) {}

    /**
     * The call after a modifier whose words end at after: ( directly, or after members (.each with
     * its table, a call or a tagged template, and then the call). OPEN when the reading ends first;
     * NOTE when no call follows (the modifier is referred to: const later = it.skip, cond ?
     * test.skip.each : test.each).
     */
    private static Call registering(Read r, int after) {
        CharSequence s = r.s;
        int n = s.length(), i = blanks(s, after);
        for (int hops = 0; hops <= 8; hops++) {
            if (i >= n) return new Call(OPEN, null, false);
            if (s.charAt(i) == '(') return new Call(NONE, arguments(s, i, r.budget), false);
            if (s.charAt(i) != '.') return new Call(NOTE, null, false);
            i = blanks(s, i + 1);
            int w = wordEnd(s, i);
            if (w == i) return new Call(i >= n ? OPEN : NOTE, null, false);
            boolean each = w - i == 4 && s.subSequence(i, w).toString().equals("each");
            i = blanks(s, w);
            if (!each) continue;
            if (i >= n) return new Call(OPEN, null, true);
            int table;
            if (s.charAt(i) == '(') {
                Arguments rows = arguments(s, i, r.budget);
                if (!rows.closed()) return new Call(OPEN, null, true);
                table = rows.end();
            } else if (s.charAt(i) == '`') {
                int end = closingQuote(s, i, Math.min(n, i + SCAN), r.budget);
                if (end < 0) return new Call(OPEN, null, true);
                table = end + 1;
            } else {
                return new Call(NOTE, null, true);
            }
            i = blanks(s, table);
            if (i >= n) return new Call(OPEN, null, true);
            return s.charAt(i) == '(' ? new Call(NONE, arguments(s, i, r.budget), true) : new Call(NOTE, null, true);
        }
        return new Call(NOTE, null, false);
    }

    /**
     * A title: a whole argument whose value is a string for certain. It begins with a string
     * literal (a template too, whose ${...} close before it does), and is that literal alone or
     * followed by + and more operands, each a string literal, a name or a member, called or
     * indexed or not, a number, or a group in parentheses, and no other operator. + binds tighter
     * than any comparison, logical operator or ternary, and with a string on its left it makes a
     * string, so the whole is a string (it.skip('adds ' + n, ...)): a test's title, never
     * Playwright's condition. Anything else (test.skip('a' + b === c, ...), 'x' || y, a spread, a
     * string that does not end in the argument) is not a title.
     */
    private static boolean title(String arg) {
        int n = arg.length();
        work += n;
        int i = 0;
        for (boolean first = true;; first = false) {
            if (i >= n) return false;
            char c = arg.charAt(i);
            if (c == '\'' || c == '"' || c == '`') i = stringEnd(arg, i);
            else if (first) return false;
            else if (c == '(') i = groupEnd(arg, i);
            else if (Character.isJavaIdentifierStart(c) || Character.isDigit(c)) i = chainEnd(arg, i);
            else return false;
            if (i < 0) return false;
            i = blanks(arg, i);
            if (i >= n) return true;
            if (arg.charAt(i) != '+' || i + 1 < n && (arg.charAt(i + 1) == '+' || arg.charAt(i + 1) == '=')) return false;
            i = blanks(arg, i + 1);
        }
    }

    /** The index after the string that starts at i (a template's ${...} must close before the template does), or -1. */
    private static int stringEnd(String t, int i) {
        char quote = t.charAt(i);
        int depth = 0; // braces open in a template's ${...}
        for (int k = i + 1; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '\\') {
                k++;
            } else if (quote == '`' && c == '$' && k + 1 < t.length() && t.charAt(k + 1) == '{') {
                depth++;
                k++;
            } else if (depth > 0 && c == '{') {
                depth++;
            } else if (depth > 0 && c == '}') {
                depth--;
            } else if (c == quote) {
                return depth == 0 ? k + 1 : -1; // a backtick inside ${...}: a template in a template, not read
            }
        }
        return -1;
    }

    /** The index after the bracket that closes the one at i, strings skipped, or -1. */
    private static int groupEnd(String t, int i) {
        int depth = 0;
        for (int k = i; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '\'' || c == '"' || c == '`') {
                int end = stringEnd(t, k);
                if (end < 0) return -1;
                k = end - 1;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '}') && --depth == 0) {
                return k + 1;
            }
        }
        return -1;
    }

    /** The index after a name or a member chain at i (a.b?.c), with the calls and indexes on it, or -1. */
    private static int chainEnd(String t, int i) {
        while (i < t.length()) {
            char c = t.charAt(i);
            if (Character.isJavaIdentifierPart(c) || c == '.') {
                i++;
            } else if (c == '?' && i + 1 < t.length() && t.charAt(i + 1) == '.') {
                i += 2;
            } else if (c == '(' || c == '[') {
                i = groupEnd(t, i);
                if (i < 0) return -1;
            } else {
                break;
            }
        }
        return i;
    }

    /**
     * A test declaration (test('x', { skip: true }, fn), Vitest) whose option object switches (see
     * optionObject): sure (for the gate to place) when its first argument is a title; a note
     * otherwise. Only a reading that holds skip:, only: or todo: is read.
     */
    private static void options(Read r) {
        if (!OPTION_KEY.matcher(r.s).find()) return;
        for (Matcher m = DECLARATION.matcher(r.s); r.level < SURE && m.find();) {
            Arguments a = arguments(r.s, m.end() - 1, r.budget);
            int found = NONE;
            for (int k = 1; k < a.list().size() && found < SURE; k++) {
                if (a.list().get(k).startsWith("{")) found = Math.max(found, optionObject(r, a.starts().get(k)));
            }
            if (found == SURE && !title(a.list().get(0))) found = NOTE;
            r.form(K_OPTIONS, m.start(), found, AS_STATEMENT);
        }
    }

    /**
     * The object at at, an argument of a test declaration. Sure when one of its top-level
     * properties says skip, only or todo with a whole true, nothing after it in the object can
     * change that (a spread, a property it cannot read: a computed key, a method, a getter; a later
     * one of the same name is read for its value), and the object is the whole argument (a comma
     * or the declaration's closing parenthesis follows it). A note when such a property's value is
     * computed or may be changed, or the object stands in more than the argument. Nothing when no
     * top-level property says so (a skip: true inside another property is metadata). OPEN while
     * the reading ends inside the object or before its argument ends.
     */
    private static int optionObject(Read r, int at) {
        Arguments props = arguments(r.s, at, r.budget);
        if (!props.closed()) return OPEN;
        int[] said = new int[OPTIONS.size()]; // for each option: 0 not said, 1 a whole true, 2 a whole false, 3 computed or may be changed
        for (String p : props.list()) {
            if (p.isEmpty()) continue;
            Matcher key = PROPERTY.matcher(p);
            if (p.startsWith("...")) {
                for (int k = 0; k < said.length; k++) if (said[k] != 0) said[k] = 3;
            } else if (key.lookingAt()) {
                String name = key.group(1) != null ? key.group(1) : key.group(2) != null ? key.group(2) : key.group(3);
                int k = OPTIONS.indexOf(name);
                String value = p.substring(key.end()).strip();
                if (k >= 0) said[k] = value.equals("true") ? 1 : value.equals("false") ? 2 : 3;
            } else if (p.matches("[\\w$]+")) {
                int k = OPTIONS.indexOf(p); // shorthand: { skip }
                if (k >= 0) said[k] = 3;
            } else {
                for (int k = 0; k < said.length; k++) if (said[k] == 1) said[k] = 3;
            }
        }
        int level = NONE;
        for (int v : said) level = Math.max(level, v == 1 ? SURE : v == 3 ? NOTE : NONE);
        if (level == NONE) return NONE;
        int next = blanks(r.s, props.end());
        if (next >= r.s.length()) return OPEN;
        return r.s.charAt(next) == ',' || r.s.charAt(next) == ')' ? level : NOTE;
    }

    /**
     * Java's annotations: @Disabled and @Ignore (sure); TestNG's @Test(enabled = false);
     * @DisabledIf("true") and @EnabledIf("false"), by place or as value = or expression = (sure
     * when that element is the whole literal that switches, nothing when it is the other, a note
     * when it is computed or a method's name). Each sure only with its arguments complete and as
     * the whole annotation: after it the end of the reading, another annotation, a declaration or
     * a comment; before it what the gate allows (complete annotations and modifiers: see sure).
     * Nothing when a name stands right before its @ (an address: ci@Disabled.example).
     */
    private static void annotations(Read r) {
        for (Matcher m = ANNOTATION.matcher(r.s); r.level < SURE && m.find();) r.form(K_ANNOTATION, m.start(), annotation(r, m), AS_ANNOTATION);
    }

    private static int annotation(Read r, Matcher m) {
        CharSequence s = r.s;
        if (m.start() > 0 && word(s.charAt(m.start() - 1))) return NONE;
        int i = blanks(s, m.end()), end = m.end();
        Arguments a = null;
        if (i < s.length() && s.charAt(i) == '(') {
            a = arguments(s, i, r.budget);
            if (!a.closed()) return OPEN;
            end = a.end();
        }
        int level = switch (m.group(1)) {
            case "Disabled", "Ignore" -> SURE;
            case "Test" -> a == null ? NONE : element(a, "enabled", "false", "true");
            default -> a == null ? NONE : annotationCondition(a, m.group(1).equals("DisabledIf"));
        };
        if (level != SURE) return level;
        return javaEnds(s, end) ? SURE : NOTE;
    }

    /** The element of this name among an annotation's arguments, judged by its value (see whole); NONE when there is none. */
    private static int element(Arguments a, String name, String switches, String runs) {
        for (String arg : a.list()) {
            Matcher e = ELEMENT.matcher(arg);
            if (e.lookingAt() && e.group(1).equals(name)) return whole(arg.substring(e.end()).strip(), switches, runs);
        }
        return NONE;
    }

    /** The condition of @DisabledIf / @EnabledIf: its one argument by place, or value = or expression =, a whole string literal. */
    private static int annotationCondition(Arguments a, boolean disabledIf) {
        String condition = null;
        for (String arg : a.list()) {
            Matcher e = ELEMENT.matcher(arg);
            if (!e.lookingAt()) condition = arg;
            else if (e.group(1).equals("value") || e.group(1).equals("expression")) condition = arg.substring(e.end()).strip();
        }
        if (condition == null) return NONE;
        Matcher literal = STRING_LITERAL.matcher(condition);
        if (!literal.matches()) return NOTE;
        return whole(literal.group(1), disabledIf ? "true" : "false", disabledIf ? "false" : "true");
    }

    /** Words that go on an expression, not a declaration that an annotation is on. */
    private static final Set<String> GOES_ON_WORDS = Set.of("if", "else", "and", "or", "instanceof");

    /**
     * True when a Java annotation's expression ends at at: the reading ends, or another annotation,
     * a declaration (a word, but one that goes on an expression: if, or, ...) or a comment follows.
     */
    private static boolean javaEnds(CharSequence s, int at) {
        int i = blanks(s, at);
        if (i >= s.length()) return true;
        char c = s.charAt(i), next = i + 1 < s.length() ? s.charAt(i + 1) : ' ';
        if (Character.isJavaIdentifierStart(c)) return !GOES_ON_WORDS.contains(s.subSequence(i, wordEnd(s, i)).toString());
        return c == '@' || c == '/' && (next == '/' || next == '*') || c == '*' && next == '/';
    }

    /** What follows an expression: it ends, it goes on (it is part of more), or the reading ends where it may go on. */
    private static final int ENDS = 0, GOES_ON = 1, CUT = 2;
    /** Where a mark stands: a decorator, the value of a statement (pytestmark = ...), an argument of a call (marks=...). */
    private static final int DECORATOR = 0, STATEMENT = 1, ARGUMENT = 2;

    /**
     * pytest's marks and unittest's decorators where they apply to tests: a decorator (also in
     * parentheses), pytestmark = and the marks= of pytest.param (alone, or as an element of a
     * list or a tuple). Sure (for the gate to place) when the mark switches (see markLevel) and is
     * the whole decorator or the whole value or element (see decorator, markValue, param). A mark
     * anywhere else (kept in a variable, passed to another call, inside a larger expression:
     * @(pytest.mark.skip if IS_CI else identity)) is a note when it would switch, nothing when it
     * would not.
     */
    private static void python(Read r) {
        Set<Integer> judged = new java.util.HashSet<>(); // the marks a decorator or a value has judged, by where they start
        for (Matcher c = PY_CONTEXT.matcher(r.s); r.level < SURE && c.find();) {
            if (c.group().equals("@")) r.form(K_DECORATOR, c.start(), decorator(r, c.end(), judged), AS_DECORATOR);
            else r.form(K_PYTESTMARK, c.start(), markValue(r, c.end(), false, judged), AS_STATEMENT);
        }
        for (Matcher p = PARAM.matcher(r.s); r.level < SURE && p.find();) r.form(K_PARAM, p.start(), param(r, p, judged), AS_ELEMENT);
        for (Matcher m = PY_MARK.matcher(r.s); r.level < SURE && m.find();) {
            if (judged.contains(m.start())) continue;
            int alone = mark(r, m)[0];
            r.form(K_MARK, m.start(), alone == SURE ? NOTE : alone, AS_STATEMENT);
        }
    }

    /** pytest.param(..., marks=...), whose parenthesis ends p: the most its marks= holds (see markValue); NONE without marks=. */
    private static int param(Read r, Matcher p, Set<Integer> judged) {
        Arguments a = arguments(r.s, p.end() - 1, r.budget);
        int level = NONE;
        for (int k = 0; k < a.list().size(); k++) {
            Matcher keyword = KEYWORD.matcher(a.list().get(k));
            if (keyword.lookingAt() && keyword.group(1).equals("marks")) level = Math.max(level, markValue(r, a.starts().get(k) + keyword.end(), true, judged));
        }
        return level;
    }

    /** A decorator whose @ ends at after: the level of the mark it is, as a whole (see python); NONE when it is no mark of these. */
    private static int decorator(Read r, int after, Set<Integer> judged) {
        CharSequence s = r.s;
        int i = blanks(s, after), parens = 0;
        while (i < s.length() && s.charAt(i) == '(') {
            parens++;
            i = blanks(s, i + 1);
        }
        Matcher m = PY_MARK.matcher(s).region(i, s.length());
        if (!m.lookingAt()) return NONE;
        judged.add(m.start());
        int[] mark = mark(r, m);
        if (mark[0] == OPEN || mark[0] == NONE) return mark[0];
        int end = mark[1];
        for (int k = 0; k < parens; k++) {
            end = blanks(s, end);
            if (end >= s.length()) return OPEN;
            if (s.charAt(end) != ')') return NOTE;
            end++;
        }
        int ends = pythonEnds(s, end, DECORATOR);
        return ends == ENDS ? mark[0] : ends == CUT ? OPEN : NOTE;
    }

    /**
     * The value of pytestmark = or (argument) the marks= of pytest.param, whose = ends at after:
     * its marks as whole values or elements (see python).
     */
    private static int markValue(Read r, int after, boolean argument, Set<Integer> judged) {
        CharSequence s = r.s;
        int i = blanks(s, after);
        if (i >= s.length()) return NONE;
        int level, end;
        if (s.charAt(i) == '[' || s.charAt(i) == '(') {
            Arguments list = arguments(s, i, r.budget);
            level = NONE;
            for (int k = 0; k < list.list().size(); k++) {
                int at = list.starts().get(k);
                Matcher m = PY_MARK.matcher(s).region(at, s.length());
                if (!m.lookingAt() || m.group(1) == null) continue;
                judged.add(at);
                int[] mark = mark(r, m);
                boolean whole = mark[0] != OPEN && blanks(s, mark[1]) >= at + list.list().get(k).length();
                level = Math.max(level, mark[0] == OPEN ? OPEN : whole || mark[0] == NONE ? mark[0] : NOTE);
            }
            if (level == NONE) return NONE;
            if (!list.closed()) return OPEN;
            end = list.end();
        } else {
            Matcher m = PY_MARK.matcher(s).region(i, s.length());
            if (!m.lookingAt() || m.group(1) == null) return NONE;
            judged.add(i);
            int[] mark = mark(r, m);
            if (mark[0] == OPEN || mark[0] == NONE) return mark[0];
            level = mark[0];
            end = mark[1];
        }
        if (level == OPEN) return OPEN;
        int ends = pythonEnds(s, end, argument ? ARGUMENT : STATEMENT);
        if (ends != ENDS) return ends == CUT ? OPEN : NOTE;
        return level;
    }

    /**
     * What follows a mark where it stands (see DECORATOR), at at. A decorator and a statement end
     * with their line: at the end of the reading or a comment; a decorator also before (lines read
     * together) the next decorator or the declaration it decorates. A value ends at a comma, a
     * semicolon or a closing bracket; an argument stands inside its call's parentheses, where the
     * line may go on, so the end of the reading or a comment is CUT for it, as a backslash that
     * ends the reading is for all. Anything else (if, or, a call on it) GOES_ON: the mark is part
     * of more.
     */
    private static int pythonEnds(CharSequence s, int at, int where) {
        int i = blanks(s, at);
        if (i >= s.length()) return where == ARGUMENT ? CUT : ENDS;
        char c = s.charAt(i);
        if (c == '\\' && blanks(s, i + 1) >= s.length()) return CUT;
        if (c == '#') return where == ARGUMENT ? CUT : ENDS;
        if (where == DECORATOR) return c == '@' || wordAt(s, i, "def") || wordAt(s, i, "async") || wordAt(s, i, "class") ? ENDS : GOES_ON;
        return c == ',' || c == ';' || c == ')' || c == ']' ? ENDS : GOES_ON;
    }

    /** A mark found by m (see PY_MARK), with its arguments if a call follows: {its level (see markLevel), the index after it}. */
    private static int[] mark(Read r, Matcher m) {
        int i = blanks(r.s, m.end());
        Arguments a = null;
        int end = m.end();
        if (i < r.s.length() && r.s.charAt(i) == '(') {
            a = arguments(r.s, i, r.budget);
            if (!a.closed()) return new int[] {OPEN, a.end()};
            end = a.end();
        }
        boolean unittest = m.group(2) != null;
        return new int[] {markLevel(unittest ? m.group(2) : m.group(1), unittest, a), end};
    }

    /**
     * What a mark does with its arguments (complete, or none: applied bare). pytest's skip and
     * unittest's skip: they switch. pytest's skipif and xfail follow pytest's own reading
     * (_pytest/skipping.py, evaluate_skip_marks and evaluate_xfail_marks, as of pytest 9): when
     * condition= is given it is the only condition and the positional ones are not read; otherwise
     * every positional condition is, and any that holds applies the mark; with no condition at all
     * it applies. So sure when a condition is a whole True, or there is none; nothing when every
     * condition is a whole False; else a note. xfail switches only with run=False, a whole False
     * (run computed: a note; run left out or True: nothing). unittest's skipIf and skipUnless take
     * one condition, by place or as condition=: sure with a whole True (skipIf) or False
     * (skipUnless). Arguments of *args or **kwargs leave the conditions unread: a note.
     */
    private static int markLevel(String name, boolean unittest, Arguments a) {
        if (name.equals("skip")) return SURE;
        List<String> positional = new ArrayList<>();
        Map<String, String> keyword = new HashMap<>();
        for (String arg : a == null ? List.<String>of() : a.list()) {
            if (arg.isEmpty()) continue;
            if (arg.startsWith("*")) return NOTE;
            Matcher k = KEYWORD.matcher(arg);
            if (k.lookingAt()) keyword.put(k.group(1), arg.substring(k.end()).strip());
            else positional.add(arg);
        }
        if (unittest) {
            String condition = !positional.isEmpty() ? positional.get(0) : keyword.get("condition");
            boolean skipIf = name.equals("skipIf");
            return condition == null ? NONE : whole(condition, skipIf ? "True" : "False", skipIf ? "False" : "True");
        }
        List<String> conditions = keyword.containsKey("condition") ? List.of(keyword.get("condition")) : positional;
        int level = conditions.isEmpty() ? SURE : NONE;
        for (String condition : conditions) level = Math.max(level, whole(condition, "True", "False"));
        if (name.equals("xfail")) {
            String run = keyword.get("run");
            level = Math.min(level, run == null ? NONE : whole(run, "False", "True"));
        }
        return level;
    }

    /**
     * The arguments of a call, each stripped, with where each starts; whether its closing bracket
     * was reached; the index after it, or after where the reading stopped. An argument has ended
     * before a comma of the list, and the last one when the list closed.
     */
    private record Arguments(List<String> list, List<Integer> starts, boolean closed, int end) {
        int ended() {
            return closed ? list.size() : Math.max(0, list.size() - 1);
        }
    }

    /**
     * The arguments of the list whose bracket ((, [ or {) is at open, told apart by their
     * parentheses, brackets and braces, and strings, read at most SCAN characters far, and while
     * the budget lasts: a list whose end is further has its last argument cut there. No argument
     * is an empty list.
     */
    private static Arguments arguments(CharSequence s, int open, Budget budget) {
        char closer = switch (s.charAt(open)) {
            case '[' -> ']';
            case '{' -> '}';
            default -> ')';
        };
        List<String> out = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        int depth = 0, from = open + 1, stop = Math.min(s.length(), open + 1 + SCAN);
        for (int i = open + 1; i < stop; i++) {
            if (!budget.spend()) {
                stop = i;
                break;
            }
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int end = closingQuote(s, i, stop, budget);
                if (end < 0) break;
                i = end;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                depth--;
            } else if (depth == 0 && (c == ',' || c == closer)) {
                String arg = s.subSequence(from, i).toString().strip();
                if (c == ',' || !arg.isEmpty() || !out.isEmpty()) {
                    out.add(arg);
                    starts.add(blanks(s, from));
                }
                from = i + 1;
                if (c == closer) return new Arguments(out, starts, true, i + 1);
            }
        }
        String last = s.subSequence(Math.min(from, stop), stop).toString().strip();
        if (last.isEmpty() && out.isEmpty()) return new Arguments(List.of(), List.of(), false, stop);
        out.add(last);
        starts.add(blanks(s, Math.min(from, stop)));
        return new Arguments(out, starts, false, stop);
    }

    /** The index of the quote that closes the string starting at from, before limit and while the budget lasts, or -1. */
    private static int closingQuote(CharSequence t, int from, int limit, Budget budget) {
        char quote = t.charAt(from);
        for (int i = from + 1; i < limit; i++) {
            if (!budget.spend()) return -1;
            char c = t.charAt(i);
            if (c == '\\') i++;
            else if (c == quote) return i;
        }
        return -1;
    }

    /**
     * The index of the parenthesis that closes the one at open, counting parentheses and skipping
     * strings, at most SCAN characters on and while the budget lasts; -1 when it is not within reach.
     */
    private static int closingParenthesis(CharSequence s, int open, Budget budget) {
        int depth = 0;
        for (int i = open, stop = Math.min(s.length(), open + SCAN); i < stop; i++) {
            if (!budget.spend()) return -1;
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int end = closingQuote(s, i, stop, budget);
                if (end < 0) return -1;
                i = end;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Where abort( is called alone, as a statically imported Assumptions.abort is: not after a dot
     * (controller.abort(), x?.abort()), a # or a name (a declaration: function abort(), void
     * abort(), def abort(); but return abort(...) is a call), and not followed by a body or a
     * return type (abort() { ... }, abort(): void { ... }, a method declared in a class, an object
     * or an interface; but x ? abort() : y is a call). A note, never sure: whether it is the abort
     * that skips a test is not in the text. Each as [start, end, 1 when its parentheses close in the
     * reading, else 0]; with first, only the first.
     */
    private static List<int[]> bareAborts(CharSequence s, Budget budget, boolean first) {
        List<int[]> out = new ArrayList<>();
        Matcher m = ABORT.matcher(s);
        while (m.find()) {
            int p = m.start() - 1;
            while (p >= 0 && Character.isWhitespace(s.charAt(p))) p--;
            char before = p >= 0 ? s.charAt(p) : ' ';
            if (before == '.' || before == '#' || before == '>' || before == '@') continue;
            if (p >= 0 && word(before)) {
                int q = p;
                while (q >= 0 && word(s.charAt(q))) q--;
                if (!CALLED_AFTER.contains(s.subSequence(q + 1, p + 1).toString())) continue;
            }
            int close = closingParenthesis(s, m.end() - 1, budget);
            if (close >= 0) {
                int k = close + 1;
                while (k < s.length() && Character.isWhitespace(s.charAt(k))) k++;
                if (k < s.length() && (s.charAt(k) == '{' || s.charAt(k) == ':' && before != '?')) continue;
            }
            out.add(new int[] {m.start(), close >= 0 ? close + 1 : s.length(), close >= 0 ? 1 : 0});
            if (first) break;
        }
        return out;
    }

    /** A note for abort( called alone; OPEN while its parentheses do not close in the reading (a declaration may follow them). */
    private static int aborted(List<int[]> aborts) {
        return aborts.isEmpty() ? NONE : aborts.get(0)[2] == 1 ? NOTE : OPEN;
    }

    private static boolean word(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /**
     * assumeTrue with a plain false as an argument of its own, or assumeFalse with a plain true,
     * whatever the other arguments are (a message, a lambda, String.format(...)), as a statement
     * of its own: the call, qualified or not (Assumptions., Assume.), starts its statement (see
     * sure: not after =, (, ->, =>, return: a deferred body or an argument, as in
     * assertThrows(X.class, () -> assumeTrue(false))) and ends it (;, }, a comment or the
     * reading's end after its closing parenthesis): sure then, for the gate to place, else a
     * note. An argument counts where it ends, and only a whole literal counts (assumeTrue(false ||
     * x) is computed: nothing, as any computed assumption); OPEN while the call is not closed.
     */
    private static void assumption(Read r) {
        Matcher m = ASSUME.matcher(r.s);
        while (r.level < SURE && m.find()) {
            Arguments a = arguments(r.s, m.end() - 1, r.budget);
            String never = m.group(1).equals("True") ? "false" : "true";
            boolean holds = false;
            for (int k = 0; k < a.ended() && !holds; k++) holds = a.list().get(k).equals(never);
            int level = !a.closed() ? OPEN : !holds ? NONE : endsStatement(r.s, a.end()) ? SURE : NOTE;
            r.form(K_ASSUME, qualified(r.s, m.start()), level, AS_STATEMENT);
        }
    }

    /**
     * Where an assumption never holds, as withoutBuiltInSwitches takes it out: assumeTrue with an
     * argument that is a plain false, or assumeFalse with one that is a plain true, a condition
     * before it or not. A call whose end is not within reach has its last argument cut there.
     * Each as [start, end).
     */
    private static List<int[]> assumptions(CharSequence s, Budget budget) {
        List<int[]> out = new ArrayList<>();
        Matcher m = ASSUME.matcher(s);
        while (m.find()) {
            String never = m.group(1).equals("True") ? "false" : "true";
            Arguments a = arguments(s, m.end() - 1, budget);
            if (a.list().contains(never)) out.add(new int[] {m.start(), a.end()});
        }
        return out;
    }

    /** What withoutBuiltInSwitches takes out: the words of every switch the lint judges, those it only notes and conditional ones too. */
    private static final Pattern BUILT_IN = Pattern.compile(SCRIPT_SWITCH.pattern() + "|\\b[xf](?:it|test|describe|context|specify)\\b"
            + "|@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(?:Disabled\\w*|Ignore|EnabledIf\\w*)\\b|" + ABORTS.pattern()
            + "|\\bpytest\\s*\\.\\s*(?:mark\\s*\\.\\s*)?(?:skip\\w*|xfail)\\b|\\bunittest\\s*\\.\\s*skip\\w*|\\bself\\s*\\.\\s*skipTest\\b|\\bSkipTest\\b"
            + "|\\bthis\\s*\\.\\s*skip\\b|\\bt\\s*\\.\\s*Skip\\w*");

    /**
     * A line with the words of its built-in switches taken out. What a configured pattern still
     * finds in it is the repository's own (a helper of its own that skips, a comment directive)
     * and is judged by the line as written; the built-in ones, conditional ones included, are
     * judged by the line lint. A line longer than LONGEST, which the lint does not read, is kept
     * as it is: the configured patterns judge it.
     */
    public static String withoutBuiltInSwitches(String line) {
        if (line.length() > LONGEST) return line;
        List<int[]> spans = new ArrayList<>(assumptions(line, unbounded()));
        spans.addAll(bareAborts(line, unbounded(), false));
        spans.sort(Comparator.comparingInt((int[] x) -> x[0]).reversed());
        StringBuilder out = new StringBuilder(line);
        int limit = line.length();
        for (int[] x : spans) {
            if (x[1] > limit) continue; // within one already taken out
            out.replace(x[0], x[1], " ");
            limit = x[0];
        }
        return BUILT_IN.matcher(out.toString()).replaceAll(" ");
    }

    /** How a reported line is named: focused (only this test runs) or switched off. */
    private static final Pattern FOCUSED = Pattern.compile("\\b(?:it|test|describe|context|suite|specify)(?:" + DOT + "\\w+){0,8}" + DOT + "only\\b"
            + "|(?<![\\w$.])f(?:it|describe)(?=\\s*(?:\\(|\\.\\s*each\\b))|\\bonly['\"]?\\s*:\\s*true\\b");
    private static final Pattern JAVA_ASSERT = Pattern.compile(
            "\\b(?:assert|verify)\\w*\\s*\\(|\\b(?=\\w*?(?i:expect))\\w++\\s*\\(|\\.should\\w*\\s*\\(|\\bfail\\s*\\(|\\bassert\\s|\\bexpected\\s*=");
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

    // possessive: the white space before and after an optional % is not split two ways (<minimum>1, many spaces, then no </minimum>)
    private static final Pattern MINIMUM = Pattern.compile("<minimum>\\s*+([\\d.]++)\\s*+%?+\\s*+</minimum>|\\bminimum\\s*+=\\s*+([\\d.]++)");
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
     * The scan reads what the change wrote, so its work per line and per file is bounded. The
     * detectors other than the switch lint drop nothing for that: a long line is scanned in
     * overlapping pieces. The switch lint does not read an added line longer than LONGEST, and
     * says so (see switchedOff). Test files are read by the lines the change adds and removes,
     * never whole; only build and configuration files are read whole (a pom, package.json, a
     * coverage configuration), and one too large to read is reported.
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
        s.byPath(path -> PIPELINE.matcher(path).matches(), "pipeline changed");
        s.buildConfiguration();
        s.dependencies();
        s.lockFiles();
        s.byPath(Integrity::migration, "database migration changed");
        s.coverage();
        s.byPath(path -> STEERING.matcher(path).matches(), "agent or review instructions changed");
        List<Finding> all = new ArrayList<>(s.out);
        all.sort(Comparator.comparing(Finding::level));
        return all;
    }

    // ---------------- problems ----------------

    /** The built-in idea of a test file, and the repository's own (paths.test). */
    private static boolean anyTestFile(String path) {
        return testFile(path, sdlc.core.Ctx.cfg.list("paths.test"));
    }

    private void switchedOff() {
        // every test file alike: the lines the change adds, as it wrote them, are linted alone and two or three together; what
        // the lint is sure of is a problem, the rest a note; a line longer than the lint reads is not read, and the report says
        // how many were not
        for (String path : change.paths("AM")) {
            if (!anyTestFile(path)) continue;
            List<String> lines = whole.getOrDefault(path, List.of());
            Lint lint = lint(lines);
            for (String hit : lint.sure()) switchedOff(path, hit);
            // what the lint cannot know to be a switch, or to be unconditional, is said; the run of the file judges it
            for (String hit : lint.notes()) note(FOCUSED.matcher(hit).find() ? "may focus a test" : "may switch a test off", path, "the run of the file judges it: " + hit);
            int unread = unlinted(lines);
            if (unread > 0) {
                note("lines too long for the switch lint", path, unread + (unread == 1 ? " added line is" : " added lines are") + " longer than " + LONGEST
                        + " characters and " + (unread == 1 ? "was" : "were") + " not read for switches; the run of the file judges its tests");
            }
        }
        // the repository's own patterns may name more than the built-in lint knows; a line is read as the lint reads it, and the
        // built-in switches in it are the lint's to judge (as check-pr does)
        for (String hit : Checks.disabledTests(added)) {
            if (!seen(hitLine(hit), l -> Checks.containsDisabled(withoutBuiltInSwitches(l)))) continue;
            switchedOff(hitPath(hit), hitLine(hit));
        }
    }

    /**
     * The code of a text, one line or lines joined by line breaks, in one forward pass that
     * carries what it is in (a block comment, a template string, the ${...} of one) from line to
     * line: comments are dropped (a block comment becomes a space, so "@", a block comment and
     * "Disabled" read as "@ Disabled"), the inside of each string is emptied, and what a template
     * string computes (${...}) stays code. A ' or " string ends with its line: one that does not
     * close there keeps the rest of the line as written. A string in three quotes (a Java text
     * block, Python's ''' and """) runs to the same three quotes, over lines, and is emptied; one
     * that does not close runs to the text's end: what the reading cannot place is text, not
     * code. At the start of a line, a # begins a
     * comment (Python, a shell) and a * is the middle of a block comment (a doc comment whose
     * start the text does not hold), to the comment's end on that line or the line's end. A
     * backslash that ends a line (a Python line continued) is dropped. Line breaks (also U+2028,
     * U+2029) are kept. Each character is looked at a bounded number of times: no search goes
     * past the end it is given (the line's, for the end of a block comment begun on it), so the
     * work is linear in the text, however long (see work).
     * Reading text cannot tell every quote for certain (a quote in a regular expression), so this
     * is never the only reading of an added line (see reading).
     */
    static String code(String t) {
        return code(t, null);
    }

    /** The code of t (see above); with map, map[k] is where the k-th character of the code stands in t. */
    private static String code(String t, int[] map) {
        int n = t.length();
        work += n;
        StringBuilder out = new StringBuilder(n);
        // what the pass is in, innermost last: -1 the text of a template string, k >= 0 the code of a ${...} with k braces open
        int[] stack = new int[8];
        int top = 0;
        boolean lineStart = true;
        int i = 0;
        while (i < n) {
            char c = t.charAt(i);
            if (top > 0 && stack[top - 1] < 0) { // the text of a template string
                if (lineBreak(c)) put(out, map, '\n', i);
                if (c == '\\' && i + 1 < n && !lineBreak(t.charAt(i + 1))) i++;
                else if (c == '`') {
                    top--;
                    put(out, map, '`', i);
                } else if (c == '$' && i + 1 < n && t.charAt(i + 1) == '{') {
                    stack = push(stack, top++, 0);
                    put(out, map, ' ', i);
                    i++;
                }
                i++;
                continue;
            }
            if (lineBreak(c)) {
                put(out, map, '\n', i);
                lineStart = true;
                i++;
                continue;
            }
            if (lineStart && top == 0) {
                lineStart = false;
                int k = i;
                while (k < n && (t.charAt(k) == ' ' || t.charAt(k) == '\t')) k++;
                if (k < n && t.charAt(k) == '#') {
                    i = lineEnd(t, k);
                    continue;
                }
                if (k < n && t.charAt(k) == '*') {
                    int end = lineEnd(t, k), close = closing(t, k, end);
                    put(out, map, ' ', k);
                    i = close < 0 ? end : close + 2;
                    continue;
                }
            }
            lineStart = false;
            char next = i + 1 < n ? t.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                i = lineEnd(t, i);
            } else if (c == '/' && next == '*') {
                i = blockComment(t, i + 2, out, map);
            } else if (c == '\\' && lineBreak(next)) {
                i++;
            } else if ((c == '"' || c == '\'') && next == c && i + 2 < n && t.charAt(i + 2) == c) {
                i = tripleString(t, i, out, map);
            } else if (c == '"' || c == '\'') {
                i = string(t, i, out, map);
            } else if (c == '`') {
                stack = push(stack, top++, -1);
                put(out, map, '`', i);
                i++;
            } else if (top > 0 && c == '}' && stack[top - 1] == 0) {
                top--; // the end of a ${...}: back in the template's text
                put(out, map, ' ', i);
                i++;
            } else {
                if (top > 0 && c == '{') stack[top - 1]++;
                else if (top > 0 && c == '}') stack[top - 1]--;
                put(out, map, c, i);
                i++;
            }
        }
        return out.toString();
    }

    /** Appends c, which stands for the character of the source at at (map: see code). The code is never longer than its source. */
    private static void put(StringBuilder out, int[] map, char c, int at) {
        if (map != null) map[out.length()] = at;
        out.append(c);
    }

    private static int[] push(int[] stack, int at, int value) {
        int[] s = at < stack.length ? stack : java.util.Arrays.copyOf(stack, stack.length * 2);
        s[at] = value;
        return s;
    }

    private static boolean lineBreak(char c) {
        return c == '\n' || c == '\r' || c == 0x2028 || c == 0x2029; // also JavaScript's line and paragraph separators
    }

    /** The index of the line break at or after from, or the text's end. */
    private static int lineEnd(String t, int from) {
        int i = from;
        while (i < t.length() && !lineBreak(t.charAt(i))) i++;
        work += i - from;
        return i;
    }

    /** The index of the first star-slash that ends at or before to, looking from from forward and no further than to; -1 when none. */
    private static int closing(String t, int from, int to) {
        for (int i = from; i + 1 < to; i++) {
            work++;
            if (t.charAt(i) == '*' && t.charAt(i + 1) == '/') return i;
        }
        return -1;
    }

    /** A block comment whose inside starts at from: a space for it, its line breaks kept; the index after its end, or the text's end. */
    private static int blockComment(String t, int from, StringBuilder out, int[] map) {
        put(out, map, ' ', from - 2);
        for (int i = from; i < t.length(); i++) {
            work++;
            char c = t.charAt(i);
            if (c == '*' && i + 1 < t.length() && t.charAt(i + 1) == '/') return i + 2;
            if (lineBreak(c)) put(out, map, '\n', i);
        }
        return t.length();
    }

    /** A ' or " string that starts at from: emptied when it closes on its line, else the rest of the line as written. The index after it. */
    private static int string(String t, int from, StringBuilder out, int[] map) {
        char quote = t.charAt(from);
        int i = from + 1;
        while (i < t.length() && !lineBreak(t.charAt(i))) {
            work++;
            char c = t.charAt(i);
            if (c == quote) {
                put(out, map, quote, from);
                put(out, map, quote, i);
                return i + 1;
            }
            i += c == '\\' && i + 1 < t.length() && !lineBreak(t.charAt(i + 1)) ? 2 : 1;
        }
        for (int k = from; k < i; k++) put(out, map, t.charAt(k), k);
        return i;
    }

    /**
     * A string in three quotes that starts at from (a Java text block, Python's ''' or """): emptied
     * to two quotes, its line breaks kept, to the same three quotes or to the text's end. The
     * index after it.
     */
    private static int tripleString(String t, int from, StringBuilder out, int[] map) {
        char quote = t.charAt(from);
        put(out, map, quote, from);
        put(out, map, quote, from + 1);
        int i = from + 3;
        while (i < t.length()) {
            work++;
            char c = t.charAt(i);
            if (c == quote && i + 2 < t.length() && t.charAt(i + 1) == quote && t.charAt(i + 2) == quote) return i + 3;
            if (lineBreak(c)) put(out, map, '\n', i);
            i += c == '\\' && i + 1 < t.length() && !lineBreak(t.charAt(i + 1)) ? 2 : 1;
        }
        return i;
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

    // its segments are taken possessively: fewer of them would leave the reading before a dot or a name, never before a "(",
    // so giving one back finds nothing; a long chain (it.it.it...) is not tried again shorter from each place
    private static final Pattern TEST_CALL = Pattern.compile("\\b[xf]?(?:it|test|describe|context|suite)(?:" + DOT + "\\w++){0,8}+\\s*(?:\\?\\.\\s*)?\\(|^\\s*(?:async\\s+)?def\\s+test|\\bfunc\\s+Test"
            + "|@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    /** Source files, whose code can declare tests; the lines of any other file (a .json, a .txt fixture) are data. */
    private static final Pattern SOURCE = Pattern.compile(".*\\.(?:java|kt|kts|groovy|scala|[cm]?[jt]sx?|vue|svelte|py|go|rb|cs|php|rs|swift)");
    // A package or import line, read whole (matches) on a line of at most LONGEST characters. Every repetition is possessive:
    // what follows each one cannot start with what it takes (white space before a name, a name before ";", ";" before white
    // space), so giving back a character never finds a match, and the white space around ";" is not split two ways.
    private static final Pattern PACKAGE_LINE = Pattern.compile("\\s*+package\\s++[\\w.]++\\s*+;?+\\s*+");
    // a name's dot is part of the name unless ".*" follows it (import a.b.*): the lookahead reads the white space after one dot
    private static final Pattern IMPORT_LINE = Pattern.compile("\\s*+import\\s++(?:static\\s++)?+(?:\\w|\\.(?!\\s*+\\*))++(?:\\s*+\\.\\s*+\\*)?+\\s*+;?+\\s*+");
    private static final Pattern JVM = Pattern.compile(".*\\.(?:java|kt|groovy|scala)");

    /**
     * What the lines of a deleted test file say of the tests it declared: "code" when its code
     * calls it, test, describe or their kin, defines a test function (Python, Go), or carries a
     * JUnit test annotation; "text" when only the text does (a helper whose comment shows an
     * example, a fixture that holds sample source); null when none does. The lines a change
     * removes from a deleted file are all of its lines, in order, so its code is read as one text
     * (see code), carrying a comment or a string that runs over several lines from line to line:
     * an example in such a comment or template string declares nothing. Where that reading may
     * go wrong (a quote it cannot tell), what it hides is still "text": a note, not a problem. A
     * line longer than LONGEST is not read for a test call: when no line that was read declares
     * one, "unread" (a note, not a problem). A file that is not source (see SOURCE) declares
     * nothing. Which tests it held is not read.
     */
    private static String declaresTests(String path, List<String> lines) {
        if (!SOURCE.matcher(path).matches()) return null;
        boolean unread = false;
        // each line's characters, as the pattern reads them, are counted (see work)
        for (String line : code(String.join("\n", lines), null).split("\n", -1)) {
            if (line.length() > LONGEST) unread = true;
            else if (TEST_CALL.matcher(new Counted(line)).find()) return "code";
        }
        if (unread) return "unread";
        for (String line : lines) if (line.length() <= LONGEST && TEST_CALL.matcher(new Counted(line)).find()) return "text";
        return null;
    }

    /**
     * The lines that are not package lines (and, with imports, not import lines either). A line
     * longer than LONGEST is not read as either: it stays in the body, so two files are the same
     * but for their package and imports only when such lines are equal as written.
     */
    private static List<String> body(List<String> lines, boolean imports) {
        return lines.stream().filter(l -> l.length() > LONGEST || !PACKAGE_LINE.matcher(new Counted(l)).matches()
                && !(imports && IMPORT_LINE.matcher(new Counted(l)).matches())).toList();
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
        // each file the change adds is read once, by its lines and (a JVM source) by its body without its package and import
        // lines: a deleted file finds its twin by a lookup, so many deleted and many added files cost their lines, not their product
        Map<List<String>, String> byLines = new HashMap<>(), byPackage = new HashMap<>(), byImports = new HashMap<>();
        boolean any = false;
        for (String path : change.paths("D")) any |= anyTestFile(path);
        if (!any) return;
        for (String a : change.paths("A")) {
            List<String> lines = whole.getOrDefault(a, List.of());
            byLines.putIfAbsent(lines, a);
            if (!JVM.matcher(a).matches()) continue;
            byPackage.putIfAbsent(body(lines, false), a);
            byImports.putIfAbsent(body(lines, true), a);
        }
        for (String path : change.paths("D")) {
            if (!anyTestFile(path)) continue;
            List<String> was = wholeRemoved.getOrDefault(path, List.of());
            String twin = byLines.get(was);
            boolean jvm = JVM.matcher(path).matches();
            String repackaged = twin != null || !jvm ? null : byPackage.get(body(was, false));
            String reimported = twin != null || repackaged != null || !jvm ? null : byImports.get(body(was, true));
            String declared = declaresTests(path, was);
            if (twin != null) note("test file moved", path, "the same file is now at " + twin);
            else if (repackaged != null) note("test file moved", path, "the same file is now at " + repackaged + ", in another package");
            else if (reimported != null) note("test file moved and its imports changed", path, "the same tests are now at " + reimported + ", with other imports: its run judges it");
            else if (testNamed(path) || "code".equals(declared)) problem("test file deleted", path, "the file is gone");
            else if ("unread".equals(declared)) note("test support file deleted", path, "a line of it is longer than " + LONGEST + " characters and was not read; no line that was read declares a test");
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
            // one finding for the line, however many of its commands are made to pass: the line is shown once
            problem("test failures ignored by the build", path, "a failing test command still counts as success: " + line.trim().replaceAll("\\s+", " "));
            return;
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

    private void byPath(Predicate<String> kind, String what) {
        for (Change.File f : change.files) if (kind.test(f.path())) note(what, f.path(), word(f.status()));
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

    /** The matches of p on the lines, each line read in pieces of at most LONGEST characters (a call cut by a piece's end is not counted). */
    private static int count(Pattern p, List<String> lines) {
        int n = 0;
        for (String line : lines) {
            Matcher m = p.matcher(new Counted(line));
            for (int at = 0; at < line.length(); at += LONGEST) {
                m.region(at, Math.min(line.length(), at + LONGEST));
                while (m.find()) n++;
            }
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
        // [^<]* then trim, not \s*([^<]*?)\s*: the two would share a run of white space and try every way of splitting it
        Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static List<Double> numbers(Pattern p, List<String> lines) {
        List<Double> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = p.matcher(new Counted(line));
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
            Matcher m = SCRIPT_THRESHOLD.matcher(new Counted(line));
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
