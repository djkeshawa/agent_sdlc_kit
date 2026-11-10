package sdlc.selftest;

import static sdlc.selftest.Asserts.check;
import static sdlc.selftest.Asserts.expect;
import static sdlc.selftest.Asserts.json;
import static sdlc.selftest.Asserts.shell;
import static sdlc.selftest.Asserts.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Defaults;
import sdlc.guard.Decision;
import sdlc.workflow.Checks;
import sdlc.workflow.Tasks;

/** Self test: the guard rules and settings that differ in a Node repository. */
final class NodeGuardTests {
    private NodeGuardTests() {}

    static void run(Path tmp) throws IOException {
        Config saved = Ctx.cfg;
        Ctx.cfg = Config.parse("stack: node\njira:\n  projectKeys: [PROJ]\n");
        try {
            expect("npm test ok", shell("npm test -- src/refund.test.ts"), Decision.Kind.ALLOW);
            expect("passWithNoTests", shell("npx jest --passWithNoTests"), Decision.Kind.DENY);
            expect("snapshot update asks", shell("npm test -- -u"), Decision.Kind.ASK);
            expect("npm install asks", shell("npm install left-pad"), Decision.Kind.ASK);
            expect("npm publish", shell("npm publish"), Decision.Kind.DENY);
            expect("ts code without task", tool("create_file", "{\"filePath\":\"" + json(tmp) + "/src/refund.ts\"}"), Decision.Kind.DENY);
            expect("package.json asks", tool("create_file", "{\"filePath\":\"" + json(tmp) + "/docs/x.md\"}"), Decision.Kind.ALLOW);
            check("it.only found", !Checks.disabledTests(Map.of("src/a.test.ts", List.of("  it.only('works', () => {})"))).isEmpty());
            check("xit found", !Checks.disabledTests(Map.of("src/__tests__/a.ts", List.of("xit('works', () => {})"))).isEmpty());
            check("normal it ok", Checks.disabledTests(Map.of("src/a.spec.tsx", List.of("it('refuses unpaid orders', () => {})"))).isEmpty());
            check("js main without tests", Checks.testsMissing(List.of("src/refund.ts")));
            check("js main with tests", !Checks.testsMissing(List.of("src/refund.ts", "src/refund.test.ts")));
            check("js token literal", !Checks.secrets(Map.of("src/config.ts", List.of("const apiKey = 'q8Zk2LmP0wXr9';"))).isEmpty()); // guard:allow-secret
            expect("routes file without task", tool("create_file", "{\"filePath\":\"" + json(tmp) + "/routes/users.js\"}"), Decision.Kind.DENY);
            expect("root entry file without task", tool("create_file", "{\"filePath\":\"index.js\"}"), Decision.Kind.DENY);
            expect("shell write to routes without task", shell("echo x > routes/users.js"), Decision.Kind.DENY);
            check("routes is main code, tool config is not", Checks.isMainCode("routes/users.js") && Checks.isMainCode("server.ts") && !Checks.isMainCode("eslint.config.js"));
            check("node scope depth", !Tasks.tooBroad("src/refund/**") && Tasks.tooBroad("lib/**") && Tasks.tooBroad("**/*.ts"));
            check("npm is the default verify", Ctx.cfg.str("build.verify").equals(Defaults.NODE_VERIFY) && Ctx.cfg.verifyCommand().equals(Defaults.NODE_VERIFY));
            Files.writeString(tmp.resolve("pnpm-lock.yaml"), "");
            check("pnpm lock file picks pnpm", Ctx.cfg.verifyCommand().equals("pnpm run --if-present lint && pnpm test && pnpm run --if-present build"));
            Files.delete(tmp.resolve("pnpm-lock.yaml"));
            Files.writeString(tmp.resolve("yarn.lock"), "");
            Files.writeString(tmp.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest run\",\"build\":\"tsc\"}}");
            check("yarn lock file picks yarn", Ctx.cfg.verifyCommand().equals("yarn test && yarn run build"));
            Files.delete(tmp.resolve("yarn.lock"));
            Files.delete(tmp.resolve("package.json"));
            Files.createDirectories(tmp.resolve("node_modules/.bin"));
            Files.writeString(tmp.resolve("node_modules/.bin/vitest"), "");
            expect("npx installed tool ok", shell("npx vitest run"), Decision.Kind.ALLOW);
            expect("npx --yes download asks", shell("npx --yes vitest run"), Decision.Kind.ASK);
            check("js env reference ok", Checks.secrets(Map.of("src/config.ts", List.of("const apiKey = process.env.API_KEY;"))).isEmpty());
        } finally {
            Ctx.cfg = saved;
        }
    }
}
