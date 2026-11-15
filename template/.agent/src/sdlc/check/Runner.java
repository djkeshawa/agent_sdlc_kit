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
     *
     * retriesUnknown: why the run does not say which of its passed cases passed at the first try
     * and which only when the runner ran them again, or null when it says so of each (Case.retried).
     * A run that does not say it still gives its cases: a failure is a failure whatever the retries,
     * but no pass of it can be taken for a pass at the first try (see Witness.blind).
     */
    record FileRun(List<JUnitXml.Case> cases, String problem, boolean broken, String retriesUnknown) {
        FileRun(List<JUnitXml.Case> cases, String problem) {
            this(cases, problem, false, null);
        }

        FileRun(List<JUnitXml.Case> cases, String problem, boolean broken) {
            this(cases, problem, broken, null);
        }

        static FileRun failed(String problem) {
            return new FileRun(List.of(), problem, false, null);
        }

        static FileRun broken(String problem) {
            return new FileRun(List.of(), problem, true, null);
        }
    }
}
