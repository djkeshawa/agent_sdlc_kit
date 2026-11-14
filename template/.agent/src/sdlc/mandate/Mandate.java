package sdlc.mandate;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import sdlc.core.UsageException;
import sdlc.util.Glob;
import sdlc.util.Json;
import sdlc.util.Proc;

/**
 * A mandate: what one change is authorised to do (spec/mandate-v0.md). One JSON file per change,
 * docs/mandates/<id>.json. Its identity is the git blob id of the file's bytes, so nothing here
 * rewrites or normalises it. Parsing is strict: every problem of the file is reported, one per
 * line, and a mandate with any problem is not used.
 *
 * @param version      the value of "mandate": always "v0"
 * @param failsIf      the approver's sentence, or null when the file has none yet
 * @param apis         scope.apis exactly as written, or null when the file has none (not checked in version 0)
 * @param locked       oracle.locked: test file path to the blob id it must have; empty when the file has none
 * @param featureKey   feature.key, or null when the file has none
 */
public record Mandate(String version, String id, String intent, List<Truth> mustStayTrue, String failsIf, List<String> scopePaths,
        Map<String, Object> apis, List<Scenario> scenarios, Map<String, String> locked, String featureKey) {

    /** Where mandates live, relative to the repository root. */
    public static final String DIR = "docs/mandates";

    /** One "must stay true" line: the sentence, and the locked test that keeps it true. */
    public record Truth(String is, String test) {}

    /** A test that must pass: a test file, and one case in it, or every case when name is empty. */
    public record Scenario(String path, String name) {
        public boolean wholeFile() {
            return name.isEmpty();
        }

        @Override
        public String toString() {
            return name.isEmpty() ? path : path + "#" + name;
        }
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern BLOB = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final int MAX_SENTENCE = 300;
    private static final Set<String> TOP = Set.of("mandate", "id", "intent", "mustStayTrue", "failsIf", "scope", "oracle", "feature");
    private static final Set<String> SCOPE = Set.of("paths", "apis");
    private static final Set<String> ORACLE = Set.of("scenarios", "locked");
    private static final Set<String> TRUTH = Set.of("is", "test");
    private static final Set<String> FEATURE = Set.of("key");

    /** True when the path matches one of scope.paths: "**" crosses folders, "*" stays in one. */
    public boolean inScope(String path) {
        for (String glob : scopePaths) if (matches(glob, path)) return true;
        return false;
    }

    /**
     * Glob also lets a pattern match every path below what it matches ("src/*" would match
     * "src/a/b.java"), which is wider than the specification's "*" that stays in one folder. A
     * character that cannot be in a path, put at the end of both, leaves only whole matches.
     */
    static boolean matches(String glob, String path) {
        String end = "\u0001";
        if (path.contains(end) || glob.contains(end)) return false;
        // a backslash or a control character in a file's name: no path of a mandate can name it, so it is outside
        if (path.indexOf('\\') >= 0 || control(path)) return false;
        return Glob.any(List.of(glob + end), path + end);
    }

    /** docs/mandates/<id>.json under the repository root. The id is checked first. */
    public static Path file(Path root, String id) {
        requireId(id);
        return root.resolve(DIR).resolve(id + ".json");
    }

    /** Refuses an id the specification does not allow, before it is ever part of a path. */
    public static void requireId(String id) {
        String problem = idProblem(id);
        if (problem != null) throw new UsageException("the mandate id " + problem);
    }

    /** A mandate file may be this large and no larger: it is a page, not a document. */
    static final int MAX_BYTES = 256 * 1024;

    /**
     * A mandate as it was read: the parsed form, the text and the blob id, all of the same bytes.
     * What is shown, what is approved and what is checked must be one thing; reading the file once
     * and hashing those very bytes (no git filter in between) makes it so.
     */
    public record Loaded(Mandate mandate, String text, String blob) {}

    /** Reads docs/mandates/<id>.json once and parses it. The message of the error names the file and lists every problem. */
    public static Loaded loaded(Path root, String id) {
        String rel = DIR + "/" + id + ".json";
        String json = read(root, file(root, id), rel);
        List<String> problems = problems(id, json);
        if (!problems.isEmpty()) throw new UsageException(rel + " is not a valid mandate:\n  " + String.join("\n  ", problems));
        return new Loaded(build(id, json), json, blobOf(root, json));
    }

    public static Mandate load(Path root, String id) {
        return loaded(root, id).mandate();
    }

    /**
     * The file's text. Refused: a file that is not there, a link (it could name any file on the
     * machine, and approving would rewrite that file), a path that leaves the repository through a
     * linked folder, a file that is too large, and one that is not UTF-8 (its bytes would not mean
     * what they show).
     */
    static String read(Path root, Path f, String rel) {
        try {
            if (Files.isSymbolicLink(f)) throw new UsageException(rel + " is a link. A mandate is a file of its own.");
            if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new UsageException(rel + " does not exist.");
            if (!f.toRealPath().startsWith(root.toRealPath())) throw new UsageException(rel + " is reached through a link that leaves the repository.");
            if (Files.size(f) > MAX_BYTES) throw new UsageException(rel + " is larger than " + MAX_BYTES / 1024 + " KB. A mandate is one page.");
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(f))).toString();
        } catch (CharacterCodingException e) {
            throw new UsageException(rel + " is not UTF-8 text.");
        } catch (IOException e) {
            throw new UsageException(rel + " cannot be read: " + e.getMessage());
        }
    }

    /** The git blob id of the file's bytes as they are on disk. */
    public static String blobId(Path root, String id) {
        return loadedText(root, id).blob();
    }

    private static Loaded loadedText(Path root, String id) {
        String json = read(root, file(root, id), DIR + "/" + id + ".json");
        return new Loaded(null, json, blobOf(root, json));
    }

    /** git hash-object of a file of the working tree, as git would store it: what a locked test is compared with. */
    static String hashObject(Path root, String rel, Path f) {
        Proc p = Proc.run(root, "git", "hash-object", "--", f.toString());
        if (!p.ok()) throw new UsageException("cannot work out the blob id of " + rel + ": " + p.err().strip());
        return p.out().strip();
    }

    /** The git blob id of a text's UTF-8 bytes, exactly as given: no line-ending or other filter of the repository is applied. */
    public static String blobOf(Path root, String text) {
        Proc p = Proc.runWithInput(root, text, 60, "git", "hash-object", "--stdin");
        if (!p.ok() || p.out().isBlank()) throw new UsageException("cannot work out a blob id: " + p.err().strip());
        return p.out().strip();
    }

    /** Parses a mandate; the id is the one the file name gives. Every problem is in the message, one per line. */
    public static Mandate parse(String id, String json) {
        List<String> problems = problems(id, json);
        if (!problems.isEmpty()) throw new UsageException(String.join("\n", problems));
        return build(id, json);
    }

    /** Every problem of the file, in plain words; empty when the mandate is valid. */
    public static List<String> problems(String id, String json) {
        List<String> out = new ArrayList<>();
        Object root;
        try {
            root = Json.parseStrict(json);
        } catch (IllegalArgumentException e) {
            out.add("the file is not valid JSON: " + e.getMessage());
            return out;
        }
        if (!(root instanceof Map<?, ?>)) {
            out.add("the file must hold one JSON object, { ... }");
            return out;
        }
        Map<String, Object> m = Json.obj(root);
        unknownKeys(out, m, TOP, "");
        values(out, m, "");

        Object v = m.get("mandate");
        if (!m.containsKey("mandate")) out.add("mandate is missing; it must be \"v0\"");
        else if (!"v0".equals(v)) out.add("mandate must be \"v0\", not " + shown(v));

        if (!m.containsKey("id")) out.add("id is missing");
        else if (!(m.get("id") instanceof String s)) out.add("id must be text, not " + kind(m.get("id")));
        else {
            String p = idProblem(s);
            if (p != null) out.add("id " + p);
            else if (id != null && !s.equals(id)) out.add("id \"" + s + "\" does not match the file name " + id + ".json");
        }

        sentence(out, m, "intent", "intent", true);
        sentence(out, m, "failsIf", "failsIf", false);

        Map<String, Object> locked = null;
        if (!m.containsKey("oracle")) out.add("oracle is missing; it must name at least one test in oracle.scenarios");
        else if (!(m.get("oracle") instanceof Map<?, ?>)) out.add("oracle must be an object, { ... }, not " + kind(m.get("oracle")));
        else {
            Map<String, Object> o = Json.obj(m.get("oracle"));
            unknownKeys(out, o, ORACLE, "oracle");
            List<Object> sc = list(out, o, "scenarios", "oracle.scenarios", true);
            for (int k = 0; sc != null && k < sc.size(); k++) scenario(out, sc.get(k), "oracle.scenarios[" + k + "]");
            if (o.containsKey("locked")) {
                if (!(o.get("locked") instanceof Map<?, ?>)) out.add("oracle.locked must be an object, { \"path\": \"blob id\" }, not " + kind(o.get("locked")));
                else {
                    locked = Json.obj(o.get("locked"));
                    for (Map.Entry<String, Object> e : locked.entrySet()) {
                        String where = "oracle.locked[\"" + e.getKey() + "\"]";
                        path(out, e.getKey(), "oracle.locked key \"" + e.getKey() + "\"", false);
                        if (!(e.getValue() instanceof String b)) out.add(where + " must be a blob id as text, not " + kind(e.getValue()));
                        else if (!BLOB.matcher(b).matches()) out.add(where + " \"" + b + "\" is not a git blob id: 40 (or 64) characters of 0-9 and a-f");
                    }
                }
            }
        }

        if (!m.containsKey("scope")) out.add("scope is missing; it must list the paths the change may touch in scope.paths");
        else if (!(m.get("scope") instanceof Map<?, ?>)) out.add("scope must be an object, { ... }, not " + kind(m.get("scope")));
        else {
            Map<String, Object> s = Json.obj(m.get("scope"));
            unknownKeys(out, s, SCOPE, "scope");
            List<Object> paths = list(out, s, "paths", "scope.paths", true);
            for (int k = 0; paths != null && k < paths.size(); k++) {
                Object p = paths.get(k);
                String where = "scope.paths[" + k + "]";
                if (!(p instanceof String str)) out.add(where + " must be text, not " + kind(p));
                else path(out, str, where, true);
            }
            if (s.containsKey("apis") && !(s.get("apis") instanceof Map<?, ?>)) out.add("scope.apis must be an object, { ... }, not " + kind(s.get("apis")));
        }

        if (m.containsKey("mustStayTrue")) {
            List<Object> truths = list(out, m, "mustStayTrue", "mustStayTrue", false);
            for (int k = 0; truths != null && k < truths.size(); k++) truth(out, truths.get(k), "mustStayTrue[" + k + "]", locked);
        }

        if (m.containsKey("feature")) {
            if (!(m.get("feature") instanceof Map<?, ?>)) out.add("feature must be an object, { \"key\": \"...\" }, not " + kind(m.get("feature")));
            else {
                Map<String, Object> f = Json.obj(m.get("feature"));
                unknownKeys(out, f, FEATURE, "feature");
                if (f.containsKey("key")) {
                    if (!(f.get("key") instanceof String k)) out.add("feature.key must be text, not " + kind(f.get("key")));
                    else if (k.isBlank()) out.add("feature.key is empty");
                }
            }
        }
        return out;
    }

    /** Builds the record from a file that has no problems. */
    private static Mandate build(String id, String json) {
        Map<String, Object> m = Json.obj(Json.parseStrict(json));
        Map<String, Object> scope = Json.obj(m.get("scope"));
        Map<String, Object> oracle = Json.obj(m.get("oracle"));
        List<Truth> truths = new ArrayList<>();
        for (Object t : Json.list(m.get("mustStayTrue"))) {
            Map<String, Object> e = Json.obj(t);
            truths.add(new Truth((String) e.get("is"), (String) e.get("test")));
        }
        List<String> paths = new ArrayList<>();
        for (Object p : Json.list(scope.get("paths"))) paths.add((String) p);
        List<Scenario> scenarios = new ArrayList<>();
        for (Object s : Json.list(oracle.get("scenarios"))) scenarios.add(scenarioOf((String) s));
        Map<String, String> locked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.map(oracle.get("locked")).entrySet()) locked.put(e.getKey(), (String) e.getValue());
        Map<String, Object> apis = scope.get("apis") == null ? null : Collections.unmodifiableMap(Json.obj(scope.get("apis")));
        Object feature = Json.map(m.get("feature")).get("key");
        return new Mandate((String) m.get("mandate"), (String) m.get("id"), (String) m.get("intent"), List.copyOf(truths), (String) m.get("failsIf"),
                List.copyOf(paths), apis, List.copyOf(scenarios), Collections.unmodifiableMap(locked), (String) feature);
    }

    private static Scenario scenarioOf(String s) {
        int hash = s.indexOf('#');
        return hash < 0 ? new Scenario(s, "") : new Scenario(s.substring(0, hash), s.substring(hash + 1));
    }

    static String idProblem(String id) {
        if (id == null || id.isEmpty()) return "is empty";
        if (id.length() > 64) return "\"" + id + "\" is longer than 64 characters";
        if (!ID.matcher(id).matches()) return "\"" + id + "\" may hold only letters, digits, \".\", \"_\" and \"-\", and must start with a letter or digit";
        return null;
    }

    private static void unknownKeys(List<String> out, Map<String, Object> m, Set<String> known, String where) {
        for (String k : m.keySet()) {
            if (known.contains(k)) continue;
            out.add(where.isEmpty() ? "unknown key \"" + k + "\" at the top level" : where + " has an unknown key \"" + k + "\"");
        }
    }

    private static void sentence(List<String> out, Map<String, Object> m, String key, String where, boolean required) {
        if (!m.containsKey(key)) {
            if (required) out.add(where + " is missing");
            return;
        }
        Object v = m.get(key);
        if (!(v instanceof String s)) {
            out.add(where + " must be text, not " + kind(v));
            return;
        }
        int n = s.codePointCount(0, s.length());
        if (s.isBlank()) out.add(where + " is empty");
        else if (n > MAX_SENTENCE) out.add(where + " is " + n + " characters long; at most " + MAX_SENTENCE + " are allowed");
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) out.add(where + " has a line break; write one sentence on one line");
    }

    private static List<Object> list(List<String> out, Map<String, Object> m, String key, String where, boolean required) {
        if (!m.containsKey(key)) {
            if (required) out.add(where + " is missing");
            return null;
        }
        Object v = m.get(key);
        if (!(v instanceof List<?>)) {
            out.add(where + " must be a list, [ ... ], not " + kind(v));
            return null;
        }
        List<Object> l = Json.list(v);
        if (l.isEmpty() && required) out.add(where + " is empty; name at least one");
        return l;
    }

    private static void scenario(List<String> out, Object v, String where) {
        if (!(v instanceof String s)) {
            out.add(where + " must be text, not " + kind(v));
            return;
        }
        int hash = s.indexOf('#');
        String p = hash < 0 ? s : s.substring(0, hash);
        path(out, p, where + " \"" + s + "\": the file", false);
        if (hash >= 0 && hash == s.length() - 1) out.add(where + " \"" + s + "\" has no test name after \"#\"");
        if (hash >= 0 && s.indexOf('#', hash + 1) >= 0) out.add(where + " \"" + s + "\" has more than one \"#\"");
    }

    private static void truth(List<String> out, Object v, String where, Map<String, Object> locked) {
        if (!(v instanceof Map<?, ?>)) {
            out.add(where + " must be an object, { \"is\": \"...\", \"test\": \"...\" }, not " + kind(v));
            return;
        }
        Map<String, Object> t = Json.obj(v);
        unknownKeys(out, t, TRUTH, where);
        sentence(out, t, "is", where + ".is", true);
        if (!t.containsKey("test")) out.add(where + ".test is missing");
        else if (!(t.get("test") instanceof String test)) out.add(where + ".test must be text, not " + kind(t.get("test")));
        else {
            int before = out.size();
            path(out, test, where + ".test", false);
            if (out.size() == before && (locked == null || !locked.containsKey(test)))
                out.add(where + ".test \"" + test + "\" is not in oracle.locked; a test that keeps something true must be locked");
        }
    }

    /**
     * Rules for every value, wherever it sits. No numbers: nothing in a mandate is a quantity, and a
     * number can change its spelling when the file is written again. No control characters in any
     * text or key: a mandate is shown on a terminal, where a line break or an escape sequence could
     * imitate the tool's own lines.
     */
    private static void values(List<String> out, Object v, String where) {
        if (v instanceof Number) {
            out.add((where.isEmpty() ? "the file" : where) + " holds a number; a mandate has none (write it as text)");
        } else if (v instanceof String s) {
            if (control(s)) out.add((where.isEmpty() ? "the file" : where) + " has a control character (a line break, a tab, an escape, or one that is invisible or turns the direction of the text); write it as plain text on one line");
        } else if (v instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (control(key)) out.add("a key under " + (where.isEmpty() ? "the top level" : where) + " has a control character");
                else values(out, e.getValue(), where.isEmpty() ? key : where + "." + key);
            }
        } else if (v instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) values(out, list.get(i), where + "[" + i + "]");
        }
    }

    private static boolean control(String s) {
        return s.codePoints().anyMatch(Mandate::unprintable) || s.chars().anyMatch(c -> Character.isSurrogate((char) c) && !paired(s));
    }

    /**
     * A character that must not reach a terminal or a card: a control character, a line or
     * paragraph separator, and every "format" character. The last group holds the marks that
     * turn the direction of text (so a path or a sentence reads in another order than it is
     * stored) and the ones with no width (so two different paths look the same).
     */
    public static boolean unprintable(int codePoint) {
        return sdlc.util.SafeOut.unprintable(codePoint);
    }

    /** True when every surrogate of the text is half of a pair. */
    private static boolean paired(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) i++;
            else if (Character.isSurrogate(c)) return false;
        }
        return true;
    }

    /** The rules every path of the file follows: relative to the repository root, and never leaving it. */
    private static void path(List<String> out, String p, String where, boolean glob) {
        String shown = where.contains("\"") ? where : where + " \"" + p + "\"";
        if (p.isEmpty()) out.add(where + " is empty");
        else if (p.indexOf('\\') >= 0) out.add(shown + " has a backslash; use \"/\" between folders");
        else if (p.startsWith("/")) out.add(shown + " starts with \"/\"; write it relative to the repository root");
        else if (p.matches("[A-Za-z]:.*")) out.add(shown + " starts with a drive letter; write it relative to the repository root");
        else if (("/" + p + "/").contains("/../") || p.equals("..")) out.add(shown + " leaves the repository");
        else if (control(p)) out.add(shown + " has a control character");
        if (!glob && !p.isEmpty() && p.endsWith("/")) out.add(shown + " ends with \"/\"; name a file");
        // the specification has two wildcards and nothing else
        if (glob && p.chars().anyMatch(c -> "{}[]?".indexOf(c) >= 0)) out.add(shown + " uses a sign that is not part of a mandate's paths; only * and ** are");
    }

    private static String kind(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "text";
        if (v instanceof Double || v instanceof Number) return "a number";
        if (v instanceof Boolean) return "true or false";
        if (v instanceof List<?>) return "a list";
        if (v instanceof Map<?, ?>) return "an object";
        return v.getClass().getSimpleName();
    }

    private static String shown(Object v) {
        return v instanceof String s ? "\"" + s + "\"" : kind(v);
    }
}
