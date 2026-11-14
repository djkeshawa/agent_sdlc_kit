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
 * the usual ways of writing a switch (lintedSwitches), each line no longer than LONGEST. That lint
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

    // The switch lint: the usual ways of writing a switch that skips a test, marks it as to do or focuses on one, looked for on
    // the lines a change adds to test files. A helper that catches mistakes and plain attempts early, in commands that run no
    // test; not a guard: an author who sets out to hide a switch from a reader of text can (see lintedSwitches). The run of a
    // test file (sdlc check) is the guard.
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
    /** Vitest's conditions with a plain literal that always skips: it.skipIf(true), describe.runIf(false). */
    private static final Pattern LITERAL_IF = Pattern.compile(
            "(?=[itdcs])\\b(?:it|test|describe|context|suite)(?:" + DOT + "\\w+){0,8}?" + DOT + "(?:skipIf\\s*\\(\\s*true|runIf\\s*\\(\\s*false)\\s*\\)");
    /**
     * xit, xtest, xdescribe, xcontext, xspecify, fdescribe, called or with .each; fit with a
     * title (fit('x', ...)), as fit(data) may be any function. Not another object's (model.fit(x))
     * nor a declaration (def fit(...)).
     */
    private static final Pattern ALIAS = Pattern.compile("(?=[xf])(?<![\\w$.])(?<!def )(?<!function )(?<!fun )"
            + "(?:(?:x(?:it|test|describe|context|specify)|fdescribe)(?=\\s*(?:\\(|\\.\\s*each\\b))|fit(?=\\s*(?:\\(\\s*['\"`]|\\.\\s*each\\b)))");
    private static final String TYPE_ARGUMENTS = "(?:<[^()<>]{0,200}>\\s*)?";
    /** @Disabled and @Ignore, also qualified; @DisabledIf("true") and @EnabledIf("false"), whose condition is a plain literal; TestNG's @Test(enabled = false). */
    private static final Pattern ANNOTATION = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(?:(?:Disabled|Ignore)\\b|Test\\s*\\([^()]{0,300}?\\benabled\\s*=\\s*false\\b"
            + "|DisabledIf\\s*\\(\\s*(?:(?:value|expression)\\s*=\\s*)?\"\\s*true\\s*\"|EnabledIf\\s*\\(\\s*(?:(?:value|expression)\\s*=\\s*)?\"\\s*false\\s*\")");
    /** Assumptions.abort (also Assumptions.<Void>abort), and the line that imports it statically. */
    private static final Pattern ABORTS = Pattern.compile("(?=A)\\bAssumptions\\s*\\.\\s*" + TYPE_ARGUMENTS + "abort\\b");
    /** A pytest mark where it applies to tests: a decorator, pytestmark = ..., marks=... Group 1: skip, skipif or xfail. */
    private static final Pattern PYTEST_MARK = Pattern.compile(
            "(?=[@pm])(?:@\\s*(?:\\(\\s*)?|\\b(?:pytestmark|marks)\\s*=\\s*(?:\\[[^\\]=]{0,200}?)?)pytest\\s*\\.\\s*mark\\s*\\.\\s*(skip|skipif|xfail)\\b");
    /** unittest's decorators. Group 1: skip, skipIf or skipUnless. */
    private static final Pattern UNITTEST_SKIP = Pattern.compile("@\\s*(?:\\(\\s*)?unittest\\s*\\.\\s*(skip|skipIf|skipUnless)\\b");
    /**
     * A skip from inside a test, as a statement of its own: pytest.skip(...), self.skipTest(...),
     * raise unittest.SkipTest(...), Mocha's this.skip() (no argument), Go's t.Skip, t.Skipf, t.SkipNow,
     * Jasmine's pending('reason').
     */
    private static final Pattern BODY_SKIP = Pattern.compile("(?:^|[;{}:])\\s*(?:(?:pytest\\s*\\.\\s*skip|self\\s*\\.\\s*skipTest|raise\\s+(?:unittest\\s*\\.\\s*)?SkipTest"
            + "|t\\s*\\.\\s*Skip(?:f|Now)?)\\s*\\(|this\\s*\\.\\s*skip\\s*\\(\\s*\\)|pending\\s*\\(\\s*(?:['\"`]|\\)))");
    /** What makes a skip from inside a test conditional, on its line or the one above it: if, else, a ternary, &&, || and their kin. */
    private static final Pattern CONDITION = Pattern.compile("(?=[iuewcf?&|])(?:\\b(?:if|unless|elif|else|when|except|catch|case|while|for)\\b|\\?(?![.?])|&&|\\|\\|)");
    /** A test declared without a body, on one line of its own (its code: strings emptied): it('pending'); Mocha and Vitest report it as pending. */
    private static final Pattern PENDING = Pattern.compile("^(?:it|test|specify)\\s*\\(\\s*(?:''|\"\"|``)\\s*\\)\\s*;?$");
    /** A test declaration, for its option object (Vitest: test('x', { skip: true }, fn)). */
    private static final Pattern DECLARATION = Pattern.compile("(?=[itdsc])\\b(?:it|test|describe|suite|context|specify)(?:" + DOT + "\\w+){0,3}?\\s*\\(");
    private static final Pattern OPTION = Pattern.compile("(?=[sot])\\b(?:skip|only|todo)\\s*:\\s*true\\b");
    private static final Pattern ABORT = Pattern.compile("(?=a)\\babort\\s*" + TYPE_ARGUMENTS + "\\(");
    private static final Pattern ASSUME = Pattern.compile("(?=a)\\bassume(True|False)\\s*\\(");
    /** A keyword argument of a Python call (reason="x"), not a comparison. */
    private static final Pattern KEYWORD = Pattern.compile("[A-Za-z_]\\w*\\s*=(?!=)");
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

    /** True when one line holds a switch (see lintedSwitches), read without the lines around it. */
    public static boolean lintedSwitch(String line) {
        if (line.length() > LONGEST) return false;
        String d = decoded(line);
        return switched(readDecoded(d), keys(d), "", true);
    }

    /** True when a line shows what the test finds, in one of its two readings (see reading). */
    static boolean seen(String line, Predicate<String> test) {
        Reading r = reading(line);
        return test.test(r.code()) || r.written() != null && test.test(r.written());
    }

    /** A line wholly a comment: it starts with // or with a # that no name follows (in JavaScript #name is code). */
    static boolean commentLine(String line) {
        String t = line.stripLeading();
        if (t.startsWith("//")) return true;
        return t.startsWith("#") && (t.length() == 1 || !Character.isJavaIdentifierPart(t.charAt(1)));
    }

    /**
     * The two ways a line, or a run of lines, is read, each with its white space of any kind made
     * one space: its code (see code), and the text as written, unless it is wholly a comment
     * (null then). Both are read with their Unicode escapes decoded (see decoded). One line cannot
     * tell its quotes for certain (a quote in a regular expression), so the code is never the only
     * reading: what a line shows as written is not hidden by the masking, and a switch's words
     * inside a string or a trailing comment are reported too.
     */
    private record Reading(String code, String written) {}

    private static Reading reading(String line) {
        return readDecoded(decoded(line));
    }

    private static Reading readDecoded(String d) {
        return new Reading(fold(code(d)), commentLine(d) ? null : fold(d));
    }

    /**
     * Two or three added lines, read together: their code, read across the line breaks (a block
     * comment that closes on the next line, a Python line that ends in a backslash), and the
     * lines as written, joined by a space (a line wholly a comment counts as empty, a backslash
     * that ends a line is dropped).
     */
    private static Reading joined(List<String> lines) {
        StringBuilder code = new StringBuilder(), written = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            String d = decoded(lines.get(k));
            if (k > 0) {
                code.append('\n');
                written.append(' ');
            }
            code.append(d);
            if (commentLine(d)) continue;
            String t = d.stripTrailing();
            written.append(t.endsWith("\\") ? t.substring(0, t.length() - 1) : t);
        }
        return new Reading(fold(code(code.toString())), fold(written));
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
        int n = s.length();
        work += n;
        StringBuilder out = new StringBuilder(n);
        boolean space = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (blank(c)) {
                space = true;
                continue;
            }
            if (space && out.length() > 0) out.append(' ');
            space = false;
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

    /**
     * The line lint over the lines a change adds to one file, in their order, as the change wrote
     * them: each line that holds a switch, as it is written, and each run of two or three added
     * lines that hold one only together (it, a line break, .skip; @, a line break, Disabled),
     * joined by a space. A line longer than LONGEST is not read, nor joined (see unlinted). The
     * lines are taken as neighbours as the diff lists them; lines of two places of the file that
     * only together read as a switch are reported too, which errs on the safe side.
     *
     * A skip from inside a test (pytest.skip, self.skipTest, this.skip(), t.Skip, Playwright's
     * test.skip()) is reported only where the added lines show it unconditional: not when its
     * line, or the added line above it, holds an if, an else, a ternary, && or ||. A skip with a
     * condition first (Playwright's test.skip(browserName === 'webkit', 'reason'), skipif,
     * skipUnless, @DisabledIf..., @EnabledIf...) is not a switch, unless its condition is a plain
     * true (test.skip(true, ...), skipif(True, ...)).
     *
     * It finds the usual ways of writing a switch, to catch mistakes and plain attempts early. It
     * is not a guard: an author who sets out to hide a switch from a reader of text can (a switch
     * reached through another name, one of which the change wrote only a part, one over more than
     * three lines or on a line longer than LONGEST, a quote in a regular expression before a
     * comment between a switch's words). The run of the file says which tests that ran before
     * the change do not run now, whatever the source says.
     */
    public static List<String> lintedSwitches(List<String> addedLines) {
        int n = addedLines.size();
        List<String> out = new ArrayList<>();
        boolean[] told = new boolean[n], read = new boolean[n];
        int[] keys = new int[n];
        String[] code = new String[n];
        for (int i = 0; i < n; i++) {
            String line = addedLines.get(i);
            read[i] = line.length() <= LONGEST;
            if (!read[i]) continue;
            String d = decoded(line);
            Reading r = readDecoded(d);
            code[i] = r.code();
            keys[i] = keys(d);
            if (switched(r, keys[i], above(code, i), true)) {
                told[i] = true;
                out.add(line.trim());
            }
        }
        for (int width = 2; width <= 3; width++) {
            for (int i = 0; i + width <= n; i++) {
                boolean fresh = true;
                int key = 0;
                for (int k = i; k < i + width; k++) {
                    fresh &= read[k] && !told[k];
                    key |= keys[k];
                }
                // every word a switch needs is on one line (a join puts a space between lines), so a run with none holds none
                if (!fresh || key == 0) continue;
                List<String> lines = addedLines.subList(i, i + width);
                if (!switched(joined(lines), key, above(code, i), false)) continue;
                for (int k = i; k < i + width; k++) told[k] = true;
                out.add(String.join(" ", lines.stream().map(String::trim).toList()));
            }
        }
        return out;
    }

    /** The code of the nearest added line above line i that has any (up to three lines up), for whether a skip under it is conditional. */
    private static String above(String[] code, int i) {
        for (int k = i - 1; k >= 0 && k >= i - 3; k--) if (code[k] != null && !code[k].isEmpty()) return code[k];
        return "";
    }

    /**
     * True when a reading holds a switch. above is the code of the added line above (see
     * lintedSwitches); single is false for lines read together, where a pending test (a whole
     * declaration on one line) is not looked for.
     */
    private static boolean switched(Reading r, int keys, String above, boolean single) {
        if (keys == 0 && !single) return false;
        // whether a skip from inside a test is conditional: read once, for every skip of the line
        boolean conditional = keys != 0 && (CONDITION.matcher(new Counted(r.code())).find() || CONDITION.matcher(new Counted(above)).find());
        return switched(r.code(), keys, true, conditional, single) || r.written() != null && switched(r.written(), keys, false, conditional, single);
    }

    /** True when one reading holds a switch; keys are its line's (see keys): each kind of switch is looked for only where its word stands. */
    private static boolean switched(String text, int keys, boolean code, boolean conditional, boolean single) {
        CharSequence s = new Counted(text);
        if (single && code && PENDING.matcher(s).lookingAt()) return true;
        if (keys == 0) return false;
        if (code && (keys & SKIP) != 0 && BODY_SKIP.matcher(s).find() && !conditional) return true;
        if ((keys & ANNOTATED) != 0 && ANNOTATION.matcher(s).find() || (keys & ABORTED) != 0 && ABORTS.matcher(s).find()
                || (keys & ALIASED) != 0 && ALIAS.matcher(s).find()) {
            return true;
        }
        Budget budget = new Budget(s.length());
        if ((keys & MODIFIER) != 0 && (scriptSwitch(s, conditional, budget) || options(s, budget))) return true;
        if ((keys & (SKIP | XFAIL)) != 0 && pythonSwitch(s, budget)) return true;
        return (keys & ABORTED) != 0 && !bareAborts(s, budget, true).isEmpty() || (keys & ASSUMED) != 0 && !assumptions(s, budget, true).isEmpty();
    }

    /**
     * it.skip, test.only and their kin. A skip or a fixme whose first argument is a title (a
     * string) is a switch, and so is one followed by another member (.each). One with no
     * arguments or a plain true skips from inside a test (Playwright's test.skip(),
     * test.skip(true, 'reason')), and one followed by nothing is a reference (const later =
     * it.skip): each a switch where it is unconditional (see lintedSwitches), not in an if or a
     * ternary (cond ? describe : describe.skip). One whose first argument is a condition, and
     * whose second is a description or absent, is a conditional skip (Playwright's
     * test.skip(browserName === 'webkit', 'reason')): not a switch. A function as the second
     * argument makes it a declaration with a computed title (it.skip(name, () => {})): a switch.
     * Vitest's it.skipIf(true) and runIf(false) are switches.
     */
    private static boolean scriptSwitch(CharSequence s, boolean conditional, Budget budget) {
        if (LITERAL_IF.matcher(s).find()) return true;
        Matcher m = SCRIPT_SWITCH.matcher(s);
        while (m.find()) {
            String modifier = m.group(1);
            if (modifier.equals("only") || modifier.equals("todo")) return true;
            int at = m.end();
            while (at < s.length() && blank(s.charAt(at))) at++;
            if (at < s.length() && s.charAt(at) == '.') return true;
            if (at >= s.length() || s.charAt(at) != '(') {
                if (!conditional) return true;
                continue;
            }
            Arguments a = arguments(s, at, budget);
            if (a.list().isEmpty() && !a.closed()) return true;
            String first = a.list().isEmpty() ? "" : a.list().get(0);
            if (a.list().isEmpty() || first.equals("true")) {
                if (!conditional) return true;
                continue;
            }
            if (first.isEmpty() && !a.closed() || literal(first)) return true;
            if (a.list().size() >= 2 && !a.list().get(1).isEmpty() && !literal(a.list().get(1))) return true;
        }
        return false;
    }

    private static boolean literal(String arg) {
        return !arg.isEmpty() && "'\"`".indexOf(arg.charAt(0)) >= 0;
    }

    /**
     * pytest's skip mark, applied to tests (a decorator, pytestmark, marks=); skipif with a plain
     * True; xfail with run=False and no condition. unittest's skip; skipIf(True; skipUnless(False.
     */
    private static boolean pythonSwitch(CharSequence s, Budget budget) {
        Matcher m = PYTEST_MARK.matcher(s);
        while (m.find()) {
            String mark = m.group(1);
            if (mark.equals("skip")) return true;
            Arguments a = argumentsAfter(s, m.end(), budget);
            if (a == null || a.list().isEmpty()) continue;
            String first = a.list().get(0).replace(" ", "");
            if (mark.equals("skipif") && (first.equals("True") || first.equals("condition=True"))) return true;
            if (mark.equals("xfail") && a.list().stream().anyMatch(x -> x.replace(" ", "").equals("run=False"))
                    && (first.equals("True") || KEYWORD.matcher(first).lookingAt())
                    && a.list().stream().map(x -> x.replace(" ", "")).noneMatch(x -> x.startsWith("condition=") && !x.equals("condition=True"))) {
                return true;
            }
        }
        Matcher u = UNITTEST_SKIP.matcher(s);
        while (u.find()) {
            String mark = u.group(1);
            if (mark.equals("skip")) return true;
            Arguments a = argumentsAfter(s, u.end(), budget);
            if (a == null || a.list().isEmpty()) continue;
            if (a.list().get(0).equals(mark.equals("skipIf") ? "True" : "False")) return true;
        }
        return false;
    }

    /** A test declaration whose option object, an argument after its title, says skip, only or todo: true (Vitest). */
    private static boolean options(CharSequence s, Budget budget) {
        if (!OPTION.matcher(s).find()) return false;
        Matcher m = DECLARATION.matcher(s);
        while (m.find()) {
            List<String> args = arguments(s, m.end() - 1, budget).list();
            for (int k = 1; k < args.size(); k++) if (args.get(k).startsWith("{") && OPTION.matcher(args.get(k)).find()) return true;
        }
        return false;
    }

    /** The arguments of a call, each stripped; whether its closing parenthesis was reached; the index after it, or after where the reading stopped. */
    private record Arguments(List<String> list, boolean closed, int end) {}

    /** The arguments of the call whose ( follows at (after white space), or null when none follows. */
    private static Arguments argumentsAfter(CharSequence s, int at, Budget budget) {
        while (at < s.length() && blank(s.charAt(at))) at++;
        return at < s.length() && s.charAt(at) == '(' ? arguments(s, at, budget) : null;
    }

    /**
     * The arguments of the call whose parenthesis is at open, told apart by their parentheses,
     * brackets and braces, and strings, read at most SCAN characters far, and while the budget
     * lasts: a call whose end is further has its last argument cut there. No argument is an
     * empty list.
     */
    private static Arguments arguments(CharSequence s, int open, Budget budget) {
        List<String> out = new ArrayList<>();
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
            } else if (depth == 0 && (c == ',' || c == ')')) {
                String arg = s.subSequence(from, i).toString().strip();
                if (c == ',' || !arg.isEmpty() || !out.isEmpty()) out.add(arg);
                from = i + 1;
                if (c == ')') return new Arguments(out, true, i + 1);
            }
        }
        out.add(s.subSequence(Math.min(from, stop), stop).toString().strip());
        return new Arguments(out.size() == 1 && out.get(0).isEmpty() ? List.of() : out, false, stop);
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
     * or an interface; but x ? abort() : y is a call). Each as [start, end); with first, only the
     * first.
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
            out.add(new int[] {m.start(), close >= 0 ? close + 1 : s.length()});
            if (first) break;
        }
        return out;
    }

    private static boolean word(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /**
     * Where an assumption never holds: assumeTrue with an argument that is a plain false, or
     * assumeFalse with one that is a plain true, whatever the other arguments are (a message, a
     * lambda, String.format(...)). A call whose end is not within reach has its last argument cut
     * there. Each as [start, end); with first, only the first.
     */
    private static List<int[]> assumptions(CharSequence s, Budget budget, boolean first) {
        List<int[]> out = new ArrayList<>();
        Matcher m = ASSUME.matcher(s);
        while (m.find()) {
            String never = m.group(1).equals("True") ? "false" : "true";
            Arguments a = arguments(s, m.end() - 1, budget);
            if (!a.list().contains(never)) continue;
            out.add(new int[] {m.start(), a.end()});
            if (first) break;
        }
        return out;
    }

    /** What withoutBuiltInSwitches takes out: the words of every switch the lint judges, conditional ones too (it judges them not switches). */
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
        List<int[]> spans = new ArrayList<>(assumptions(line, unbounded(), false));
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
            + "|(?<![\\w$.])f(?:it|describe)(?=\\s*(?:\\(|\\.\\s*each\\b))|\\bonly\\s*:\\s*true\\b");
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
        // every test file alike: the lines the change adds, as it wrote them, are linted alone and two or three together; a line
        // longer than the lint reads is not read, and the report says how many were not
        for (String path : change.paths("AM")) {
            if (!anyTestFile(path)) continue;
            List<String> lines = whole.getOrDefault(path, List.of());
            for (String hit : lintedSwitches(lines)) switchedOff(path, hit);
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
     * close there keeps the rest of the line as written. At the start of a line, a # begins a
     * comment (Python, a shell) and a * is the middle of a block comment (a doc comment whose
     * start the text does not hold), to the comment's end on that line or the line's end. A
     * backslash that ends a line (a Python line continued) is dropped. Line breaks (also U+2028,
     * U+2029) are kept.
     * Reading text cannot tell every quote for certain (a quote in a regular expression), so this
     * is never the only reading of an added line (see reading).
     */
    static String code(String t) {
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
                if (lineBreak(c)) out.append('\n');
                if (c == '\\' && i + 1 < n && !lineBreak(t.charAt(i + 1))) i++;
                else if (c == '`') {
                    top--;
                    out.append('`');
                } else if (c == '$' && i + 1 < n && t.charAt(i + 1) == '{') {
                    stack = push(stack, top++, 0);
                    out.append(' ');
                    i++;
                }
                i++;
                continue;
            }
            if (lineBreak(c)) {
                out.append('\n');
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
                    int end = lineEnd(t, k), close = indexOf(t, "*/", k, end);
                    out.append(' ');
                    i = close < 0 ? end : close + 2;
                    continue;
                }
            }
            lineStart = false;
            char next = i + 1 < n ? t.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                i = lineEnd(t, i);
            } else if (c == '/' && next == '*') {
                i = blockComment(t, i + 2, out);
            } else if (c == '\\' && lineBreak(next)) {
                i++;
            } else if (c == '"' || c == '\'') {
                i = string(t, i, out);
            } else if (c == '`') {
                stack = push(stack, top++, -1);
                out.append('`');
                i++;
            } else if (top > 0 && c == '}' && stack[top - 1] == 0) {
                top--; // the end of a ${...}: back in the template's text
                out.append(' ');
                i++;
            } else {
                if (top > 0 && c == '{') stack[top - 1]++;
                else if (top > 0 && c == '}') stack[top - 1]--;
                out.append(c);
                i++;
            }
        }
        return out.toString();
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
        return i;
    }

    private static int indexOf(String t, String what, int from, int to) {
        int at = t.indexOf(what, from);
        return at >= 0 && at + what.length() <= to ? at : -1;
    }

    /** A block comment whose inside starts at from: a space for it, its line breaks kept; the index after its end, or the text's end. */
    private static int blockComment(String t, int from, StringBuilder out) {
        out.append(' ');
        for (int i = from; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '*' && i + 1 < t.length() && t.charAt(i + 1) == '/') return i + 2;
            if (lineBreak(c)) out.append('\n');
        }
        return t.length();
    }

    /** A ' or " string that starts at from: emptied when it closes on its line, else the rest of the line as written. The index after it. */
    private static int string(String t, int from, StringBuilder out) {
        char quote = t.charAt(from);
        int i = from + 1;
        while (i < t.length() && !lineBreak(t.charAt(i))) {
            char c = t.charAt(i);
            if (c == quote) {
                out.append(quote).append(quote);
                return i + 1;
            }
            i += c == '\\' && i + 1 < t.length() && !lineBreak(t.charAt(i + 1)) ? 2 : 1;
        }
        out.append(t, from, i);
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

    private static final Pattern TEST_CALL = Pattern.compile("\\b[xf]?(?:it|test|describe|context|suite)(?:" + DOT + "\\w+){0,8}\\s*(?:\\?\\.\\s*)?\\(|^\\s*(?:async\\s+)?def\\s+test|\\bfunc\\s+Test"
            + "|@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    /** Source files, whose code can declare tests; the lines of any other file (a .json, a .txt fixture) are data. */
    private static final Pattern SOURCE = Pattern.compile(".*\\.(?:java|kt|kts|groovy|scala|[cm]?[jt]sx?|vue|svelte|py|go|rb|cs|php|rs|swift)");
    private static final Pattern PACKAGE_LINE = Pattern.compile("\\s*package\\s+[\\w.]+\\s*;?\\s*");
    private static final Pattern IMPORT_LINE = Pattern.compile("\\s*import\\s+(?:static\\s+)?[\\w.]+(?:\\s*\\.\\s*\\*)?\\s*;?\\s*");
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
     * file that is not source (see SOURCE) declares nothing. Which tests it held is not read.
     */
    private static String declaresTests(String path, List<String> lines) {
        if (!SOURCE.matcher(path).matches()) return null;
        for (String line : code(String.join("\n", lines)).split("\n", -1)) if (TEST_CALL.matcher(line).find()) return "code";
        for (String line : lines) if (TEST_CALL.matcher(line).find()) return "text";
        return null;
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
        // [^<]* then trim, not \s*([^<]*?)\s*: the two would share a run of white space and try every way of splitting it
        Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
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
