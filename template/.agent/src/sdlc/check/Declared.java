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
 * "RefundsTest > Partial > roundsDown"), not by its title alone: two groups may each hold a test
 * of the same title.
 *
 * Two rules keep a reading of tokens honest. A switch that is seen is never dropped: when the
 * declaration around it cannot be read, it is kept with what stands after it as its name. And
 * what could not be read to its end is marked unsure, so that callers do not build on it.
 */
final class Declared {
    private Declared() {}

    /**
     * One declaration. title is the value of a plain string title (escapes resolved), null when
     * the title is computed while the file runs; computed then holds the expression as written.
     * written is a plain title as the file spells it. home names the groups or classes around
     * it, outermost first, joined by " > ". generated says the declaration makes several tests
     * (it.each) or has a computed title. test is false for a group, and for a Java declaration
     * that is no test but carries a switch. body starts after the title. unsure: its end could
     * not be found, so its extent is a guess.
     */
    record Item(boolean group, boolean test, String title, String written, String computed, String home, Set<String> switches, boolean generated,
            int start, int body, int end, boolean unsure) {
        /** What it is called. A computed title reads "?(expression)". */
        String name() {
            return title != null ? title : "?" + (computed == null || computed.isEmpty() ? "" : "(" + computed + ")");
        }

        /** Where it stands and what it is called. */
        String where() {
            return home.isEmpty() ? name() : home + " > " + name();
        }

        Item at(String home) {
            return new Item(group, test, title, written, computed, home, switches, generated, start, body, end, unsure);
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
            Set<String> switches = new LinkedHashSet<>();
            for (String s : members) if (SWITCHES.contains(s)) switches.add(s);
            if (head.startsWith("x")) switches.add("skip");
            if (head.equals("fit") || head.equals("fdescribe")) switches.add("only");
            boolean group = head.endsWith("describe") || head.endsWith("context") || head.equals("suite");
            boolean table = members.contains("each") || members.contains("for");

            // the calls: (title, body); for a table of cases (table)(title, body) or `table`(title, body); type arguments may stand before each
            String title = null, written = null, computed = null;
            boolean called = false, unsure = false;
            int end = at, body = at;
            for (int groups = 0; groups < 3; groups++) {
                int open = typeArguments(code, skipSpace(code, end));
                if (open < n && code.charAt(open) == '`' && table) {
                    int close = code.indexOf('`', open + 1);
                    if (close < 0) break;
                    end = close + 1;
                    continue;
                }
                if (open >= n || code.charAt(open) != '(') break;
                called = true;
                int close = Code.closing(code, open);
                int first = skipSpace(code, open + 1);
                char q = first < n ? code.charAt(first) : ' ';
                boolean tableCall = table && groups == 0 && q != '"' && q != '\'' && q != '`';
                if (tableCall && close >= 0) {
                    end = close + 1; // the table; the call with the title follows
                    continue;
                }
                // this is the call with the title
                if (q == '"' || q == '\'' || q == '`') {
                    int closing = code.indexOf(q, first + 1);
                    int after = closing < 0 ? n : skipSpace(code, closing + 1);
                    // plain when it is one string and nothing more: no ${...} in it, no sum of strings
                    if (closing > first && !code.substring(first, closing).contains("${") && after < n && (code.charAt(after) == ',' || code.charAt(after) == ')')) {
                        written = text.substring(first + 1, closing);
                        title = TestNames.unescaped(written);
                        body = closing + 1;
                    }
                }
                if (title == null) {
                    computed = expression(text, code, first);
                    body = first;
                }
                if (close < 0) unsure = true; // its end is somewhere the reader lost count of the brackets
                end = close < 0 ? -1 : close + 1;
                break;
            }
            if (!called) {
                if (switches.isEmpty()) continue; // the word used as a name, not called
                // a switch that is not followed by a call this reading knows: kept, named by what stands after it
                computed = expression(text, code, skipSpace(code, at));
                unsure = true;
                end = -1;
                body = at;
            }
            out.add(new Item(group, !group, title, written, computed, "", switches, table || title == null, m.start(), body, end, unsure));
        }
        // an end that was not found: a test is taken to end where the next declaration starts, a group at the end of the file
        List<Item> placed = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            Item item = out.get(i);
            if (item.end() >= 0) {
                placed.add(item);
                continue;
            }
            int end = item.group() || i + 1 == out.size() ? n : out.get(i + 1).start();
            placed.add(new Item(item.group(), item.test(), item.title(), item.written(), item.computed(), "", item.switches(), item.generated(), item.start(), item.body(), end, true));
        }
        return homes(placed);
    }

    /** The place after type arguments (<...>, balanced) that start here; the place itself when none do. */
    private static int typeArguments(String code, int at) {
        if (at >= code.length() || code.charAt(at) != '<') return at;
        int depth = 0;
        for (int i = at; i < Math.min(code.length(), at + 2000); i++) {
            char c = code.charAt(i);
            if (c == '<') depth++;
            else if (c == '>' && i > 0 && code.charAt(i - 1) != '=' && --depth == 0) return skipSpace(code, i + 1);
            else if (c == ';') break;
        }
        return at;
    }

    /** An expression as the file writes it, up to the comma or bracket that ends it (60 characters at most), white space taken out. */
    private static String expression(String text, String code, int from) {
        int depth = 0, i = from;
        for (; i < code.length() && i < from + 200; i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break;
                depth--;
            } else if (c == ',' && depth == 0) break;
        }
        String squeezed = text.substring(Math.min(from, text.length()), Math.min(i, text.length())).replaceAll("\\s+", "");
        return squeezed.length() > 60 ? squeezed.substring(0, 60) : squeezed;
    }

    private static final Pattern JAVA_TYPE = Pattern.compile("\\b(?:class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD = Pattern.compile("\\bvoid\\s+(\\w+)\\s*\\(");
    private static final Pattern JAVA_TEST = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    static final Pattern JAVA_OFF = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*)*(Disabled|DisabledIf\\w*|Ignore)\\b");
    /** An assumption that can never hold, with or without a reason after it, and an unconditional abort. */
    static final Pattern JAVA_ASSUME = Pattern.compile("\\bassume(?:True|That)\\s*\\(\\s*false\\s*[,)]|\\bassumeFalse\\s*\\(\\s*true\\s*[,)]|\\bAssumptions\\s*\\.\\s*abort\\s*\\(");
    private static final Pattern JAVA_ANNOTATION = Pattern.compile("@\\s*[\\w$.]+(?:\\s*\\([^()]*(?:\\([^()]*\\)[^()]*)*\\))?");
    private static final Pattern JAVA_CALLED = Pattern.compile("(\\w+)\\s*\\(");
    private static final Pattern JAVA_NAMED = Pattern.compile("(\\w+)\\s*(?:=|$)");

    /**
     * The classes and the void methods of a Java file, each with the classes around it. A method
     * is a test when a test annotation stands before it. An annotation that switches tests off
     * belongs to the declaration it stands on, whatever that is: when it is none of the above (a
     * method that returns a value, a field), it is kept under that declaration's own name. An
     * assumption that always fails belongs to the method it stands in, else to the class.
     */
    static List<Item> java(String path, String text) {
        List<Item> out = new ArrayList<>();
        if (text == null) return out;
        String code = Code.mask(path, text);
        Matcher m = JAVA_TYPE.matcher(code);
        while (m.find()) {
            int open = code.indexOf('{', m.end());
            int close = open < 0 ? -1 : Code.closing(code, open);
            if (open < 0) continue;
            out.add(new Item(true, false, m.group(1), m.group(1), null, "", new LinkedHashSet<>(), false, m.start(), open, close < 0 ? code.length() : close, close < 0));
        }
        m = JAVA_METHOD.matcher(code);
        while (m.find()) {
            int paren = Code.closing(code, m.end() - 1);
            int open = paren < 0 ? m.end() : paren + 1;
            while (open < code.length() && code.charAt(open) != '{' && code.charAt(open) != ';') open++;
            int close = open < code.length() && code.charAt(open) == '{' ? Code.closing(code, open) : open;
            out.add(new Item(false, false, m.group(1), m.group(1), null, "", new LinkedHashSet<>(), false, m.start(), Math.min(open, code.length()), close < 0 ? open : close, paren < 0 || close < 0));
        }
        out.sort(Comparator.comparingInt(Item::start));
        Set<Item> tests = new LinkedHashSet<>();
        List<Item> more = new ArrayList<>();
        m = JAVA_TEST.matcher(code);
        while (m.find()) {
            Item on = annotated(out, null, code, m.end());
            if (on != null && !on.group()) tests.add(on);
        }
        m = JAVA_OFF.matcher(code);
        while (m.find()) annotated(out, more, code, m.end()).switches().add("@" + m.group(1));
        m = JAVA_ASSUME.matcher(code);
        while (m.find()) {
            Item in = null;
            for (Item item : out) if (!item.group() && item.start() <= m.start() && m.start() <= item.end()) in = item;
            if (in == null) in = loose(more, "?", m.start());
            in.switches().add("assume");
        }
        List<Item> kept = new ArrayList<>();
        for (Item item : out) {
            boolean test = tests.contains(item);
            if (item.group() || test || !item.switches().isEmpty()) {
                kept.add(new Item(item.group(), test, item.title(), item.written(), null, "", item.switches(), false, item.start(), item.body(), item.end(), item.unsure()));
            }
        }
        kept.addAll(more);
        return homes(kept);
    }

    /**
     * The declaration an annotation stands on: what lies between the annotation and the next "{"
     * or ";" outside brackets. A class or a void method that starts there is returned. Anything
     * else is not searched past: with more given, it is kept there under its own name (a method
     * that returns a value, a field), so that a switch on it is neither lost nor put on the
     * declaration after it.
     */
    private static Item annotated(List<Item> items, List<Item> more, String code, int from) {
        int end = from, depth = 0;
        for (; end < code.length(); end++) {
            char c = code.charAt(end);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if ((c == '{' || c == ';') && depth <= 0) break;
        }
        for (Item item : items) if (item.start() >= from && item.start() < end) return item;
        if (more == null) return null;
        String header = JAVA_ANNOTATION.matcher(code.substring(from, end)).replaceAll(" ");
        Matcher called = JAVA_CALLED.matcher(header);
        Matcher named = JAVA_NAMED.matcher(header.strip());
        String name = called.find() ? called.group(1) : named.find() ? named.group(1) : "?";
        for (Item known : more) if (known.start() == from) return known;
        return loose(more, name, from);
    }

    private static Item loose(List<Item> more, String name, int at) {
        Item item = new Item(false, false, name, name, null, "", new LinkedHashSet<>(), false, at, at, at, false);
        more.add(item);
        return item;
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
            for (Item g : open) home.append(home.length() == 0 ? "" : " > ").append(g.name());
            Item placed = item.at(home.toString());
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
