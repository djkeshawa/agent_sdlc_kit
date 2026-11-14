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

    /** What happened to one test file: its cases, or why none could be read. */
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
