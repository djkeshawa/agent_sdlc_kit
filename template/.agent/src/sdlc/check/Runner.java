package sdlc.check;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

interface Runner {
    String name();

    boolean runs(Path checkout, String path);

    boolean support(String path);

    Map<String, FileRun> run(Path checkout, List<String> files, int timeoutSeconds);

    default String sharedBySides(Change change, Path checkout, String file) {
        return null;
    }

    default void forgetBuilt(Path checkout, List<String> files) {
    }

    default boolean runsAlone() {
        return false;
    }

    // How one reported case of a file is run alone: a filter for the runner, or why it cannot be.
    default Alone alone(String file, JUnitXml.Case c) {
        throw new UnsupportedOperationException("this runner runs no test alone");
    }

    default FileRun runAlone(Path checkout, String file, String filter, int timeoutSeconds) {
        throw new UnsupportedOperationException("this runner runs no test alone");
    }

    record Alone(String filter, String why) {}

    // timedOut: the seconds of the bound the run reached, 0 when it ended. alone: in a run before the change made of single runs, the seconds of the
    // bound the run of the file reached; 0 otherwise.
    record FileRun(List<JUnitXml.Case> cases, String problem, boolean broken, String retriesUnknown, int timedOut, int alone) {
        FileRun(List<JUnitXml.Case> cases, String problem) {
            this(cases, problem, false, null, 0, 0);
        }

        FileRun(List<JUnitXml.Case> cases, String problem, boolean broken) {
            this(cases, problem, broken, null, 0, 0);
        }

        FileRun(List<JUnitXml.Case> cases, String problem, boolean broken, String retriesUnknown) {
            this(cases, problem, broken, retriesUnknown, 0, 0);
        }

        static FileRun failed(String problem) {
            return new FileRun(List.of(), problem, false, null);
        }

        static FileRun broken(String problem) {
            return new FileRun(List.of(), problem, true, null);
        }

        static FileRun timedOut(int seconds) {
            return new FileRun(List.of(), "timed out after " + seconds + " s", false, null, seconds, 0);
        }
    }
}
