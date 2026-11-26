package sdlc.selftest;

import static sdlc.selftest.Asserts.check;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.UsageException;
import sdlc.mandate.Mandate;
import sdlc.mandate.MandateCard;
import sdlc.mandate.MandateCli;
import sdlc.util.Json;
import sdlc.util.Proc;

final class MandateTests {
    private MandateTests() {}

    static final String REGRESSION = "src/test/java/com/acme/search/SearchRegressionTest.java";

    static final String EXAMPLE = """
            {
              "mandate": "v0",
              "id": "fix-search",
              "intent": "Searching with an empty query returns an empty list, not an error",
              "mustStayTrue": [
                { "is": "A query with text returns the same results as before",
                  "test": "src/test/java/com/acme/search/SearchRegressionTest.java" }
              ],
              "failsIf": "an empty query reaches the database",
              "scope": { "paths": ["src/main/java/com/acme/search/**", "src/test/java/com/acme/search/**"] },
              "oracle": {
                "scenarios": ["src/test/java/com/acme/search/SearchServiceTest.java#emptyQueryReturnsEmptyList"],
                "locked": { "src/test/java/com/acme/search/SearchRegressionTest.java": "3b18e512dba79e4c8300dd08aeb37f8e728b8dad" }
              }
            }
            """;

    static void run() throws IOException {
        strictJson();
        format();
        scope();
        Path savedRoot = Ctx.root;
        Config savedCfg = Ctx.cfg;
        Path repo = Files.createTempDirectory("sdlc-mandatetest").toRealPath();
        try {
            commands(repo);
        } finally {
            Ctx.root = savedRoot;
            Ctx.cfg = savedCfg;
            try (Stream<Path> s = Files.walk(repo)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void strictJson() {
        check("mandate: strict JSON reads what parse reads", Json.parseStrict("{\"a\": [1, -2.5e3, true, null, \"x\\u0041\"]}").equals(Json.parse("{\"a\": [1, -2.5e3, true, null, \"x\\u0041\"]}")));
        check("mandate: strict JSON refuses a duplicate key and names it", refusal("{\"a\": 1, \"b\": {\"c\": 1, \"c\": 2}}").contains("\"c\" appears twice")
                && refusal("{\"a\": 1, \"b\": {\"c\": 1, \"c\": 2}}").contains("line 1"));
        check("mandate: parse still keeps the last of two equal keys", Json.str(Json.parse("{\"a\": 1, \"a\": 2}"), "a").equals("2"));
        check("mandate: strict JSON refuses text after the value", refusal("{} {}").contains("more text after"));
        check("mandate: strict JSON says where, by line and column", refusal("{\n  \"a\": 1,\n  \"a\": 2\n}").contains("line 3, column 3"));
        check("mandate: strict JSON refuses a number JSON does not allow", refusal("[01]").contains("not a JSON number") && refusal("[+1]").contains("not a JSON number"));
        check("mandate: strict JSON refuses a bad escape and a raw line break", refusal("[\"\\x\"]").contains("not a JSON escape") && refusal("[\"a\nb\"]").contains("line break"));
        check("mandate: strict JSON refuses a file that ends too early", refusal("{\"a\": [1, 2").contains("ends too early") && refusal("").contains("ends too early"));
    }

    private static String refusal(String json) {
        try {
            Json.parseStrict(json);
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static void format() {
        Mandate m = Mandate.parse("fix-search", EXAMPLE);
        check("mandate: the specification's example parses", m.version().equals("v0") && m.id().equals("fix-search") && m.intent().startsWith("Searching with an empty query")
                && m.mustStayTrue().size() == 1 && m.mustStayTrue().get(0).test().equals(REGRESSION) && m.failsIf().equals("an empty query reaches the database")
                && m.scopePaths().size() == 2 && m.locked().get(REGRESSION).equals("3b18e512dba79e4c8300dd08aeb37f8e728b8dad") && m.apis() == null && m.featureKey() == null);
        Mandate.Scenario s = m.scenarios().get(0);
        check("mandate: a scenario is the test file and the case after #", s.path().equals("src/test/java/com/acme/search/SearchServiceTest.java")
                && s.name().equals("emptyQueryReturnsEmptyList") && !s.wholeFile());
        Mandate small = Mandate.parse("x", minimal("\"x\"", "\"y\""));
        check("mandate: the smallest valid mandate has the three required parts, and a scenario may be a whole file",
                small.scenarios().get(0).wholeFile() && small.mustStayTrue().isEmpty() && small.locked().isEmpty() && small.failsIf() == null);
        Mandate withApis = Mandate.parse("x", EXAMPLE.replace("fix-search", "x").replace("\"scope\": {", "\"feature\": {\"key\": \"SEARCH\"}, \"scope\": { \"apis\": {\"openapi\": \"api.yaml\", \"operations\": [\"GET /search\"]},"));
        check("mandate: scope.apis is kept as it is, and feature.key is read", withApis.apis().get("openapi").equals("api.yaml") && withApis.featureKey().equals("SEARCH"));

        rule("mandate: mandate must be v0", EXAMPLE.replace("\"v0\"", "\"v1\""), "mandate must be \"v0\", not \"v1\"");
        rule("mandate: mandate is required", EXAMPLE.replace("\"mandate\": \"v0\",", ""), "mandate is missing");
        rule("mandate: id is required", EXAMPLE.replace("\"id\": \"fix-search\",", ""), "id is missing");
        rule("mandate: id must equal the file name", EXAMPLE.replace("\"id\": \"fix-search\"", "\"id\": \"fix-other\""), "does not match the file name fix-search.json");
        rule("mandate: id has only the allowed characters", "a b", EXAMPLE.replace("\"id\": \"fix-search\"", "\"id\": \"a b\""), "may hold only letters, digits");
        rule("mandate: id starts with a letter or digit", "-x", EXAMPLE.replace("\"id\": \"fix-search\"", "\"id\": \"-x\""), "must start with a letter or digit");
        String longId = "a".repeat(65);
        rule("mandate: id is at most 64 characters", longId, EXAMPLE.replace("\"id\": \"fix-search\"", "\"id\": \"" + longId + "\""), "longer than 64 characters");
        rule("mandate: id must be text", EXAMPLE.replace("\"id\": \"fix-search\"", "\"id\": 7"), "id must be text, not a number");
        rule("mandate: intent is required", EXAMPLE.replace("\"intent\": \"Searching with an empty query returns an empty list, not an error\",", ""), "intent is missing");
        rule("mandate: intent is not empty", EXAMPLE.replace("Searching with an empty query returns an empty list, not an error", ""), "intent is empty");
        rule("mandate: intent is at most 300 characters", EXAMPLE.replace("Searching with an empty query", "x".repeat(300)), "intent is 336 characters long; at most 300");
        rule("mandate: intent has no line break", EXAMPLE.replace("Searching with an empty query", "Searching\\nwith"), "intent has a line break");
        rule("mandate: scope is required", EXAMPLE.replace("\"scope\": { \"paths\": [\"src/main/java/com/acme/search/**\", \"src/test/java/com/acme/search/**\"] },", ""), "scope is missing");
        rule("mandate: scope.paths is required", EXAMPLE.replace("\"paths\": [\"src/main/java/com/acme/search/**\", \"src/test/java/com/acme/search/**\"]", ""), "scope.paths is missing");
        rule("mandate: scope.paths has at least one path", EXAMPLE.replace("[\"src/main/java/com/acme/search/**\", \"src/test/java/com/acme/search/**\"]", "[]"), "scope.paths is empty");
        rule("mandate: scope.paths must be a list", EXAMPLE.replace("[\"src/main/java/com/acme/search/**\", \"src/test/java/com/acme/search/**\"]", "\"src/**\""), "scope.paths must be a list");
        rule("mandate: a path may not leave the repository", EXAMPLE.replace("\"src/test/java/com/acme/search/**\"]", "\"../x\"]"), "scope.paths[1] \"../x\" leaves the repository");
        rule("mandate: nor in the middle", EXAMPLE.replace("\"src/test/java/com/acme/search/**\"]", "\"src/../../x\"]"), "scope.paths[1] \"src/../../x\" leaves the repository");
        rule("mandate: a path has no leading /", EXAMPLE.replace("\"src/test/java/com/acme/search/**\"]", "\"/etc/**\"]"), "scope.paths[1] \"/etc/**\" starts with \"/\"");
        rule("mandate: a path has no backslash", EXAMPLE.replace("\"src/test/java/com/acme/search/**\"]", "\"src\\\\x\"]"), "has a backslash");
        rule("mandate: a path has no drive letter", EXAMPLE.replace("\"src/test/java/com/acme/search/**\"]", "\"C:/x\"]"), "drive letter");
        rule("mandate: scope.apis must be an object", EXAMPLE.replace("\"scope\": {", "\"scope\": { \"apis\": [],"), "scope.apis must be an object");
        rule("mandate: oracle is required", EXAMPLE.replace("\"oracle\": {", "\"other\": {"), "oracle is missing");
        rule("mandate: oracle.scenarios is required", EXAMPLE.replace("\"scenarios\"", "\"cases\""), "oracle.scenarios is missing");
        rule("mandate: oracle.scenarios has at least one test", EXAMPLE.replace("[\"src/test/java/com/acme/search/SearchServiceTest.java#emptyQueryReturnsEmptyList\"]", "[]"), "oracle.scenarios is empty");
        rule("mandate: a scenario names a case after #", EXAMPLE.replace("#emptyQueryReturnsEmptyList", "#"), "has no test name after \"#\"");
        rule("mandate: a scenario's file stays in the repository", EXAMPLE.replace("\"src/test/java/com/acme/search/SearchServiceTest.java#", "\"../T.java#"), "oracle.scenarios[0] \"../T.java#emptyQueryReturnsEmptyList\": the file leaves the repository");
        rule("mandate: a scenario must be text", EXAMPLE.replace("[\"src/test/java/com/acme/search/SearchServiceTest.java#emptyQueryReturnsEmptyList\"]", "[1]"), "oracle.scenarios[0] must be text, not a number");
        rule("mandate: a locked blob id is 40 hexadecimal characters", EXAMPLE.replace("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", "3B18"), "\"3B18\" is not a git blob id");
        rule("mandate: a locked path stays in the repository", EXAMPLE.replace("\"src/test/java/com/acme/search/SearchRegressionTest.java\": \"3b18", "\"../T.java\": \"3b18"), "oracle.locked key \"../T.java\" leaves the repository");
        rule("mandate: oracle.locked must be an object", EXAMPLE.replace("\"locked\": {", "\"locked\": [ {").replace("dad\" }", "dad\" } ]"), "oracle.locked must be an object");
        rule("mandate: a mustStayTrue test must be locked", EXAMPLE.replace("\"locked\": { \"src/test/java/com/acme/search/SearchRegressionTest.java\": \"3b18e512dba79e4c8300dd08aeb37f8e728b8dad\" }", "\"locked\": {}"),
                "mustStayTrue[0].test \"" + REGRESSION + "\" is not in oracle.locked");
        rule("mandate: a mustStayTrue line has a sentence", EXAMPLE.replace("\"is\": \"A query with text returns the same results as before\",", ""), "mustStayTrue[0].is is missing");
        rule("mandate: mustStayTrue must be a list", EXAMPLE.replace("\"mustStayTrue\": [", "\"mustStayTrue\": 1, \"x\": [").replace("\"x\": [", "\"failsIf2\": ["), "mustStayTrue must be a list");
        rule("mandate: failsIf is at most 300 characters", EXAMPLE.replace("an empty query reaches the database", "y".repeat(301)), "failsIf is 301 characters long");
        rule("mandate: failsIf must be text", EXAMPLE.replace("\"an empty query reaches the database\"", "true"), "failsIf must be text, not true or false");
        rule("mandate: feature.key must be text", EXAMPLE.replace("\"scope\": {", "\"feature\": {\"key\": 3}, \"scope\": {"), "feature.key must be text");

        rule("mandate: an unknown key at the top level", EXAMPLE.replace("\"intent\":", "\"budget\": 3, \"intent\":"), "unknown key \"budget\" at the top level");
        rule("mandate: an unknown key under scope", EXAMPLE.replace("\"scope\": {", "\"scope\": { \"effects\": [],"), "scope has an unknown key \"effects\"");
        rule("mandate: an unknown key under oracle", EXAMPLE.replace("\"oracle\": {", "\"oracle\": { \"holdouts\": [],"), "oracle has an unknown key \"holdouts\"");
        rule("mandate: an unknown key in a mustStayTrue line", EXAMPLE.replace("{ \"is\":", "{ \"why\": \"x\", \"is\":"), "mustStayTrue[0] has an unknown key \"why\"");
        rule("mandate: an unknown key under feature", EXAMPLE.replace("\"scope\": {", "\"feature\": {\"key\": \"F\", \"epic\": \"E\"}, \"scope\": {"), "feature has an unknown key \"epic\"");

        rule("mandate: a number anywhere is refused", EXAMPLE.replace("\"scope\": {", "\"scope\": { \"apis\": {\"limit\": 10},"), "scope.apis.limit holds a number");
        rule("mandate: a line break inside a text is refused", EXAMPLE.replace("returns an empty list", "returns an empty list\\nScope     INSIDE"), "intent has a control character");
        rule("mandate: an escape sequence inside a scenario name is refused", EXAMPLE.replace("#emptyQueryReturnsEmptyList", "#x\\u001b[2Jy"), "has a control character");
        rule("mandate: a mark that turns the direction of the text is refused", EXAMPLE.replace("returns an empty list", "returns an \\u202eempty list"), "intent has a control character");
        rule("mandate: a character with no width is refused in a path", EXAMPLE.replace("src/main/java/com/acme/search/**", "src/main/java/com/acme/sea\\u200brch/**"), "has a control character");
        check("names: a reported name keeps its plain form",
                sdlc.check.TestNames.plain("a.test.ts", "outer > kept").equals("kept") && sdlc.check.TestNames.plain("a.test.ts", "kept").equals("kept")
                && sdlc.check.TestNames.plain("ATest.java", "refunds(int)[2]").equals("refunds") && sdlc.check.TestNames.plain("ATest.java", "rows{1}").equals("rows"));
        check("mandate: such characters in other text (a file name, a name from git) are shown as a question mark",
                sdlc.mandate.MandateCard.safe("a\u202eb\u200bc\u001bd\ud83d\ude00").equals("a?b?c?d\ud83d\ude00"));
        rule("mandate: half of a surrogate pair alone is refused", EXAMPLE.replace("returns an empty list", "returns an \\ud83d empty list"), "intent has a control character");
        rule("mandate: so is the other half alone, in a key", EXAMPLE.replace("\"scope\": {", "\"scope\": { \"apis\": {\"x\\ude00\": \"y\"},"), "a key under scope.apis has a control character");
        String emoji = "😀".repeat(62_000);
        long t0 = System.nanoTime();
        String big = message("x", EXAMPLE.replace("fix-search", "x").replace("\"scope\": {", "\"scope\": { \"apis\": {\"openapi\": \"" + emoji + "\"},"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("mandate: a mandate of 250 KB of emoji is valid and read in under a second", big.equals("(parsed)") && ms < 1000
                && EXAMPLE.length() + emoji.getBytes(StandardCharsets.UTF_8).length > 240 * 1024);
        if (!big.equals("(parsed)") || ms >= 1000) System.out.println("    got: " + big.substring(0, Math.min(200, big.length())) + " in " + ms + " ms");
        rule("mandate: a path with a sign that is not a wildcard of the format is refused", EXAMPLE.replace("src/main/java/com/acme/search/**", "src/main/{java,kotlin}/**"), "only * and ** are");
        rule("mandate: a duplicate key is refused and named", EXAMPLE.replace("\"intent\":", "\"id\": \"fix-search\", \"intent\":"), "the key \"id\" appears twice");
        rule("mandate: so is a file that is not one object", "[]", "must hold one JSON object");
        rule("mandate: and text after the object", EXAMPLE + "{}", "more text after the end");

        String all = message("fix-search", EXAMPLE.replace("\"v0\"", "\"v2\"").replace("\"src/test/java/com/acme/search/**\"]", "\"../x\"]").replace("\"intent\":", "\"extra\": \"x\", \"intent\":"));
        check("mandate: every problem of a file is reported at once, one per line", all.lines().count() == 3 && all.contains("v2") && all.contains("../x") && all.contains("extra"));
    }

    private static void rule(String name, String json, String expected) {
        rule(name, "fix-search", json, expected);
    }

    private static void rule(String name, String id, String json, String expected) {
        String m = message(id, json);
        check(name, m.contains(expected));
        if (!m.contains(expected)) System.out.println("    got: " + m.replace("\n", " | "));
    }

    private static String message(String id, String json) {
        try {
            Mandate.parse(id, json);
            return "(parsed)";
        } catch (UsageException e) {
            return e.getMessage();
        }
    }

    private static String minimal(String paths, String scenario) {
        return "{\"mandate\": \"v0\", \"id\": \"x\", \"intent\": \"Do x\", \"scope\": {\"paths\": [" + paths + "]}, \"oracle\": {\"scenarios\": [" + scenario + "]}}";
    }

    private static void scope() {
        Mandate m = Mandate.parse("fix-search", EXAMPLE);
        check("mandate: a path under a ** folder is in scope, at any depth", m.inScope("src/main/java/com/acme/search/SearchService.java")
                && m.inScope("src/main/java/com/acme/search/deep/er/Thing.java"));
        check("mandate: a path that only shares a prefix is not in scope", !m.inScope("src/main/java/com/acme/searching/X.java")
                && !m.inScope("src/main/java/com/acme/search.java"));
        check("mandate: a path elsewhere is not in scope", !m.inScope("pom.xml") && !m.inScope("src/main/java/com/acme/billing/Bill.java"));
        Mandate star = Mandate.parse("x", minimal("\"docs/*.md\", \"src/**/Test*.java\", \"README.md\"", "\"y\""));
        check("mandate: * stays in one folder", star.inScope("docs/a.md") && !star.inScope("docs/sub/a.md") && !star.inScope("docs/a.md/b"));
        check("mandate: ** crosses folders, also none", star.inScope("src/a/b/TestX.java") && star.inScope("src/TestX.java") && !star.inScope("src/a/b/XTest.java"));
        check("mandate: a plain path is that file only", star.inScope("README.md") && !star.inScope("README.md/x") && !star.inScope("README.mdx"));
        Mandate everything = Mandate.parse("x", minimal("\"**\"", "\"y\""));
        check("mandate: ** alone is the whole repository", everything.inScope("a/b/c.txt") && everything.inScope("pom.xml"));
    }

    private static void commands(Path repo) throws IOException {
        write(repo, REGRESSION, "class SearchRegressionTest {}\n");
        write(repo, "src/test/java/com/acme/search/SearchServiceTest.java", "class SearchServiceTest {}\n");
        Proc.run(repo, "git", "init", "-q", "-b", "main");
        Ctx.init(repo);
        String regressionBlob = Proc.run(repo, "git", "hash-object", "--", REGRESSION).out().strip();
        write(repo, "docs/mandates/fix-search.json", EXAMPLE.replace("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", regressionBlob));

        String blob = Mandate.blobId(repo, "fix-search");
        check("mandate: the blob id is git hash-object of the file", blob.length() == 40
                && blob.equals(Proc.run(repo, "git", "hash-object", "--", "docs/mandates/fix-search.json").out().strip()));
        check("mandate: load reads docs/mandates/<id>.json", Mandate.load(repo, "fix-search").id().equals("fix-search")
                && Mandate.file(repo, "fix-search").equals(repo.resolve("docs/mandates/fix-search.json")));
        check("mandate: an id that leaves the folder is refused before any path is built",
                Asserts.throwsUsage(() -> Mandate.file(repo, "../x")) && Asserts.throwsUsage(() -> Mandate.file(repo, "a/b"))
                && Asserts.throwsUsage(() -> Mandate.load(repo, "..")) && Asserts.throwsUsage(() -> Mandate.file(repo, "")));
        check("mandate: a missing mandate says so", loadMessage(repo, "nope").contains("docs/mandates/nope.json does not exist"));

        String ok = cli("mandate", "check", "fix-search");
        check("mandate check: a valid mandate says so with its blob, and exits 0",
                code == 0 && ok.startsWith("docs/mandates/fix-search.json is a valid mandate (blob " + blob.substring(0, 12) + ")") && !ok.contains("warning"));

        write(repo, REGRESSION, "class SearchRegressionTest { /* edited */ }\n");
        String now = Proc.run(repo, "git", "hash-object", "--", REGRESSION).out().strip();
        String drift = cli("mandate", "check", "fix-search");
        check("mandate check: a locked test that changed is a warning that names both blobs, not a failure", code == 0
                && drift.contains("warning: " + REGRESSION + " has blob " + now.substring(0, 12) + " now, but oracle.locked says it must have " + regressionBlob.substring(0, 12)));
        Files.delete(repo.resolve(REGRESSION));
        String gone = cli("mandate", "check", "fix-search");
        check("mandate check: a missing test is a warning", code == 0 && gone.contains("mustStayTrue[0] names the test " + REGRESSION + ", which does not exist")
                && gone.contains("oracle.locked names " + REGRESSION + ", which does not exist"));

        String show = cli("mandate", "show", "fix-search");
        check("mandate show: the card has every part, in plain words", code == 0 && show.startsWith("Mandate fix-search\n  Searching with an empty query")
                && show.contains("Must stay true\n  - A query with text returns the same results as before\n    kept by the test " + REGRESSION)
                && show.contains("This fails if\n  an empty query reaches the database") && show.contains("May change\n  src/main/java/com/acme/search/**")
                && show.contains("Must pass\n  emptyQueryReturnsEmptyList in src/test/java/com/acme/search/SearchServiceTest.java")
                && show.contains("Locked tests") && show.strip().endsWith(blob));
        check("mandate show: no line of the card is wider than about 80 columns", show.lines().allMatch(l -> l.length() <= 90));
        check("mandate show: no colour codes", show.indexOf('\u001b') < 0);
        String unwritten = MandateCard.render(Mandate.parse("x", minimal("\"a/**\"", "\"T.java\"")), "abc");
        check("mandate show: a card without the 'this fails if' sentence says no one has written it", unwritten.contains("This fails if\n  No one has written this sentence yet.")
                && unwritten.contains("every test in T.java") && unwritten.contains("Locked tests (each must stay exactly as it is)\n  None."));
        check("mandate show: a long intent is wrapped", MandateCard.render(Mandate.parse("x", minimal("\"a\"", "\"b\"").replace("Do x", "word ".repeat(50).strip())), "b")
                .lines().allMatch(l -> l.length() <= 80));

        write(repo, "docs/mandates/broken.json", "{\"mandate\": \"v0\", \"id\": \"broken\", \"intent\": \"Half done\", \"scope\": {\"paths\": [\"../up\"]}}");
        String bad = cli("mandate", "check", "broken");
        check("mandate check: an invalid mandate lists its problems and exits 1", code == 1 && bad.startsWith("docs/mandates/broken.json is not a valid mandate:")
                && bad.contains("  scope.paths[0] \"../up\" leaves the repository") && bad.contains("  oracle is missing"));
        check("mandate check: a missing mandate exits 1", cli("mandate", "check", "nope").contains("does not exist") && code == 1);
        check("mandate check: an id that leaves the folder is refused", Asserts.throwsUsage(() -> cli("mandate", "check", "../x")));
        check("mandate show: an invalid mandate is refused with its problems",
                loadMessage(repo, "broken").contains("is not a valid mandate:\n  ") && Asserts.throwsUsage(() -> cli("mandate", "show", "broken")));

        write(repo, "docs/mandates/not an id.json", "{}");
        String list = cli("mandate", "list");
        check("mandate list: one line per file with the id, valid or not, and the intent", code == 0 && list.lines().count() == 3
                && list.lines().anyMatch(l -> l.matches("broken +not valid +Half done"))
                && list.lines().anyMatch(l -> l.matches("fix-search +valid +Searching with an empty query.*"))
                && list.lines().anyMatch(l -> l.startsWith("not an id") && l.contains("not valid")));

        PrintStream err = System.err;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        System.setErr(new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            cli("mandate", "frobnicate");
        } finally {
            System.setErr(err);
        }
        check("mandate: an unknown sub-command prints the usage and exits 3", code == 3 && sink.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").contains("mandate check <ID>"));
        check("mandate: check without an id is a usage error", Asserts.throwsUsage(() -> cli("mandate", "check")));
        check("mandate draft: the usage names it", sink.toString(StandardCharsets.UTF_8).contains("mandate draft <ID>"));
        draft(repo);
    }

    private static final String DRAFTED = """
            {
              "intent": "Searching with an empty query returns an empty list",
              "mustStayTrue": [ { "is": "Other queries return what they did", "test": "%s" } ],
              "scope": { "paths": ["src/**"] },
              "oracle": { "scenarios": ["src/test/java/com/acme/search/SearchServiceTest.java"] }
            }
            """;

    private static void draft(Path repo) throws IOException {
        String keep = "src/test/java/com/acme/search/KeepTest.java";
        write(repo, keep, "class KeepTest {}\n");
        String keepBlob = Proc.run(repo, "git", "hash-object", "--", keep).out().strip();

        String none = cli("mandate", "draft", "d0");
        check("mandate draft: a mandate that is not there says what to write and where, and exits 1", code == 1 && none.startsWith("docs/mandates/d0.json does not exist.")
                && none.contains("\"intent\"") && none.contains("\"scope\"") && none.contains("\"oracle\"") && none.contains("sdlc mandate draft d0") && !Files.exists(repo.resolve("docs/mandates/d0.json")));
        check("mandate draft: without an id it is a usage error", Asserts.throwsUsage(() -> cli("mandate", "draft")));

        write(repo, "docs/mandates/d1.json", DRAFTED.formatted(keep));
        String first = cli("mandate", "draft", "d1");
        String text = Files.readString(repo.resolve("docs/mandates/d1.json"));
        Mandate d1 = loadedOrNull(repo, "d1");
        check("mandate draft: it fills in the version and the id, and the result is a valid mandate", code == 0 && d1 != null && d1.version().equals("v0") && d1.id().equals("d1")
                && text.startsWith("{\n  \"mandate\": \"v0\",\n  \"id\": \"d1\",\n  \"intent\": ") && first.contains("added \"mandate\": \"v0\"") && first.contains("added \"id\": \"d1\""));
        check("mandate draft: it locks each test that must stay true at the blob git hash-object gives", d1 != null && d1.locked().size() == 1 && d1.locked().get(keep).equals(keepBlob)
                && keepBlob.length() == 40 && first.contains("locked " + keep + " at blob " + keepBlob.substring(0, 12)));
        check("mandate draft: it writes the layout approve writes", text.equals(Json.pretty(Json.parseStrict(text)) + "\n"));
        check("mandate draft: it prints the card, then says the draft is not approved and how a person approves it", first.startsWith("Mandate d1\n")
                && first.contains("This mandate is the file with blob " + Mandate.blobId(repo, "d1")) && first.contains("It is not approved")
                && first.contains("sdlc mandate approve d1") && first.indexOf("sdlc mandate approve d1") > first.indexOf("Locked tests"));
        check("mandate draft: it never approves", sdlc.mandate.SelfApproval.of(repo, "d1") == null);
        String checked = cli("mandate", "check", "d1");
        check("mandate draft: mandate check finds nothing to warn about in what it wrote", code == 0 && checked.contains("is a valid mandate") && !checked.contains("warning"));
        String second = cli("mandate", "draft", "d1");
        check("mandate draft: run again, it changes nothing and says so", code == 0 && second.contains("Nothing to change in docs/mandates/d1.json")
                && Files.readString(repo.resolve("docs/mandates/d1.json")).equals(text) && !second.contains("added"));

        write(repo, "docs/mandates/d2.json", DRAFTED.formatted(keep).replace("{\n  \"intent\"", "{\n  \"id\": \"other\",\n  \"intent\""));
        String before = Files.readString(repo.resolve("docs/mandates/d2.json"));
        String wrongId = cli("mandate", "draft", "d2");
        check("mandate draft: an id that is not the file name is refused, not put right", code == 1 && wrongId.contains("  id \"other\" does not match the file name d2.json")
                && wrongId.contains("Nothing was written") && Files.readString(repo.resolve("docs/mandates/d2.json")).equals(before));

        String missingTest = "src/test/java/com/acme/search/MissingTest.java";
        write(repo, "docs/mandates/d3.json", DRAFTED.formatted(missingTest));
        before = Files.readString(repo.resolve("docs/mandates/d3.json"));
        String missing = cli("mandate", "draft", "d3");
        check("mandate draft: a test that must stay true and does not exist is named, and nothing is written", code == 1
                && missing.contains("  mustStayTrue[0].test \"" + missingTest + "\" does not exist") && !missing.contains("is not in oracle.locked")
                && Files.readString(repo.resolve("docs/mandates/d3.json")).equals(before));

        write(repo, "docs/mandates/d4.json", DRAFTED.formatted(keep).replace("\"scope\":", "\"failsIf\": \"an empty query reaches the database\",\n  \"scope\":"));
        before = Files.readString(repo.resolve("docs/mandates/d4.json"));
        String fails = cli("mandate", "draft", "d4");
        check("mandate draft: a draft that holds failsIf is refused: the approver writes that sentence", code == 1 && fails.contains("failsIf")
                && fails.contains("sdlc mandate approve") && fails.contains("Take \"failsIf\" out") && Files.readString(repo.resolve("docs/mandates/d4.json")).equals(before));

        write(repo, "docs/mandates/d5.json", DRAFTED.formatted(keep).replace("\"scope\": { \"paths\": [\"src/**\"] },", ""));
        before = Files.readString(repo.resolve("docs/mandates/d5.json"));
        String invalid = cli("mandate", "draft", "d5");
        check("mandate draft: a draft that is not valid once filled in prints each problem as mandate check does, and is not written", code == 1
                && invalid.startsWith("docs/mandates/d5.json is not a valid mandate:\n  ") && invalid.contains("\n  scope is missing")
                && invalid.contains("Nothing was written") && Files.readString(repo.resolve("docs/mandates/d5.json")).equals(before));

        write(repo, "docs/mandates/d5.json", DRAFTED.formatted(keep).replace(",\n  \"oracle\": { \"scenarios\": [\"src/test/java/com/acme/search/SearchServiceTest.java\"] }", ""));
        String noOracle = cli("mandate", "draft", "d5");
        check("mandate draft: a draft with no oracle is told the one thing it lacks, the scenarios, not to lock its tests by hand", code == 1
                && noOracle.contains("\n  oracle.scenarios is missing") && !noOracle.contains("is not in oracle.locked") && !Files.readString(repo.resolve("docs/mandates/d5.json")).contains("oracle"));

        write(repo, "docs/mandates/d6.json", DRAFTED.formatted(keep).replace("\"scenarios\":", "\"locked\": { \"" + keep + "\": \"" + "0".repeat(40) + "\" }, \"scenarios\":"));
        String stale = cli("mandate", "draft", "d6");
        check("mandate draft: a locked blob that is not the file's is replaced, and the replacement is said", code == 0
                && loadedOrNull(repo, "d6") != null && loadedOrNull(repo, "d6").locked().get(keep).equals(keepBlob) && stale.contains("oracle.locked[\"" + keep + "\"] said 000000000000; the file has blob " + keepBlob.substring(0, 12)));
        write(repo, "docs/mandates/d6.json", DRAFTED.formatted(keep).replace("\"scenarios\":", "\"locked\": { \"" + REGRESSION + "\": \"" + "1".repeat(40) + "\" }, \"scenarios\":"));
        String gone = cli("mandate", "draft", "d6");
        check("mandate draft: a locked test that does not exist is named too", code == 1 && gone.contains("  oracle.locked key \"" + REGRESSION + "\" does not exist"));

        write(repo, "docs/mandates/d7.json", DRAFTED.formatted("../KeepTest.java"));
        String leaves = cli("mandate", "draft", "d7");
        check("mandate draft: a test path that leaves the repository is refused by the parser and never looked for", code == 1
                && leaves.contains("mustStayTrue[0].test \"../KeepTest.java\" leaves the repository") && !leaves.contains("does not exist"));

        Path linkedTest = repo.resolve("src/test/java/com/acme/search/LinkedTest.java");
        try {
            Files.createSymbolicLink(linkedTest, repo.resolve(keep));
            write(repo, "docs/mandates/d8.json", DRAFTED.formatted("src/test/java/com/acme/search/LinkedTest.java"));
            String link = cli("mandate", "draft", "d8");
            check("mandate draft: a test that is a link is not locked", code == 1 && link.contains("LinkedTest.java\" is a link"));
            Files.delete(linkedTest);
            Files.createSymbolicLink(repo.resolve("src/test/java/linked"), repo.resolve("src/test/java/com/acme/search"));
            write(repo, "docs/mandates/d8.json", DRAFTED.formatted("src/test/java/linked/KeepTest.java"));
            String through = cli("mandate", "draft", "d8");
            check("mandate draft: nor a test reached through a linked folder, which git does not keep", code == 1 && through.contains("src/test/java/linked/KeepTest.java\" is reached through a link"));
            Files.delete(repo.resolve("src/test/java/linked"));
            Files.createSymbolicLink(repo.resolve("docs/mandates/d9.json"), repo.resolve("docs/mandates/d1.json"));
            String linkedMandate = cli("mandate", "draft", "d9");
            check("mandate draft: a mandate that is a link is refused as for the other commands", code == 1 && linkedMandate.contains("docs/mandates/d9.json is a link")
                    && Files.readString(repo.resolve("docs/mandates/d1.json")).equals(text));
        } catch (IOException | UnsupportedOperationException e) {
        } finally {
            Files.deleteIfExists(linkedTest);
            Files.deleteIfExists(repo.resolve("src/test/java/linked"));
            Files.deleteIfExists(repo.resolve("docs/mandates/d9.json"));
        }

        write(repo, "docs/mandates/d10.json", DRAFTED.formatted(keep).replace("Searching with an empty query", "x".repeat(256 * 1024)));
        String huge = cli("mandate", "draft", "d10");
        check("mandate draft: a mandate larger than a page is refused for its size", code == 1 && huge.contains("is larger than 256 KB"));
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < 22_000; i++) paths.append(i == 0 ? "" : ",").append("\"p/").append(i).append('"');
        write(repo, "docs/mandates/d10.json", "{\"intent\":\"Many paths\",\"scope\":{\"paths\":[" + paths + "]},\"oracle\":{\"scenarios\":[\"T.java\"]}}");
        before = Files.readString(repo.resolve("docs/mandates/d10.json"));
        String grows = cli("mandate", "draft", "d10");
        check("mandate draft: a draft that would grow past a page once laid out is not written", code == 1 && grows.contains("would be larger than 256 KB")
                && Files.readString(repo.resolve("docs/mandates/d10.json")).equals(before));

        String ignored = "src/test/java/com/acme/search/IgnoredTest.java", kept = "src/test/java/com/acme/search/KeptTest.java";
        write(repo, ".gitignore", "src/test/java/com/acme/search/IgnoredTest.java\nsrc/test/java/com/acme/search/KeptTest.java\n");
        write(repo, ignored, "class IgnoredTest {}\n");
        write(repo, kept, "class KeptTest {}\n");
        Proc.run(repo, "git", "add", "-f", "--", kept);
        write(repo, "docs/mandates/d11.json", DRAFTED.formatted(ignored));
        before = Files.readString(repo.resolve("docs/mandates/d11.json"));
        String notTaken = cli("mandate", "draft", "d11");
        check("mandate draft: a test that git ignores and does not track is refused, as the check would not find it, and nothing is written", code == 1
                && notTaken.contains("  mustStayTrue[0].test \"" + ignored + "\" is ignored by git, so the check will not find it. Take it out of .gitignore or name another test")
                && !notTaken.contains("is not in oracle.locked") && Files.readString(repo.resolve("docs/mandates/d11.json")).equals(before));
        write(repo, "docs/mandates/d12.json", DRAFTED.formatted(kept));
        String tracked = cli("mandate", "draft", "d12");
        Mandate d12 = loadedOrNull(repo, "d12");
        check("mandate draft: a test that git tracks is locked, though a pattern of .gitignore matches it", code == 0 && tracked.contains("locked " + kept) && d12 != null
                && d12.locked().get(kept).equals(Proc.run(repo, "git", "hash-object", "--", kept).out().strip()));
        Files.delete(repo.resolve(".gitignore"));

        java.util.Deque<String> answers = new java.util.ArrayDeque<>(java.util.List.of("other queries change", "1", "d1"));
        int approved;
        try {
            approved = MandateCli.approve(repo, "d1", q -> answers.poll(), new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8));
        } catch (UsageException e) {
            approved = -1;
        }
        String asApproved = Files.readString(repo.resolve("docs/mandates/d1.json"));
        String again = cli("mandate", "draft", "d1");
        check("mandate draft: a mandate that is approved as it is is left byte for byte", approved == 0 && code == 0
                && again.contains("already approved; an edit needs a new approval") && Files.readString(repo.resolve("docs/mandates/d1.json")).equals(asApproved));
        write(repo, "docs/mandates/d1.json", asApproved.replace("an empty list", "an empty list, quickly"));
        String edited = cli("mandate", "draft", "d1");
        check("mandate draft: an approved mandate edited since still holds the approver's sentence, so it is refused and its approval is not touched", code == 1
                && edited.contains("Take \"failsIf\" out") && edited.contains("edited since it was approved")
                && approved == 0 && sdlc.mandate.SelfApproval.of(repo, "d1").blob().equals(Mandate.blobOf(repo, asApproved)));
    }

    private static Mandate loadedOrNull(Path repo, String id) {
        try {
            return Mandate.load(repo, id);
        } catch (UsageException e) {
            return null;
        }
    }

    private static String loadMessage(Path repo, String id) {
        try {
            Mandate.load(repo, id);
            return "(loaded)";
        } catch (UsageException e) {
            return e.getMessage();
        }
    }

    private static int code;

    private static String cli(String... args) {
        PrintStream out = System.out;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            code = MandateCli.run(args, Map.of());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            System.setOut(out);
        }
        return sink.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static void write(Path repo, String rel, String text) throws IOException {
        Path f = repo.resolve(rel);
        sdlc.util.Fs.makeParent(f);
        Files.writeString(f, text);
    }
}
