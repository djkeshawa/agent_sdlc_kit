package sdlc.check;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tests a Java source file declares, read from its code (comments and the insides of strings
 * are not code): each class and each test method with the classes it stands in, and the switches
 * on it that skip it.
 *
 * A test is known by where it stands and what it is called ("RefundsTest > Partial > roundsDown"),
 * not by its name alone: two nested classes may each hold a test of the same name.
 *
 * Two rules keep a reading of tokens honest. A switch that is seen is never dropped: when the
 * declaration it stands on is no class or test method, it is kept under that declaration's own
 * name. And what could not be read to its end is marked unsure, so that callers do not build on
 * it. Test files of other languages are not read: their run says which tests they hold.
 */
final class Declared {
    private Declared() {}

    /**
     * One declaration: a class (group) or a method. home names the classes around it, outermost
     * first, joined by " > ". test is false for a class, and for a declaration that is no test but
     * carries a switch. body starts after its header. unsure: its end could not be found, so its
     * extent is a guess.
     */
    record Item(boolean group, boolean test, String name, String home, Set<String> switches, int start, int body, int end, boolean unsure) {
        /** Where it stands and what it is called. */
        String where() {
            return home.isEmpty() ? name : home + " > " + name;
        }

        Item at(String home) {
            return new Item(group, test, name, home, switches, start, body, end, unsure);
        }
    }

    private static final Pattern JAVA_TYPE = Pattern.compile("\\b(?:class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD = Pattern.compile("\\bvoid\\s+(\\w+)\\s*\\(");
    private static final Pattern JAVA_TEST = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern JAVA_OFF = Pattern.compile("@\\s*(?:[\\w$]+\\s*\\.\\s*)*(Disabled|DisabledIf\\w*|Ignore)\\b");
    /** A call that may be an assumption that can never hold; its arguments decide (never). assumeThat(actual, matcher) is none: a matcher decides it. */
    private static final Pattern JAVA_ASSUME_CALL = Pattern.compile("\\b(assumeTrue|assumeFalse)\\s*\\(");
    private static final Pattern JAVA_ABORT = Pattern.compile("\\bAssumptions\\s*\\.\\s*abort\\s*\\(");
    private static final Pattern JAVA_ABORT_IMPORTED = Pattern.compile("(?<![\\w.])abort\\s*\\(");
    private static final Pattern JAVA_ABORT_IMPORT = Pattern.compile("\\bimport\\s+static\\s+org\\.junit\\.jupiter\\.api\\.Assumptions\\.(?:abort|\\*)\\s*;");

    /**
     * The places of a Java file where a test gives up without a condition: assumeTrue with a plain
     * false among its arguments (JUnit 5 puts the reason after it, JUnit 4 before it), assumeFalse
     * with a plain true, Assumptions.abort, and abort where that method is statically imported.
     */
    private static List<Integer> never(String code) {
        List<Integer> out = new ArrayList<>();
        Matcher m = JAVA_ASSUME_CALL.matcher(code);
        while (m.find()) {
            int open = m.end() - 1, close = Code.closing(code, open);
            if (close < 0) continue;
            String wanted = m.group(1).equals("assumeTrue") ? "false" : "true";
            int depth = 0, from = open + 1;
            for (int i = open + 1; i <= close; i++) {
                char c = code.charAt(i);
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') depth--;
                if (i == close || c == ',' && depth == 0) {
                    if (code.substring(from, i).strip().equals(wanted)) {
                        out.add(m.start());
                        break;
                    }
                    from = i + 1;
                }
            }
        }
        m = JAVA_ABORT.matcher(code);
        while (m.find()) out.add(m.start());
        if (JAVA_ABORT_IMPORT.matcher(code).find()) {
            m = JAVA_ABORT_IMPORTED.matcher(code);
            while (m.find()) out.add(m.start());
        }
        java.util.Collections.sort(out);
        return out;
    }

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
    static List<Item> java(String text) {
        List<Item> out = new ArrayList<>();
        if (text == null) return out;
        String code = Code.mask(text);
        Matcher m = JAVA_TYPE.matcher(code);
        while (m.find()) {
            int open = code.indexOf('{', m.end());
            int close = open < 0 ? -1 : Code.closing(code, open);
            if (open < 0) continue;
            out.add(new Item(true, false, m.group(1), "", new LinkedHashSet<>(), m.start(), open, close < 0 ? code.length() : close, close < 0));
        }
        m = JAVA_METHOD.matcher(code);
        while (m.find()) {
            int paren = Code.closing(code, m.end() - 1);
            int open = paren < 0 ? m.end() : paren + 1;
            while (open < code.length() && code.charAt(open) != '{' && code.charAt(open) != ';') open++;
            int close = open < code.length() && code.charAt(open) == '{' ? Code.closing(code, open) : open;
            out.add(new Item(false, false, m.group(1), "", new LinkedHashSet<>(), m.start(), Math.min(open, code.length()), close < 0 ? open : close, paren < 0 || close < 0));
        }
        out.sort(Comparator.comparingInt(Item::start));
        // by where each starts: a record that holds a set changes its hash when the set does, so the records themselves are no keys
        Set<Integer> tests = new LinkedHashSet<>();
        List<Item> more = new ArrayList<>();
        m = JAVA_TEST.matcher(code);
        while (m.find()) {
            Item on = annotated(out, null, code, m.end());
            if (on != null && !on.group()) tests.add(on.start());
        }
        m = JAVA_OFF.matcher(code);
        while (m.find()) annotated(out, more, code, m.end()).switches().add("@" + m.group(1));
        for (int at : never(code)) {
            Item in = null;
            for (Item item : out) if (!item.group() && item.start() <= at && at <= item.end()) in = item;
            if (in == null) in = loose(more, "?", at);
            in.switches().add("assume");
        }
        List<Item> kept = new ArrayList<>();
        for (Item item : out) {
            boolean test = tests.contains(item.start());
            if (item.group() || test || !item.switches().isEmpty()) {
                kept.add(new Item(item.group(), test, item.name(), "", item.switches(), item.start(), item.body(), item.end(), item.unsure()));
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

    /** True when the file cannot be relied on for what stands where: its brackets do not pair up, or a declaration could not be read to its end. */
    static boolean unsure(String text) {
        if (text == null) return false;
        if (!Code.balanced(Code.mask(text))) return true;
        return java(text).stream().anyMatch(Item::unsure);
    }

    private static Item loose(List<Item> more, String name, int at) {
        Item item = new Item(false, false, name, "", new LinkedHashSet<>(), at, at, at, false);
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
}
