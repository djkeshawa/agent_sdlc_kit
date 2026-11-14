package sdlc.check;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tests a source file declares, read from its code (comments and the insides of strings are
 * not code): each test and each group of tests with its title, the groups or classes it stands
 * in, and the switches on it that skip it, mark it as to do, or focus on it.
 *
 * A test is known by where it stands and what it is called ("refunds > partial > rounds down",
 * "RefundsTest.Partial.roundsDown"), not by its title alone: two groups may each hold a test of
 * the same title.
 */
final class Declared {
    private Declared() {}

    /**
     * One declaration. title is the value of a plain string title (escapes resolved), null when
     * the title is computed while the file runs; written is that title as the file spells it.
     * home names the groups or classes around it, outermost first, joined by " > ". generated
     * says the declaration makes several tests (it.each) or has a computed title. test is false
     * for a group, and for a Java method that is no test but carries a switch.
     */
    record Item(boolean group, boolean test, String title, String written, String home, Set<String> switches, boolean generated, int start, int end) {
        /** Where it stands and what it is called. A computed title reads "?". */
        String where() {
            String name = title == null ? "?" : title;
            return home.isEmpty() ? name : home + " > " + name;
        }
    }

    private static final Pattern SCRIPT_HEAD = Pattern.compile("(?<![\\w$.])(describe|context|suite|it|test|xdescribe|xcontext|xit|xtest|fdescribe|fit)\\b");
    private static final Set<String> SWITCHES = Set.of("skip", "todo", "only");

    /** The tests and groups of a script test file (describe, it, test and their members), in the order of the file. */
    static List<Item> script(String path, String text) {
        List<Item> out = new ArrayList<>();
        if (text == null) return out;
        String code = Code.mask(path, text);
        int n = code.length();
        Matcher m = SCRIPT_HEAD.matcher(code);
        while (m.find()) {
            String head = m.group(1);
            Set<String> members = new LinkedHashSet<>();
            int at = m.end();
            // the members: it.concurrent.skip, with white space and line breaks around each dot
            while (true) {
                int dot = skipSpace(code, at);
                if (dot >= n || code.charAt(dot) != '.') break;
                int name = skipSpace(code, dot + 1);
                int end = name;
                while (end < n && (Character.isLetterOrDigit(code.charAt(end)) || code.charAt(end) == '_' || code.charAt(end) == '$')) end++;
                if (end == name) break;
                members.add(code.substring(name, end));
                at = end;
            }
            boolean table = members.contains("each") || members.contains("for");
            // the calls: (title, body), or for a table of cases (table)(title, body), or `table`(title, body)
            String title = null, written = null;
            boolean called = false, plain = false;
            int end = at;
            for (int groups = 0; groups < 3; groups++) {
                int open = skipSpace(code, end);
                if (open < n && code.charAt(open) == '`' && table) {
                    int close = code.indexOf('`', open + 1);
                    if (close < 0) break;
                    end = close + 1;
                    continue;
                }
                if (open >= n || code.charAt(open) != '(') break;
                int close = Code.closing(code, open);
                if (close < 0) break;
                called = true;
                end = close + 1;
                int first = skipSpace(code, open + 1);
                char q = first < n ? code.charAt(first) : ' ';
                if (q == '"' || q == '\'' || q == '`') {
                    int closing = code.indexOf(q, first + 1);
                    // a template string with ${...} in it is computed; one without is a plain string
                    if (closing > first && closing < close && !code.substring(first, closing).contains("${")) {
                        // a sum of strings is computed too: 'adds ' + n
                        int after = skipSpace(code, closing + 1);
                        if (after < n && (code.charAt(after) == ',' || code.charAt(after) == ')')) {
                            written = text.substring(first + 1, closing);
                            title = TestNames.unescaped(written);
                            plain = true;
                        }
                    }
                    break; // this was the call with the title
                }
                if (!table || groups > 0) break; // a name or an expression as the title: computed
            }
            if (!called) continue; // the word used as a name, not called
            boolean group = head.endsWith("describe") || head.endsWith("context") || head.equals("suite");
            Set<String> switches = new LinkedHashSet<>();
            for (String s : members) if (SWITCHES.contains(s)) switches.add(s);
            if (head.startsWith("x")) switches.add("skip");
            if (head.equals("fit") || head.equals("fdescribe")) switches.add("only");
            out.add(new Item(group, !group, title, written, "", switches, table || !plain, m.start(), end));
        }
        return homes(out);
    }

    private static final Pattern JAVA_TYPE = Pattern.compile("\\b(?:class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD = Pattern.compile("\\bvoid\\s+(\\w+)\\s*\\(");
    private static final Pattern JAVA_TEST = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern JAVA_OFF = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*)*(Disabled|DisabledIf\\w*|Ignore)\\b");
    private static final Pattern JAVA_ASSUME = Pattern.compile("\\bassume(?:True|That)\\s*\\(\\s*false\\s*\\)|\\bassumeFalse\\s*\\(\\s*true\\s*\\)");

    /**
     * The classes and the void methods of a Java file, each with the classes around it. A method
     * is a test when a test annotation stands before it. An annotation that switches tests off
     * belongs to the declaration that follows it; an assumption that always fails belongs to
     * the method it stands in.
     */
    static List<Item> java(String path, String text) {
        List<Item> out = new ArrayList<>();
        if (text == null) return out;
        String code = Code.mask(path, text);
        Matcher m = JAVA_TYPE.matcher(code);
        while (m.find()) {
            int open = code.indexOf('{', m.end());
            int close = open < 0 ? -1 : Code.closing(code, open);
            if (close < 0) continue;
            out.add(new Item(true, false, m.group(1), m.group(1), "", new LinkedHashSet<>(), false, m.start(), close));
        }
        m = JAVA_METHOD.matcher(code);
        while (m.find()) {
            int paren = Code.closing(code, m.end() - 1);
            if (paren < 0) continue;
            int open = paren + 1;
            while (open < code.length() && code.charAt(open) != '{' && code.charAt(open) != ';') open++;
            int close = open < code.length() && code.charAt(open) == '{' ? Code.closing(code, open) : open;
            if (close < 0) close = paren;
            out.add(new Item(false, false, m.group(1), m.group(1), "", new LinkedHashSet<>(), false, m.start(), close));
        }
        out.sort(Comparator.comparingInt(Item::start));
        // annotations: each belongs to the declaration that starts next after it
        boolean[] test = new boolean[out.size()];
        m = JAVA_TEST.matcher(code);
        while (m.find()) {
            int next = next(out, m.end());
            if (next >= 0 && !out.get(next).group()) test[next] = true;
        }
        // A switch is never dropped for want of a declaration to put it on: one that stands before something this
        // reading does not know (a method that returns a value, a field) is put on the class it stands in.
        List<Item> loose = new ArrayList<>();
        m = JAVA_OFF.matcher(code);
        while (m.find()) {
            int next = next(out, m.end());
            around(out, loose, next >= 0 ? out.get(next) : null, m.start()).switches().add("@" + m.group(1));
        }
        m = JAVA_ASSUME.matcher(code);
        while (m.find()) {
            Item in = null;
            for (Item item : out) if (!item.group() && item.start() <= m.start() && m.start() <= item.end()) in = item;
            around(out, loose, in, m.start()).switches().add("assume");
        }
        out.addAll(loose);
        List<Item> kept = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            Item item = out.get(i);
            boolean isTest = i < test.length && test[i]; // the loose ones were added after the tests were marked
            if (item.group() || isTest || !item.switches().isEmpty()) kept.add(new Item(item.group(), isTest, item.title(), item.written(), "", item.switches(), false, item.start(), item.end()));
        }
        return homes(kept);
    }

    /** The declaration a switch is put on: the one found, else the innermost class around the place, else the file. */
    private static Item around(List<Item> items, List<Item> loose, Item found, int at) {
        if (found != null) return found;
        Item in = null;
        for (Item item : items) if (item.group() && item.start() <= at && at <= item.end()) in = item;
        if (in != null) return in;
        Item file = new Item(false, false, "?", "?", "", new LinkedHashSet<>(), false, at, at);
        loose.add(file);
        return file;
    }

    /** The first declaration that starts at or after a place. */
    private static int next(List<Item> items, int from) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).start() >= from) return i;
        return -1;
    }

    /** Gives each declaration its home: the groups whose extent holds it. */
    private static List<Item> homes(List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(Item::start));
        List<Item> out = new ArrayList<>();
        List<Item> open = new ArrayList<>();
        for (Item item : sorted) {
            while (!open.isEmpty() && open.get(open.size() - 1).end() < item.start()) open.remove(open.size() - 1);
            StringBuilder home = new StringBuilder();
            for (Item g : open) home.append(home.length() == 0 ? "" : " > ").append(g.title() == null ? "?" : g.title());
            Item placed = new Item(item.group(), item.test(), item.title(), item.written(), home.toString(), item.switches(), item.generated(), item.start(), item.end());
            out.add(placed);
            if (item.group()) open.add(placed);
        }
        return out;
    }

    private static int skipSpace(String code, int at) {
        while (at < code.length() && Character.isWhitespace(code.charAt(at))) at++;
        return at;
    }
}
