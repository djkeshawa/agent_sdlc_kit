package sdlc.check;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

public final class Integrity {
    private static final int MAX_DETAIL = 140;

    private static final Pattern TEST_FOLDER = Pattern.compile(".*/(?:__tests__|tests?)/.*");
    private static final Pattern TEST_NAME = Pattern.compile(
            "[^/]*\\.(?:test|spec)\\.[^/]+|[^/]*(?:Test|Tests|IT)\\.(?:java|kt|groovy|scala)|test_[^/]*\\.py|[^/]*_test\\.(?:py|go)");

    static boolean isTestFile(String path) {
        return TEST_FOLDER.matcher("/" + path).matches() || testNamed(path);
    }

    static boolean testNamed(String path) {
        return TEST_NAME.matcher(path.substring(path.lastIndexOf('/') + 1)).matches();
    }

    public static boolean testFile(String path, List<String> testPaths) {
        return isTestFile(path) || sdlc.util.Glob.any(testPaths, path);
    }

    private static boolean isJava(String path) {
        return path.endsWith(".java");
    }

    private static boolean isScript(String path) {
        return path.matches(".*\\.[cm]?[jt]sx?");
    }

    private static final Pattern PIPELINE = Pattern.compile(
            "\\.github/workflows/.*|(?:.*/)?Jenkinsfile[^/]*|\\.gitlab-ci\\.yml|(?:.*/)?azure-pipelines[^/]*\\.ya?ml|\\.circleci/.*|bitbucket-pipelines\\.yml|\\.buildkite/.*");
    private static final Pattern BUILD = Pattern.compile(
            "(?:.*/)?(?:pom\\.xml|build\\.gradle(?:\\.kts)?|settings\\.gradle(?:\\.kts)?|gradle\\.properties|tsconfig[^/]*\\.json|vitest\\.config\\.[^/]+|jest\\.config\\.[^/]+|Makefile|Dockerfile[^/]*)");
    private static final Pattern SCRIPT_FILE = Pattern.compile(".*\\.(?:sh|bash|ps1|cmd|bat)|(?:.*/)?\\.mvn/maven\\.config|(?:.*/)?vite\\.config\\.[^/]+");
    private static final Pattern MIGRATION = Pattern.compile("(?:.*/)?(?:db/migration|migrations|alembic/versions|db/changelog)/.*|.*\\.sql");
    private static final Pattern CHANGELOG_EXTENSION = Pattern.compile("\\.(?:xml|ya?ml|json)$");

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

    public static final int LONGEST = 2000;
    private static final int SCAN = 500;

    private static final String DOT = "\\s*\\??\\.\\s*";
    private static final Pattern SCRIPT_SWITCH = Pattern.compile(
            "(?=[itdcs])\\b(?:it|test|describe|context|suite|specify)(?:" + DOT + "\\w+){0,8}?" + DOT + "(skip|todo|only|fixme)\\b");
    private static final Pattern CONDITIONAL_CALL = Pattern.compile(
            "(?=[itdcs])\\b(?:it|test|describe|context|suite)(?:" + DOT + "\\w+){0,8}?" + DOT + "(skipIf|runIf)\\s*\\(");
    private static final Pattern ALIAS = Pattern.compile("(?=[xf])(?<![\\w$.])(?<!def )(?<!function )(?<!fun )"
            + "(?:x(?:it|test|describe|context|specify)|fdescribe|fit)(?![\\w$])");
    private static final String TYPE_ARGUMENTS = "(?:<[^()<>]{0,200}>\\s*)?";
    private static final Pattern ANNOTATION = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(Disabled|Ignore|Test|DisabledIf|EnabledIf)(?![\\w$])");
    private static final Pattern ELEMENT = Pattern.compile("([\\w$]+)\\s*=(?!=)");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"\\s*(true|false)\\s*\"");
    private static final Pattern ABORTS = Pattern.compile("(?=A)\\bAssumptions\\s*\\.\\s*" + TYPE_ARGUMENTS + "abort\\b");
    private static final Pattern PY_MARK = Pattern.compile(
            "(?=[pu])\\b(?:pytest\\s*\\.\\s*mark\\s*\\.\\s*(skip|skipif|xfail)|unittest\\s*\\.\\s*(skip|skipIf|skipUnless))(?![\\w$])");
    private static final Pattern PY_CONTEXT = Pattern.compile("(?=[@p])(?:@|\\bpytestmark\\s*=(?!=))");
    private static final Pattern PARAM = Pattern.compile("(?=p)\\bpytest\\s*\\.\\s*param\\s*\\(");
    private static final Pattern BODY_SKIP = Pattern.compile("(?:^|[;{}:])\\s*(?:(?:pytest\\s*\\.\\s*skip|self\\s*\\.\\s*skipTest|raise\\s+(?:unittest\\s*\\.\\s*)?SkipTest"
            + "|t\\s*\\.\\s*Skip(?:f|Now)?)\\s*\\(|this\\s*\\.\\s*skip\\s*\\(\\s*\\)|pending\\s*\\(\\s*(?:['\"`]|\\)))");
    private static final Pattern CONDITION = Pattern.compile("(?=[iuewcf?&|])(?:\\b(?:if|unless|elif|else|when|except|catch|case|while|for)\\b|\\?(?![.?])|&&|\\|\\|)");
    private static final Pattern PENDING = Pattern.compile("(?:it|test|specify)\\s*+\\(");
    private static final Pattern DECLARATION = Pattern.compile("(?=[itdsc])(?<![\\w$.])(?<!\\.\\s)\\b(?:it|test|describe|suite|context|specify)(?:" + DOT + "\\w+){0,3}?\\s*\\(");
    private static final Pattern OPTION_KEY = Pattern.compile("(?=[sot])\\b(?:skip|only|todo)['\"]?\\s*:");
    private static final List<String> OPTIONS = List.of("skip", "only", "todo");
    private static final Pattern PROPERTY = Pattern.compile("(?:([\\w$]+)|'([\\w$]*)'|\"([\\w$]*)\")\\s*:(?!:)");
    private static final Pattern ABORT = Pattern.compile("(?=a)\\babort\\s*" + TYPE_ARGUMENTS + "\\(");
    private static final Pattern ASSUME = Pattern.compile("(?=a)\\bassume(True|False)\\s*\\(");
    private static final Pattern KEYWORD = Pattern.compile("([A-Za-z_]\\w*)\\s*=(?!=)");
    private static final Set<String> CALLED_AFTER = Set.of("return", "await", "else", "yield", "do");
    private static final Pattern KEY = Pattern.compile(
            "(?=[sStofDIEaxfepr])(?:skip|Skip|todo|only|fixme|runIf|pending|Disabled|Ignore|EnabledIf|enabled|abort|assume|xfail|xit|xtest|xdescribe|xcontext|xspecify|fit|fdescribe)");
    private static final int SKIP = 1, MODIFIER = 2, ANNOTATED = 4, ABORTED = 8, ASSUMED = 16, XFAIL = 32, ALIASED = 64;
    private static final int NONE = 0, NOTE = 1, OPEN = 2, SURE = 3;
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d_.]*");

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

    private static long work;

    public static long lintWork() {
        return work;
    }

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

    private static final class Budget {
        private long left;

        Budget(int length) {
            left = 4L * length + 4L * SCAN;
        }

        boolean spend() {
            return --left >= 0;
        }
    }

    private static Budget unbounded() {
        return new Budget(Integer.MAX_VALUE / 8);
    }

    public static boolean lintedSwitch(String line) {
        return level(line) == SURE;
    }

    public static boolean lintedNote(String line) {
        int level = level(line);
        return level == NOTE || level == OPEN;
    }

    private static int level(String line) {
        if (line.length() > LONGEST) return NONE;
        String d = decoded(line);
        return judged(readDecoded(d), keys(d), true, () -> FREE).level();
    }

    static boolean seen(String line, Predicate<String> test) {
        Reading r = reading(line);
        return test.test(r.code()) || test.test(r.written());
    }

    static boolean commentLine(String line) {
        String t = line.stripLeading();
        if (t.startsWith("//")) return true;
        return t.startsWith("#") && (t.length() == 1 || !Character.isJavaIdentifierPart(t.charAt(1)));
    }

    private static int afterComment(String d) {
        for (int i = 0; i < d.length(); i++) if (lineBreak(d.charAt(i))) return i + 1;
        return d.length();
    }

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

    private static boolean blank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF';
    }

    private static String fold(CharSequence s) {
        return fold(s, null, null);
    }

    private static String fold(CharSequence s, int[] in, int[] at) {
        int n = s.length();
        work += n;
        StringBuilder out = new StringBuilder(n);
        int space = -1;
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

    public static int unlinted(List<String> addedLines) {
        int n = 0;
        for (String line : addedLines) if (line.length() > LONGEST) n++;
        return n;
    }

    public record Lint(List<String> sure, List<String> notes) {}

    public static List<String> lintedSwitches(List<String> addedLines) {
        return lint(addedLines).sure();
    }

    public static Lint lint(List<String> addedLines) {
        int n = addedLines.size();
        List<String> sure = new ArrayList<>();
        TreeMap<Integer, String> notes = new TreeMap<>();
        boolean[] read = new boolean[n], told = new boolean[n], byLine = new boolean[n];
        int[] keys = new int[n], level = new int[n];
        byte[] before = new byte[n];
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
            if (judged.level() == NONE) continue;
            for (int k = i; k < i + width; k++) told[k] = true;
            if (judged.level() == SURE) sure.add(shown(addedLines, i, width));
            else noted(notes, addedLines, i, width, judged.lineBefore());
        }
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
                if (key == 0) continue;
                Verdict judged = judged(addedLines, keys, i, width, lineBefore(addedLines, read, before, i));
                open[i] = judged.level() == OPEN ? width : 0;
                if (judged.level() == SURE) {
                    for (int k = i; k < i + width; k++) told[k] = true;
                    sure.add(shown(addedLines, i, width));
                } else if (judged.level() == NOTE && !alone) {
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

    private static void noted(TreeMap<Integer, String> notes, List<String> lines, int i, int width, boolean lineBefore) {
        notes.putIfAbsent(i, lineBefore && i > 0 ? shown(lines, i - 1, width + 1) : shown(lines, i, width));
    }

    private static IntSupplier lineBefore(List<String> lines, boolean[] read, byte[] memo, int i) {
        return () -> {
            if (i == 0 || !read[i - 1]) return FREE;
            if (memo[i] == 0) memo[i] = (byte) (holdsNext(lines.get(i - 1)) + 1);
            return memo[i] - 1;
        };
    }

    private static final int FREE = 0, IN_BRACKETS = 1, HELD = 2;
    private static final Set<String> ENDS_GOING_ON = Set.of("if", "elif", "else", "unless", "when", "while", "for", "do", "case", "return", "throw", "yield",
            "await", "typeof", "void", "new", "delete", "in", "of", "instanceof", "is", "and", "or", "not", "lambda");

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

    private static boolean fresh(boolean[] read, boolean[] told, int i, int width) {
        if (i + width > read.length) return false;
        for (int k = i; k < i + width; k++) if (!read[k] || told[k]) return false;
        return true;
    }

    private static String shown(List<String> lines, int i, int width) {
        return String.join(" ", lines.subList(i, i + width).stream().map(String::trim).toList());
    }

    private record Verdict(int level, boolean lineBefore) {}

    private static final Verdict NOTHING_FOUND = new Verdict(NONE, false), FOUND_SURE = new Verdict(SURE, false);

    private static Verdict judged(List<String> lines, int[] keys, int i, int width, IntSupplier lineBefore) {
        int key = 0;
        for (int k = i; k < i + width; k++) key |= keys[k];
        return judged(joined(lines.subList(i, i + width)), key, false, lineBefore);
    }

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
                if (twin == null) level = Math.max(level, f[2] == SURE ? NOTE : f[2]);
                else if (f[2] == SURE && twin < SURE) level = Math.max(level, NOTE);
            }
        }
        return level == NONE ? NOTHING_FOUND : new Verdict(level, level == NOTE && (w.heldByLineBefore || c.heldByLineBefore));
    }

    private static long place(int kind, int at) {
        return (long) kind << 32 | at;
    }

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

    private static final int K_PENDING = 1, K_ANNOTATION = 2, K_ALIAS = 3, K_OPTIONS = 4, K_CONDITIONAL = 5, K_MODIFIER = 6, K_DECORATOR = 7,
            K_PYTESTMARK = 8, K_PARAM = 9, K_MARK = 10, K_ASSUME = 11, K_BODY = 12, K_ABORT = 13;
    private static final int AS_STATEMENT = 0, AS_ANNOTATION = 1, AS_DECORATOR = 2, AS_ELEMENT = 3;
    private static final int AT_STATEMENT = 1, AT_DECLARATION = 2, AT_DECORATOR = 4, OUTSIDE = 8;
    private static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static", "final", "abstract", "default", "synchronized");

    private static final class Read {
        final CharSequence s;
        final Budget budget;
        private final IntSupplier lineBefore;
        final List<int[]> forms = new ArrayList<>(2);
        int level = NONE;
        boolean heldByLineBefore;
        private int condition = -2;
        private byte[] starts;

        Read(CharSequence s, IntSupplier lineBefore) {
            this.s = s;
            this.lineBefore = lineBefore;
            budget = new Budget(s.length());
        }

        boolean held(int at) {
            if (condition == -2) {
                Matcher c = CONDITION.matcher(s);
                condition = c.find() ? c.start() : -1;
            }
            return condition >= 0 && condition < at;
        }

        boolean startsAt(int at, int what) {
            if (starts == null) starts = starts(s);
            return (starts[at] & what) != 0;
        }

        void form(int kind, int at, int level, int stands) {
            if (level == SURE) level = sure(this, at, stands);
            forms.add(new int[] {kind, at, level});
            this.level = Math.max(this.level, level);
        }
    }

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

    private static byte[] starts(CharSequence s) {
        int n = s.length();
        byte[] out = new byte[n + 1];
        Budget budget = new Budget(n);
        int state = AT_STATEMENT | AT_DECLARATION | AT_DECORATOR;
        int depth = 0;
        boolean lost = false;
        StringBuilder braces = new StringBuilder();
        boolean unclosed = false;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (blank(c)) {
                i++;
                continue;
            }
            char next = i + 1 < n ? s.charAt(i + 1) : '\0';
            if (c == '/' && next == '/' || c == '#' && !Character.isJavaIdentifierStart(next)) break;
            if (c == '/' && next == '*') {
                int end = indexOf(s, "*/", i + 2);
                if (end < 0) break;
                i = end + 2;
                state = AT_STATEMENT | AT_DECLARATION;
                continue;
            }
            out[i] = (byte) (depth == 0 && !lost ? state | OUTSIDE : OUTSIDE);
            int regex = c == '/' && !unclosed ? regexEnd(s, i) : -1;
            if (c == '/' && regex == UNCLOSED) unclosed = true;
            if (c == '"' || c == '\'' || c == '`' || regex > 0) {
                i = regex > 0 ? regex : literalEnd(s, i);
                state = 0;
            } else if (c == '(' || c == '[') {
                depth++;
                state = 0;
                i++;
            } else if (c == ')' || c == ']') {
                if (depth == 0) lost = true;
                else depth--;
                state = 0;
                i++;
            } else if (c == '{') {
                boolean value = valueBrace(s, i);
                braces.append(value ? 'v' : 'b');
                if (value) depth++;
                state = AT_STATEMENT | AT_DECLARATION;
                i++;
            } else if (c == '}') {
                if (!braces.isEmpty() && braces.charAt(braces.length() - 1) == 'v') depth--;
                if (!braces.isEmpty()) braces.setLength(braces.length() - 1);
                state = AT_STATEMENT | AT_DECLARATION;
                i++;
            } else if (c == ';') {
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

    private static final int UNCLOSED = -2;

    private static int regexEnd(CharSequence s, int at) {
        int n = s.length(), p = at - 1;
        while (p >= 0 && blank(s.charAt(p))) p--;
        boolean value = p < 0 || "(,=:[!&|?{};".indexOf(s.charAt(p)) >= 0;
        if (!value && p >= 5 && s.subSequence(p - 5, p + 1).toString().equals("return") && (p < 6 || !word(s.charAt(p - 6)))) value = true;
        if (!value || at + 1 >= n || s.charAt(at + 1) == '/' || s.charAt(at + 1) == '*') return -1;
        boolean inClass = false;
        for (int i = at + 1; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (c == '/' && !inClass) return i + 1;
        }
        return UNCLOSED;
    }

    private static boolean valueBrace(CharSequence s, int at) {
        int p = at - 1;
        while (p >= 0 && blank(s.charAt(p))) p--;
        if (p < 0) return false;
        char c = s.charAt(p), before = p > 0 ? s.charAt(p - 1) : ' ';
        if (c == '>') return before == '-' || before == '=';
        if (c == '=') return "=!<>".indexOf(before) < 0;
        int w = p;
        while (w >= 0 && word(s.charAt(w))) w--;
        return p - w == 6 && s.subSequence(w + 1, p + 1).toString().equals("return");
    }

    private static int literalEnd(CharSequence s, int at) {
        int n = s.length();
        char q = s.charAt(at);
        if (q != '`' && at + 2 < n && s.charAt(at + 1) == q && s.charAt(at + 2) == q) {
            int end = indexOf(s, String.valueOf(q).repeat(3), at + 3);
            return end < 0 ? n : end + 3;
        }
        int i = at + 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') i += 2;
            else if (c == q) return i + 1;
            else if (q == '`' && c == '$' && i + 1 < n && s.charAt(i + 1) == '{') i = templateCodeEnd(s, i + 2);
            else i++;
        }
        return n;
    }

    private static int templateCodeEnd(CharSequence s, int at) {
        int n = s.length(), braces = 1, i = at;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = literalEnd(s, i);
                continue;
            }
            if (c == '{') braces++;
            else if (c == '}' && --braces == 0) return i + 1;
            i++;
        }
        return n;
    }

    private static int indexOf(CharSequence s, String text, int from) {
        int n = s.length(), m = text.length();
        outer:
        for (int i = Math.max(0, from); i + m <= n; i++) {
            for (int k = 0; k < m; k++) if (s.charAt(i + k) != text.charAt(k)) continue outer;
            return i;
        }
        return -1;
    }

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

    private static boolean element(Read r, int at) {
        if (r.startsAt(at, AT_STATEMENT)) return true;
        if (!r.startsAt(at, OUTSIDE)) return false;
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

    private static int blanks(CharSequence s, int i) {
        while (i < s.length() && blank(s.charAt(i))) i++;
        return i;
    }

    private static int wordEnd(CharSequence s, int i) {
        while (i < s.length() && Character.isJavaIdentifierPart(s.charAt(i))) i++;
        return i;
    }

    private static boolean wordAt(CharSequence s, int i, String word) {
        int end = wordEnd(s, i);
        return end - i == word.length() && s.subSequence(i, end).toString().equals(word);
    }

    private static int whole(String argument, String switches, String runs) {
        return argument.equals(switches) ? SURE : argument.equals(runs) ? NONE : NOTE;
    }

    private static void pending(Read r) {
        Matcher m = PENDING.matcher(r.s);
        if (!m.lookingAt()) return;
        Arguments a = arguments(r.s, m.end() - 1, r.budget);
        int i = a.closed() ? blanks(r.s, a.end()) : 0;
        if (i < r.s.length() && r.s.charAt(i) == ';') i = blanks(r.s, i + 1);
        boolean whole = a.closed() && i >= r.s.length() && a.list().size() == 1 && literal(a.list().get(0));
        r.form(K_PENDING, 0, whole ? SURE : NONE, AS_STATEMENT);
    }

    private static boolean literal(String arg) {
        if (arg.isEmpty() || "'\"`".indexOf(arg.charAt(0)) < 0) return false;
        work += arg.length();
        return stringEnd(arg, 0) == arg.length() && (arg.charAt(0) != '`' || !arg.contains("${"));
    }

    private static void scriptSwitch(Read r) {
        for (Matcher m = CONDITIONAL_CALL.matcher(r.s); r.level < SURE && m.find();) r.form(K_CONDITIONAL, m.start(), conditionalCall(r, m), AS_STATEMENT);
        for (Matcher m = SCRIPT_SWITCH.matcher(r.s); r.level < SURE && m.find();) {
            String modifier = m.group(1);
            r.form(K_MODIFIER, m.start(), registered(r, m.end(), modifier.equals("skip") || modifier.equals("fixme"), true, true), AS_STATEMENT);
        }
    }

    private static int conditionalCall(Read r, Matcher m) {
        Arguments a = arguments(r.s, m.end() - 1, r.budget);
        if (!a.closed()) return OPEN;
        boolean skipIf = m.group(1).equals("skipIf");
        int level = whole(a.list().size() == 1 ? a.list().get(0) : "", skipIf ? "true" : "false", skipIf ? "false" : "true");
        return level == SURE ? registered(r, a.end(), false, true, true) : level;
    }

    private static void aliases(Read r) {
        for (Matcher m = ALIAS.matcher(r.s); r.level < SURE && m.find();) {
            boolean fit = m.group().equals("fit");
            r.form(K_ALIAS, m.start(), registered(r, m.end(), false, false, !fit), AS_STATEMENT);
        }
    }

    private static int registered(Read r, int after, boolean skips, boolean referenced, boolean computed) {
        Call call = registering(r, after);
        if (call.args() == null) return call.level() == NOTE && !referenced ? NONE : call.level();
        Arguments a = call.args();
        if (a.list().isEmpty()) return !a.closed() ? OPEN : computed ? NOTE : NONE;
        if (a.ended() == 0) return OPEN;
        String first = a.list().get(0);
        if (title(first)) return SURE;
        if (skips && !call.each() && (first.equals("false") || NUMBER.matcher(first).matches())) return NONE;
        return computed ? NOTE : NONE;
    }

    private record Call(int level, Arguments args, boolean each) {}

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

    private static int stringEnd(String t, int i) {
        char quote = t.charAt(i);
        int depth = 0;
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
                return depth == 0 ? k + 1 : -1;
            }
        }
        return -1;
    }

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

    private static int optionObject(Read r, int at) {
        Arguments props = arguments(r.s, at, r.budget);
        if (!props.closed()) return OPEN;
        int[] said = new int[OPTIONS.size()];
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
                int k = OPTIONS.indexOf(p);
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

    private static int element(Arguments a, String name, String switches, String runs) {
        for (String arg : a.list()) {
            Matcher e = ELEMENT.matcher(arg);
            if (e.lookingAt() && e.group(1).equals(name)) return whole(arg.substring(e.end()).strip(), switches, runs);
        }
        return NONE;
    }

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

    private static final Set<String> GOES_ON_WORDS = Set.of("if", "else", "and", "or", "instanceof");

    private static boolean javaEnds(CharSequence s, int at) {
        int i = blanks(s, at);
        if (i >= s.length()) return true;
        char c = s.charAt(i), next = i + 1 < s.length() ? s.charAt(i + 1) : ' ';
        if (Character.isJavaIdentifierStart(c)) return !GOES_ON_WORDS.contains(s.subSequence(i, wordEnd(s, i)).toString());
        return c == '@' || c == '/' && (next == '/' || next == '*') || c == '*' && next == '/';
    }

    private static final int ENDS = 0, GOES_ON = 1, CUT = 2;
    private static final int DECORATOR = 0, STATEMENT = 1, ARGUMENT = 2;

    private static void python(Read r) {
        Set<Integer> judged = new java.util.HashSet<>();
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

    private static int param(Read r, Matcher p, Set<Integer> judged) {
        Arguments a = arguments(r.s, p.end() - 1, r.budget);
        int level = NONE;
        for (int k = 0; k < a.list().size(); k++) {
            Matcher keyword = KEYWORD.matcher(a.list().get(k));
            if (keyword.lookingAt() && keyword.group(1).equals("marks")) level = Math.max(level, markValue(r, a.starts().get(k) + keyword.end(), true, judged));
        }
        return level;
    }

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

    private static int pythonEnds(CharSequence s, int at, int where) {
        int i = blanks(s, at);
        if (i >= s.length()) return where == ARGUMENT ? CUT : ENDS;
        char c = s.charAt(i);
        if (c == '\\' && blanks(s, i + 1) >= s.length()) return CUT;
        if (c == '#') return where == ARGUMENT ? CUT : ENDS;
        if (where == DECORATOR) return c == '@' || wordAt(s, i, "def") || wordAt(s, i, "async") || wordAt(s, i, "class") ? ENDS : GOES_ON;
        return c == ',' || c == ';' || c == ')' || c == ']' ? ENDS : GOES_ON;
    }

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

    // pytest (_pytest/skipping.py): with condition= it is the only condition; else any positional condition that holds applies the mark,
    // and with none it applies. xfail switches a test off only with run=False.
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

    private record Arguments(List<String> list, List<Integer> starts, boolean closed, int end) {
        int ended() {
            return closed ? list.size() : Math.max(0, list.size() - 1);
        }
    }

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

    private static int aborted(List<int[]> aborts) {
        return aborts.isEmpty() ? NONE : aborts.get(0)[2] == 1 ? NOTE : OPEN;
    }

    private static boolean word(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    private static void assumption(Read r) {
        Matcher m = ASSUME.matcher(r.s);
        while (r.level < SURE && m.find()) {
            Arguments a = arguments(r.s, m.end() - 1, r.budget);
            String never = m.group(1).equals("True") ? "false" : "true";
            boolean holds = false;
            for (int k = 0; k < a.ended() && !holds; k++) holds = a.list().get(k).equals(never);
            int from = qualified(r.s, m.start());
            // never sure: a lambda kept for later, or an exception assertion opened on a line above, may hold the call
            int level = !a.closed() ? OPEN : holds ? NOTE : NONE;
            r.form(K_ASSUME, from, level, AS_STATEMENT);
        }
    }

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

    private static final Pattern BUILT_IN = Pattern.compile(SCRIPT_SWITCH.pattern() + "|\\b[xf](?:it|test|describe|context|specify)\\b"
            + "|@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(?:Disabled\\w*|Ignore|EnabledIf\\w*)\\b|" + ABORTS.pattern()
            + "|\\bpytest\\s*\\.\\s*(?:mark\\s*\\.\\s*)?(?:skip\\w*|xfail)\\b|\\bunittest\\s*\\.\\s*skip\\w*|\\bself\\s*\\.\\s*skipTest\\b|\\bSkipTest\\b"
            + "|\\bthis\\s*\\.\\s*skip\\b|\\bt\\s*\\.\\s*Skip\\w*");

    public static String withoutBuiltInSwitches(String line) {
        if (line.length() > LONGEST) return line;
        List<int[]> spans = new ArrayList<>(assumptions(line, unbounded()));
        spans.addAll(bareAborts(line, unbounded(), false));
        spans.sort(Comparator.comparingInt((int[] x) -> x[0]).reversed());
        StringBuilder out = new StringBuilder(line);
        int limit = line.length();
        for (int[] x : spans) {
            if (x[1] > limit) continue;
            out.replace(x[0], x[1], " ");
            limit = x[0];
        }
        return BUILT_IN.matcher(out.toString()).replaceAll(" ");
    }

    private static final Pattern FOCUSED = Pattern.compile("\\b(?:it|test|describe|context|suite|specify)(?:" + DOT + "\\w+){0,8}" + DOT + "only\\b"
            + "|(?<![\\w$.])f(?:it|describe)(?=\\s*(?:\\(|\\.\\s*each\\b))|\\bonly['\"]?\\s*:\\s*true\\b");
    private static final Pattern JAVA_ASSERT = Pattern.compile(
            "\\b(?:assert|verify)\\w*\\s*\\(|\\b(?=\\w*?(?i:expect))\\w++\\s*\\(|\\.should\\w*\\s*\\(|\\bfail\\s*\\(|\\bassert\\s|\\bexpected\\s*=");
    private static final Pattern SCRIPT_ASSERT = Pattern.compile("\\bexpect(?:TypeOf)?\\s*[(.]|\\bassert\\w*\\s*[(.]|\\.should\\b|\\bshould\\s*\\(");

    private static final Pattern XML_SKIP = Pattern.compile("<(skipTests|skipITs|maven\\.test\\.skip)>\\s*true\\s*<");
    private static final Pattern XML_IGNORE = Pattern.compile("<(testFailureIgnore|maven\\.test\\.failure\\.ignore)>\\s*true\\s*<");
    private static final Pattern D_FLAG = Pattern.compile("-D(skipTests|skipITs|maven\\.test\\.skip|testFailureIgnore|maven\\.test\\.failure\\.ignore)\\b(?:=([^\\s\"']*))?");
    private static final Pattern GRADLE_EXCLUDE = Pattern.compile("(?:^|\\s)(?:-x|--exclude-task)\\s+:?(?:[\\w-]+:)*(?:test|check)\\b");
    private static final Pattern GRADLE_IGNORE = Pattern.compile("\\bignoreFailures\\s*=\\s*true\\b");
    private static final Pattern PASS_EMPTY = Pattern.compile("--passWithNoTests\\b(?!\\s*=\\s*false)|[\"']?passWithNoTests[\"']?\\s*:\\s*true\\b");
    private static final Pattern PLUGIN_SKIP = Pattern.compile("<skip>\\s*true\\s*</skip>");
    private static final Pattern OR_TAIL = Pattern.compile("(?:true|:|exit\\s+0)(?=\\s|$|[\"'`;&)])");
    private static final Pattern TEST_RUNNER = Pattern.compile(
            "\\b(?:npm|yarn|pnpm)\\s+(?:run\\s+)?test\\b|\\b(?:npx\\s+)?(?:jest|vitest|mocha|pytest|karma|ava)\\b|\\bgo\\s+test\\b|\\bcypress\\s+run\\b|\\bplaywright\\s+test\\b");
    private static final Pattern BUILD_TOOL = Pattern.compile("(?:^|[\\s/])(?:mvnw?|mvnw\\.cmd|gradlew?|gradlew\\.bat)(?=\\s|$)");
    private static final Pattern TEST_GOAL = Pattern.compile("\\b(?:test|verify|check|build|install)\\b");

    private static boolean runsTests(String command) {
        if (TEST_RUNNER.matcher(command).find()) return true;
        Matcher tool = BUILD_TOOL.matcher(command);
        return tool.find() && TEST_GOAL.matcher(command).region(tool.end(), command.length()).find();
    }

    private static final Pattern MINIMUM = Pattern.compile("<minimum>\\s*+([\\d.]++)\\s*+%?+\\s*+</minimum>|\\bminimum\\s*+=\\s*+([\\d.]++)");
    private static final Pattern SCRIPT_THRESHOLD = Pattern.compile("[\"']?\\b(lines|branches|functions|statements)[\"']?\\s*:\\s*(\\d+(?:\\.\\d+)?)");

    private final Change change;
    private final Map<String, List<String>> whole;
    private final Map<String, List<String>> wholeRemoved;
    private final Map<String, List<String>> added;
    private final Map<String, List<String>> removed;
    private final Map<String, String> beforeText = new HashMap<>();
    private final Map<String, String> afterText = new HashMap<>();
    private final Set<Finding> out = new LinkedHashSet<>();
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

    private static boolean anyTestFile(String path) {
        return testFile(path, sdlc.core.Ctx.cfg.list("paths.test"));
    }

    private void switchedOff() {
        for (String path : change.paths("AM")) {
            if (!anyTestFile(path)) continue;
            List<String> lines = whole.getOrDefault(path, List.of());
            Lint lint = lint(lines);
            for (String hit : lint.sure()) switchedOff(path, hit);
            for (String hit : lint.notes()) note(FOCUSED.matcher(hit).find() ? "may focus a test" : "may switch a test off", path, "the run of the file judges it: " + hit);
            int unread = unlinted(lines);
            if (unread > 0) {
                note("lines too long for the switch lint", path, unread + (unread == 1 ? " added line is" : " added lines are") + " longer than " + LONGEST
                        + " characters and " + (unread == 1 ? "was" : "were") + " not read for switches; the run of the file judges its tests");
            }
        }
        for (String hit : Checks.disabledTests(added)) {
            if (!seen(hitLine(hit), l -> Checks.containsDisabled(withoutBuiltInSwitches(l)))) continue;
            switchedOff(hitPath(hit), hitLine(hit));
        }
    }

    static String code(String t) {
        return code(t, null);
    }

    private static String code(String t, int[] map) {
        int n = t.length();
        work += n;
        StringBuilder out = new StringBuilder(n);
        int[] stack = new int[8];
        int top = 0;
        boolean lineStart = true;
        int i = 0;
        while (i < n) {
            char c = t.charAt(i);
            if (top > 0 && stack[top - 1] < 0) {
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
                top--;
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
        return c == '\n' || c == '\r' || c == 0x2028 || c == 0x2029;
    }

    private static int lineEnd(String t, int from) {
        int i = from;
        while (i < t.length() && !lineBreak(t.charAt(i))) i++;
        work += i - from;
        return i;
    }

    private static int closing(String t, int from, int to) {
        for (int i = from; i + 1 < to; i++) {
            work++;
            if (t.charAt(i) == '*' && t.charAt(i + 1) == '/') return i;
        }
        return -1;
    }

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
            boolean configured = sdlc.core.Trusted.inCi()
                    ? sdlc.core.Git.refExists(sdlc.core.Trusted.baseRef()) && sdlc.core.Trusted.config(sdlc.core.Trusted.baseRef()) != null
                    : java.nio.file.Files.isRegularFile(sdlc.core.Ctx.root.resolve(".agent/sdlc.yml"));
            if (!configured) sdlc.core.Ctx.cfg = EVERY_LANGUAGE;
            hits = Checks.secrets(added);
        } finally {
            sdlc.core.Ctx.cfg = own;
        }
        for (String hit : hits) {
            if (GENERATED.matcher(hitPath(hit)).matches()) note("possible secret in a generated file", hitPath(hit), hitLine(hit));
            else problem("possible secret", hitPath(hit), hitLine(hit));
        }
    }

    private static final Pattern TEST_CALL = Pattern.compile("\\b[xf]?(?:it|test|describe|context|suite)(?:" + DOT + "\\w++){0,8}+\\s*(?:\\?\\.\\s*)?\\(|^\\s*(?:async\\s+)?def\\s+test|\\bfunc\\s+Test"
            + "|@\\s*(?:[\\w$]+\\s*\\.\\s*){0,12}(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    private static final Pattern SOURCE = Pattern.compile(".*\\.(?:java|kt|kts|groovy|scala|[cm]?[jt]sx?|vue|svelte|py|go|rb|cs|php|rs|swift)");
    private static final Pattern PACKAGE_LINE = Pattern.compile("\\s*+package\\s++[\\w.]++\\s*+;?+\\s*+");
    private static final Pattern IMPORT_LINE = Pattern.compile("\\s*+import\\s++(?:static\\s++)?+(?:\\w|\\.(?!\\s*+\\*))++(?:\\s*+\\.\\s*+\\*)?+\\s*+;?+\\s*+");
    private static final Pattern JVM = Pattern.compile(".*\\.(?:java|kt|groovy|scala)");

    private static String declaresTests(String path, List<String> lines) {
        if (!SOURCE.matcher(path).matches()) return null;
        boolean unread = false;
        for (String line : code(String.join("\n", lines), null).split("\n", -1)) {
            if (line.length() > LONGEST) unread = true;
            else if (TEST_CALL.matcher(new Counted(line)).find()) return "code";
        }
        if (unread) return "unread";
        for (String line : lines) if (line.length() <= LONGEST && TEST_CALL.matcher(new Counted(line)).find()) return "text";
        return null;
    }

    private static List<String> body(List<String> lines, boolean imports) {
        return lines.stream().filter(l -> l.length() > LONGEST || !PACKAGE_LINE.matcher(new Counted(l)).matches()
                && !(imports && IMPORT_LINE.matcher(new Counted(l)).matches())).toList();
    }

    enum How {
        SAME("its lines are the same"), PACKAGE("its lines are the same but for its package line"), IMPORTS("its lines are the same but for its package and import lines");

        final String words;

        How(String words) {
            this.words = words;
        }
    }

    record Move(String from, String to, How how) {}

    static Map<String, Move> moves(Change change) {
        boolean any = false;
        for (String path : change.paths("D")) any |= anyTestFile(path);
        return any ? moves(change, change.addedLines(), change.removedLines()) : Map.of();
    }

    private static Map<String, Move> moves(Change change, Map<String, List<String>> added, Map<String, List<String>> removed) {
        Map<String, Move> out = new LinkedHashMap<>();
        List<String> gone = change.paths("D").stream().filter(Integrity::anyTestFile).toList();
        if (gone.isEmpty()) return out;
        Set<String> taken = new HashSet<>();
        for (How how : How.values()) {
            Map<List<Object>, java.util.ArrayDeque<String>> by = new HashMap<>();
            for (String a : change.paths("A")) {
                if (taken.contains(a) || !anyTestFile(a) || how != How.SAME && !JVM.matcher(a).matches()) continue;
                by.computeIfAbsent(List.of(extension(a), compared(added.getOrDefault(a, List.of()), how)), k -> new java.util.ArrayDeque<>()).add(a);
            }
            for (String d : gone) {
                if (out.containsKey(d) || how != How.SAME && !JVM.matcher(d).matches()) continue;
                java.util.ArrayDeque<String> twins = by.get(List.of(extension(d), compared(removed.getOrDefault(d, List.of()), how)));
                if (twins == null || twins.isEmpty()) continue;
                String a = twins.poll();
                taken.add(a);
                out.put(d, new Move(d, a, how));
            }
        }
        return out;
    }

    private static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> compared(List<String> lines, How how) {
        return how == How.SAME ? lines : body(lines, how == How.IMPORTS);
    }

    private void deletedTestFiles() {
        boolean any = false;
        for (String path : change.paths("D")) any |= anyTestFile(path);
        if (!any) return;
        Map<String, Move> moves = moves(change, whole, wholeRemoved);
        for (String path : change.paths("D")) {
            if (!anyTestFile(path)) continue;
            List<String> was = wholeRemoved.getOrDefault(path, List.of());
            Move m = moves.get(path);
            String declared = m != null ? null : declaresTests(path, was);
            if (m != null && m.how() == How.SAME) note("test file moved", path, "the same file is now at " + m.to());
            else if (m != null && m.how() == How.PACKAGE) note("test file moved", path, "the same file is now at " + m.to() + ", in another package");
            else if (m != null) note("test file moved and its imports changed", path, "the same tests are now at " + m.to() + ", with other imports: its run judges it");
            else if (testNamed(path) || "code".equals(declared)) problem("test file deleted", path, "the file is gone");
            else if ("unread".equals(declared)) note("test support file deleted", path, "a line of it is longer than " + LONGEST + " characters and was not read; no line that was read declares a test");
            else if ("text".equals(declared)) note("test support file deleted", path, "only a comment or a string in it mentions a test; no line of its code declares one");
            else note("test support file deleted", path, "it declares no test cases that a simple reading can find");
        }
    }

    private void skipFlags() {
        Set<String> readAsXml = new LinkedHashSet<>();
        for (String path : change.paths("AM")) if ((path.equals("pom.xml") || path.endsWith("/pom.xml")) && pomFlags(path)) readAsXml.add(path);
        for (Map.Entry<String, List<String>> e : added.entrySet()) {
            if (!buildOrScript(e.getKey())) continue;
            for (String line : e.getValue()) skipFlag(e.getKey(), line, readAsXml.contains(e.getKey()));
            for (String line : whole.getOrDefault(e.getKey(), List.of())) madeToPass(e.getKey(), line);
        }
        for (String path : change.paths("AM")) if ((path.equals("pom.xml") || path.endsWith("/pom.xml")) && !readAsXml.contains(path)) pluginSkips(path);
    }

    private boolean pomFlags(String path) {
        Map<String, String> now = pomSettings(after(path));
        if (now == null) return false;
        Map<String, String> was = pomSettings(before(path));
        for (Map.Entry<String, String> e : now.entrySet()) if (was == null || !was.containsKey(e.getKey())) problem(e.getValue(), path, e.getKey());
        return true;
    }

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

    private void madeToPass(String path, String line) {
        for (String command : commandsMadeToPass(line)) {
            if (!runsTests(command)) continue;
            problem("test failures ignored by the build", path, "a failing test command still counts as success: " + line.trim().replaceAll("\\s+", " "));
            return;
        }
    }

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

    // <skip>true</skip> skips tests only inside the Surefire or Failsafe plugin.
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

    private String before(String path) {
        return beforeText.computeIfAbsent(path, p -> text(p, change.before(p)));
    }

    private String after(String path) {
        return afterText.computeIfAbsent(path, p -> text(p, change.after(p)));
    }

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
            return Map.of();
        }
    }

    private static Set<String> changedKeys(Map<String, Object> was, Map<String, Object> now) {
        Set<String> keys = new TreeSet<>(was.keySet());
        keys.addAll(now.keySet());
        keys.removeIf(k -> String.valueOf(was.get(k)).equals(String.valueOf(now.get(k))));
        return keys;
    }

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

    private void problem(String kind, String path, String detail) {
        out.add(Finding.problem(kind, path, clip(detail)));
    }

    private void note(String kind, String path, String detail) {
        out.add(Finding.note(kind, path, clip(detail)));
    }

    static String clip(String detail) {
        String line = detail.replaceAll("\\s+", " ").strip();
        return line.length() <= MAX_DETAIL ? line : line.substring(0, MAX_DETAIL - 3) + "...";
    }
}
