package sdlc.check;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Runs named test files of one kind in a checkout and reads what happened to each test case. */
interface Runner {
    /** For the report: "Maven Surefire", "Vitest". */
    String name();

    /** True when the path is a test file this runner can run in that checkout. */
    boolean runs(Path checkout, String path);

    /** True when the path belongs to the tests (a test, a fixture, a helper): laid over the base with them. */
    boolean support(String path);

    /** Runs the files and returns one result per file. */
    Map<String, FileRun> run(Path checkout, List<String> files, int timeoutSeconds);

    /**
     * What the two sides of a comparison of this test file share although the change touches it,
     * as the end of a sentence, or null. A test that passes on both sides then proves nothing
     * either way: the side "before the change" did not run without all of the change. Only what
     * decides this file's run counts: a file of the same kind elsewhere in the repository does not.
     */
    default String sharedBySides(Change change, Path checkout, String file) {
        return null;
    }

    /**
     * When this runner's report names the case as one run of a parameterized test by its place
     * among that test's runs, the test and the place; null when the case's name is a test of its
     * own. Only the runner knows how its tool names cases: Surefire adds a place in brackets to
     * each run of a parameterized method, Vitest keeps a test's title as it is written, brackets
     * and all. Null is the safe answer: a name taken whole is never merged with another test.
     */
    default Invocation invocation(JUnitXml.Case c) {
        return null;
    }

    /** One run of a parameterized test: the test as the report names it ("check(int)"), and the place the report gives this run (3 of "check(int)[3]"). */
    record Invocation(String test, int place) {}

    /**
     * Removes what earlier runs in this checkout built from test sources, so that the next run
     * sees only the sources that are there now. Without it, a test file that was taken away may
     * still be on the class path as a compiled class.
     */
    default void forgetBuilt(Path checkout, List<String> files) {
    }

    /**
     * What happened to one test file: its cases, or why none could be read. broken says the reason
     * is the code itself (it does not compile, it does not load); otherwise the reason is the
     * surroundings (no build tool, a timeout, a name that cannot be passed on), and nothing is
     * known about the code.
     */
    record FileRun(List<JUnitXml.Case> cases, String problem, boolean broken) {
        FileRun(List<JUnitXml.Case> cases, String problem) {
            this(cases, problem, false);
        }

        static FileRun failed(String problem) {
            return new FileRun(List.of(), problem, false);
        }

        static FileRun broken(String problem) {
            return new FileRun(List.of(), problem, true);
        }
    }
}
