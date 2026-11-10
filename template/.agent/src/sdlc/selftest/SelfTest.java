package sdlc.selftest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.util.Proc;

/**
 * Built-in tests: java .agent/Sdlc.java selftest. They run in a temporary git repository.
 * This class sets that repository up and runs the test classes of this package, one per area.
 */
public final class SelfTest {
    private SelfTest() {}

    public static int run() throws IOException {
        Asserts.passed = 0;
        Asserts.failed = 0;
        Path savedRoot = Ctx.root;
        Config savedCfg = Ctx.cfg;
        Path tmp = Files.createTempDirectory("sdlc-selftest").toRealPath();
        try {
            Files.createDirectories(tmp.resolve(".agent"));
            Files.writeString(tmp.resolve(".agent/sdlc.yml"), "stack: java-maven\njira:\n  projectKeys: [PROJ]\nci:\n  jenkins:\n    url: https://ci.x.com\n");
            Files.writeString(tmp.resolve(".agent/local.env"), "JIRA_TOKEN=selftest-not-a-token\n");
            Files.writeString(tmp.resolve("AGENTS.md"), "# rules\n");
            Files.createDirectories(tmp.resolve("src/main/java/demo"));
            Files.writeString(tmp.resolve("src/main/java/demo/A.java"), "class A {}\n");
            Files.createDirectories(tmp.resolve(".sdlc/PROJ-1/drafts"));
            Files.write(tmp.resolve(".sdlc/PROJ-1/drafts/mindmap.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            Files.createDirectories(tmp.resolve("scripts"));
            Files.writeString(tmp.resolve("scripts/build.mjs"), "console.log('build')\n");
            Proc.run(tmp, "git", "init", "-q", "-b", "develop");
            Proc.run(tmp, "git", "add", "scripts/build.mjs", "src/main/java/demo/A.java");
            Proc.run(tmp, "git", "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                    "commit", "-q", "-m", "chore(PROJ-1): selftest fixture");
            Ctx.init(tmp);
            ParsingTests.run();
            JavaGuardTests.run(tmp);
            NodeGuardTests.run(tmp);
            TextTests.run();
            WorkflowTests.run(tmp);
        } finally {
            Ctx.root = savedRoot;
            Ctx.cfg = savedCfg;
            delete(tmp);
        }
        int failed = Asserts.failed;
        System.out.println((failed == 0 ? "SELFTEST PASSED" : "SELFTEST FAILED") + "  (" + Asserts.passed + " passed, " + failed + " failed)");
        return failed == 0 ? 0 : 1;
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }
}
