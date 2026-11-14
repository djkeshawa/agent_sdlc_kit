package sdlc.selftest;

import static sdlc.selftest.Asserts.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import sdlc.check.Change;
import sdlc.check.Finding;
import sdlc.check.Finding.Level;
import sdlc.check.Integrity;
import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Trusted;
import sdlc.util.Proc;

/** Self test: the integrity scan of sdlc check, one detector at a time, in a repository of its own. */
final class IntegrityTests {
    private IntegrityTests() {}

    private static final String CALC_TEST = """
            package demo;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class CalcTest {
                @Test
                void adds() {
                    assertEquals(2, 1 + 1);
                    assertEquals(3, 1 + 2);
                }

                @Test
                void subtracts() {
                    assertEquals(0, 1 - 1);
                }

                private int helper() {
                    return 1;
                }
            }
            """;

    private static final String SUM_TEST = """
            import { it, expect } from 'vitest';

            it('adds', () => {
              expect(1 + 1).toBe(2);
            });

            it('subtracts', () => {
              expect(1 - 1).toBe(0);
            });
            """;

    private static final String POM = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>32.0</version>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                    </configuration>
                  </plugin>
                  <plugin>
                    <artifactId>maven-deploy-plugin</artifactId>
                    <configuration>
                    </configuration>
                  </plugin>
                  <plugin>
                    <artifactId>jacoco-maven-plugin</artifactId>
                    <configuration>
                      <minimum>0.80</minimum>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private static final String PACKAGE = """
            {
              "name": "web",
              "scripts": { "test": "vitest run", "build": "tsc" },
              "dependencies": { "lodash": "4.17.20" },
              "devDependencies": { "vitest": "1.0.0" }
            }
            """;

    private static final String VITEST = """
            export default { test: { coverage: { thresholds: { lines: 80, branches: 70 } } } };
            """;

    static void run() throws IOException {
        Path savedRoot = Ctx.root;
        Config savedCfg = Ctx.cfg;
        Path repo = Files.createTempDirectory("sdlc-integritytest").toRealPath();
        try {
            Trusted.env = name -> null;
            setUp(repo);
            tests(repo);
            build(repo);
            notes(repo);
        } finally {
            Trusted.env = System::getenv;
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

    private static void setUp(Path repo) throws IOException {
        write(repo, "src/main/java/demo/Calc.java", "package demo;\nclass Calc {}\n");
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST);
        write(repo, "web/sum.test.ts", SUM_TEST);
        write(repo, "src/test/resources/data.json", "{}\n");
        write(repo, "pom.xml", POM);
        write(repo, "package.json", PACKAGE);
        write(repo, "package-lock.json", "{ \"lockfileVersion\": 3 }\n");
        write(repo, "vitest.config.ts", VITEST);
        write(repo, "README.md", "# demo\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=selftest", "-c", "user.email=selftest@example.com", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "base");
        Ctx.init(repo);
        check("integrity: nothing changed, nothing found", scan(repo).isEmpty());
    }

    private static void tests(Path repo) throws IOException {
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("helper()", "renamedHelper()"));
        check("integrity: renaming a helper method in a test file reports nothing", scan(repo).isEmpty());
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", """
                    @Test
                    void multiplies() {
                        assertEquals(4, 2 * 2);
                    }

                    private int helper"""));
        check("integrity: an added test with an assertion reports nothing", scan(repo).isEmpty());
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", """
                    @Test
                    void divides() {
                        new Calc();
                    }

                    private int helper"""));
        write(repo, "web/sum.test.ts", SUM_TEST + "\nit('multiplies', () => {\n  const x = 2 * 2;\n});\n");
        List<Finding> empty = scan(repo);
        check("integrity: a new Java test with no assertion is a note that says appears", only(empty, Level.NOTE, "appears to assert nothing", "src/test/java/demo/CalcTest.java", "divides (new)"));
        check("integrity: and a new script test", only(empty, Level.NOTE, "appears to assert nothing", "web/sum.test.ts", "multiplies (new)") && problems(empty) == 0);
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    @Test\n    void subtracts", "    @Disabled\n    @Test\n    void subtracts"));
        write(repo, "web/sum.test.ts", SUM_TEST.replace("it('adds'", "it.only('adds'"));
        List<Finding> off = scan(repo);
        check("integrity: a test switched off is a problem, reported once", count(off, "test switched off") == 1 && only(off, Level.PROBLEM, "test switched off", "src/test/java/demo/CalcTest.java", "@Disabled"));
        check("integrity: a focused test is a problem", only(off, Level.PROBLEM, "test focused", "web/sum.test.ts", "it.only('adds'"));
        reset(repo);

        write(repo, "src/main/resources/application.properties", "db.password=hunter2hunter2\n");
        check("integrity: a possible secret is a problem", only(scan(repo), Level.PROBLEM, "possible secret", "src/main/resources/application.properties", "(masked)"));
        reset(repo);

        Files.delete(repo.resolve("web/sum.test.ts"));
        Files.delete(repo.resolve("src/test/resources/data.json"));
        List<Finding> deleted = scan(repo);
        check("integrity: a deleted test file is a problem", only(deleted, Level.PROBLEM, "test file deleted", "web/sum.test.ts", "2 of its 2 test cases"));
        check("integrity: a deleted fixture beside the tests is only a note", only(deleted, Level.NOTE, "test support file deleted", "src/test/resources/data.json", "no test cases"));
        reset(repo);

        // a move is a deletion plus an addition: a note when the file, or every test case it had, is found again
        Files.move(repo.resolve("web/sum.test.ts"), repo.resolve("web/math.test.ts"));
        List<Finding> moved = scan(repo);
        check("integrity: a test file moved as it is is a note, not a problem", problems(moved) == 0 && only(moved, Level.NOTE, "test file moved", "web/sum.test.ts", "web/math.test.ts"));
        reset(repo);
        Files.delete(repo.resolve("src/test/java/demo/CalcTest.java"));
        write(repo, "src/test/java/demo/calc/CalcTest.java", CALC_TEST.replace("package demo;", "package demo.calc;"));
        List<Finding> movedEdited = scan(repo);
        check("integrity: a test file moved with its tests intact is a note", problems(movedEdited) == 0
                && only(movedEdited, Level.NOTE, "test file moved", "src/test/java/demo/CalcTest.java", "all 2 of its test cases are now in src/test/java/demo/calc/CalcTest.java"));
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("void subtracts()", "void minus()"));
        check("integrity: a renamed test case is a note: the file has as many tests as before", problems(scan(repo)) == 0
                && only(scan(repo), Level.NOTE, "test renamed or replaced", "src/test/java/demo/CalcTest.java", "1 test case removed or renamed: subtracts"));
        reset(repo);
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replaceFirst("(?s)    @Test\\s+void subtracts\\(\\) \\{.*?\\n    \\}\\n", ""));
        check("integrity: a test case that is gone is a problem", only(scan(repo), Level.PROBLEM, "test removed", "src/test/java/demo/CalcTest.java", "1 test case removed or renamed: subtracts"));
        reset(repo);
        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("    private int helper", "    // a note about @Disabled and it.skip( in a comment\n    String s = \"@Disabled\";\n    private int helper"));
        check("integrity: @Disabled in a comment or a string switches nothing off", problems(scan(repo)) == 0);
        reset(repo);
        write(repo, "pom.xml", POM.replace("</project>", "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration><skip>true</skip></configuration></plugin></plugins></build>\n<properties><skipTests>\n  true\n</skipTests></properties></project>"));
        List<Finding> compact = scan(repo);
        check("integrity: skip settings are found in a pom whatever its line breaks", count(compact, "tests skipped by the build") == 2
                && compact.stream().anyMatch(f -> f.detail().equals("Surefire is told to skip: <skip>true</skip>"))
                && compact.stream().anyMatch(f -> f.detail().equals("<skipTests>true</skipTests>")));
        reset(repo);
        write(repo, "package.json", "{\"scripts\": {\"test\": \"vitest run" + " ".repeat(20000) + "|| true\"}}\n");
        long started = System.nanoTime();
        scan(repo);
        check("integrity: a very long line does not keep the scan busy", (System.nanoTime() - started) / 1_000_000 < 3000);
        reset(repo);

        write(repo, "src/test/java/demo/CalcTest.java", CALC_TEST.replace("        assertEquals(3, 1 + 2);\n", ""));
        check("integrity: fewer assertions in an edited test file is a note", only(scan(repo), Level.NOTE, "fewer assertions", "src/test/java/demo/CalcTest.java", "3 assertions before, 2 after"));
        reset(repo);
    }

    private static void build(Path repo) throws IOException {
        write(repo, "pom.xml", POM.replaceFirst("<configuration>\n", "<configuration>\n          <skipTests>true</skipTests>\n          <testFailureIgnore>true</testFailureIgnore>\n"));
        List<Finding> pom = scan(repo);
        check("integrity: skipTests in the pom is a problem", only(pom, Level.PROBLEM, "tests skipped by the build", "pom.xml", "<skipTests>true</skipTests>"));
        check("integrity: testFailureIgnore in the pom is a problem", only(pom, Level.PROBLEM, "test failures ignored by the build", "pom.xml", "<testFailureIgnore>"));
        reset(repo);

        write(repo, "pom.xml", POM.replace("<configuration>\n", "<configuration>\n          <skip>true</skip>\n"));
        List<Finding> skip = scan(repo);
        check("integrity: <skip> in Surefire is a problem, in another plugin it is not", count(skip, "tests skipped by the build") == 1 && only(skip, Level.PROBLEM, "tests skipped by the build", "pom.xml", "Surefire"));
        reset(repo);

        write(repo, "package.json", PACKAGE.replace("\"vitest run\"", "\"vitest run --passWithNoTests\""));
        write(repo, "Jenkinsfile", "pipeline { stages { stage('b') { steps { sh './mvnw -B verify -DskipTests' } } } }\n");
        write(repo, ".github/workflows/ci.yml", "jobs:\n  t:\n    steps:\n      - run: npm test || true\n      - run: rm -rf coverage || true\n      - run: ./mvnw verify -DskipTests=false\n");
        write(repo, "README.md", "# demo\nBuild without tests: ./mvnw verify -DskipTests\n");
        List<Finding> flags = scan(repo);
        check("integrity: --passWithNoTests in a script is a problem", only(flags, Level.PROBLEM, "tests skipped by the build", "package.json", "finds no tests"));
        check("integrity: -DskipTests in a pipeline is a problem", only(flags, Level.PROBLEM, "tests skipped by the build", "Jenkinsfile", "-DskipTests"));
        check("integrity: || true after a test command is a problem, after another command it is not",
                count(flags, "test failures ignored by the build") == 1 && only(flags, Level.PROBLEM, "test failures ignored by the build", ".github/workflows/ci.yml", "npm test || true"));
        check("integrity: -DskipTests=false and the same flag in a README are not", flags.stream().noneMatch(f -> f.path().equals("README.md"))
                && count(flags, "tests skipped by the build") == 2);
        check("integrity: a pipeline change is a note", only(flags, Level.NOTE, "pipeline changed", "Jenkinsfile", "added") && only(flags, Level.NOTE, "pipeline changed", ".github/workflows/ci.yml", "added"));
        check("integrity: a script change in package.json names the script", only(flags, Level.NOTE, "build configuration changed", "package.json", "scripts changed: test"));
        boolean ordered = true;
        for (int i = 1; i < flags.size(); i++) ordered &= !(flags.get(i - 1).level() == Level.NOTE && flags.get(i).level() == Level.PROBLEM);
        check("integrity: problems come before notes", ordered);
        check("integrity: every detail is one line of at most 140 characters", flags.stream().allMatch(f -> f.detail().length() <= 140 && !f.detail().contains("\n")));
        reset(repo);
    }

    private static void notes(Path repo) throws IOException {
        write(repo, "pom.xml", POM.replace("<version>32.0</version>", "<version>33.0</version>"));
        List<Finding> bump = scan(repo);
        check("integrity: a dependency version bump is shown once", count(bump, "dependency changed") == 1 && count(bump, "dependency added") == 0 && count(bump, "dependency removed") == 0
                && only(bump, Level.NOTE, "dependency changed", "pom.xml", "guava: 32.0 to 33.0"));
        check("integrity: and the pom is build configuration", only(bump, Level.NOTE, "build configuration changed", "pom.xml", "edited") && problems(bump) == 0);
        reset(repo);

        write(repo, "package.json", PACKAGE.replace("\"lodash\": \"4.17.20\"", "\"lodash\": \"4.17.21\", \"axios\": \"1.6.0\"").replace("{ \"vitest\": \"1.0.0\" }", "{}"));
        List<Finding> npm = scan(repo);
        check("integrity: package.json dependencies are compared by name and version",
                only(npm, Level.NOTE, "dependency changed", "package.json", "lodash (dependencies): 4.17.20 to 4.17.21")
                && only(npm, Level.NOTE, "dependency added", "package.json", "axios (dependencies) 1.6.0")
                && only(npm, Level.NOTE, "dependency removed", "package.json", "vitest (devDependencies) 1.0.0"));
        check("integrity: a package.json whose scripts did not change is not build configuration", count(npm, "build configuration changed") == 0);
        reset(repo);

        write(repo, "package-lock.json", "{ \"lockfileVersion\": 3, \"packages\": {} }\n");
        check("integrity: a lock file changed alone is a note", only(scan(repo), Level.NOTE, "lock file changed without its manifest", "package-lock.json", "package.json"));
        reset(repo);

        write(repo, "src/main/resources/db/migration/V2__add_column.sql", "alter table t add column c int;\n");
        check("integrity: a migration is a note", only(scan(repo), Level.NOTE, "database migration changed", "src/main/resources/db/migration/V2__add_column.sql", "added"));
        reset(repo);

        write(repo, "pom.xml", POM.replace("<minimum>0.80</minimum>", "<minimum>0.60</minimum>"));
        write(repo, "vitest.config.ts", VITEST.replace("lines: 80, branches: 70", "lines: 60, branches: 75"));
        List<Finding> cover = scan(repo);
        check("integrity: a lowered JaCoCo minimum is a note", only(cover, Level.NOTE, "coverage threshold lowered", "pom.xml", "0.8 to 0.6"));
        check("integrity: a lowered vitest threshold is a note, a raised one is not", count(cover, "coverage threshold lowered") == 2
                && only(cover, Level.NOTE, "coverage threshold lowered", "vitest.config.ts", "lines coverage 80 to 60"));
        reset(repo);

        write(repo, "CLAUDE.md", "# rules\n");
        write(repo, ".github/CODEOWNERS", "* @someone\n");
        List<Finding> steer = scan(repo);
        check("integrity: files that steer agents and reviews are notes", only(steer, Level.NOTE, "agent or review instructions changed", "CLAUDE.md", "added")
                && only(steer, Level.NOTE, "agent or review instructions changed", ".github/CODEOWNERS", "added") && problems(steer) == 0);
        reset(repo);
    }

    private static List<Finding> scan(Path repo) {
        return Integrity.scan(Change.of(null));
    }

    /** Exactly one finding of this kind on this path, at this level, whose detail contains the text. */
    private static boolean only(List<Finding> all, Level level, String kind, String path, String detail) {
        List<Finding> hits = all.stream().filter(f -> f.kind().equals(kind) && f.path().equals(path)).toList();
        boolean ok = hits.size() == 1 && hits.get(0).level() == level && hits.get(0).detail().contains(detail);
        if (!ok) System.out.println("    integrity findings: " + all);
        return ok;
    }

    private static long count(List<Finding> all, String kind) {
        return all.stream().filter(f -> f.kind().equals(kind)).count();
    }

    private static long problems(List<Finding> all) {
        return all.stream().filter(f -> f.level() == Level.PROBLEM).count();
    }

    private static void reset(Path repo) {
        git(repo, "reset", "-q", "--hard");
        git(repo, "clean", "-fdq");
    }

    private static void write(Path repo, String rel, String text) throws IOException {
        Path f = repo.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private static void git(Path repo, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Proc p = Proc.run(repo, cmd);
        if (!p.ok()) throw new IllegalStateException("git " + String.join(" ", args) + ": " + p.err());
    }
}
