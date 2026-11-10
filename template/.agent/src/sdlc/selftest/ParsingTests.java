package sdlc.selftest;

import static sdlc.selftest.Asserts.check;

import java.util.List;

import sdlc.cli.Setup;
import sdlc.core.Config;
import sdlc.util.Glob;
import sdlc.util.Json;
import sdlc.util.Yaml;

/** Self test: the parsers and the settings: YAML, JSON, globs, config presets. */
final class ParsingTests {
    private ParsingTests() {}

    static void run() {
        Object y = Yaml.parse("""
                a: 1   # comment
                b:
                  - x
                  - "y # not a comment"
                c: [p, 'q r', {k: v}]
                d:
                  - id: T1
                    scope: [src/a/**]
                    acceptance:
                      - Given x, when y, then z
                  - id: T2
                e: |
                  line one
                  line two
                url: https://x.example.com/wiki
                """);
        check("yaml scalars", "1".equals(Json.at(y, "a")) && "https://x.example.com/wiki".equals(Json.at(y, "url")));
        check("yaml list + quoted #", Json.list(Json.at(y, "b")).equals(List.of("x", "y # not a comment")));
        check("yaml flow", Json.list(Json.at(y, "c")).size() == 3 && "v".equals(Json.at(Json.list(Json.at(y, "c")).get(2), "k")));
        check("yaml list of maps", Json.list(Json.at(y, "d")).size() == 2 && "Given x, when y, then z".equals(Json.list(Json.at(Json.list(Json.at(y, "d")).get(0), "acceptance")).get(0)));
        check("yaml block scalar", "line one\nline two\n".equals(Json.at(y, "e")));
        Config c = Config.parse("stack: node\npaths:\n  protected+: [docs/legal/**]\nloops:\n  review: {min: 1, max: 3}\n");
        check("config preset node", c.verifyCommand().contains("npm test"));
        check("config append list", c.list("paths.protected").contains("docs/legal/**") && c.list("paths.protected").contains(".agent/**"));
        check("config flow override", c.num("loops.review.max", 0) == 3 && c.num("loops.ci.max", 0) == 3);
        check("glob braces", Glob.any(List.of("**/*.{test,spec}.{ts,tsx}"), "src/a/b.spec.tsx") && !Glob.any(List.of("**/*.{test,spec}.ts"), "src/a/b.ts"));
        check("glob folder", Glob.any(List.of(".agent/**"), ".agent") && Glob.any(List.of("**/pom.xml"), "pom.xml"));
        check("json roundtrip", Json.write(Json.parse("{\"a\":[1,\"x\\n\",true,null]}")).equals("{\"a\":[1,\"x\\n\",true,null]}"));
        check("setModel adds", Setup.setModel("---\nname: x\ndescription: d\ntools: []\n---\nbody", "GPT-5 (copilot)").contains("description: d\nmodel: 'GPT-5 (copilot)'\ntools"));
        check("setModel removes", !Setup.setModel("---\nname: x\nmodel: 'a'\n---\nbody", "").contains("model:"));
    }
}
