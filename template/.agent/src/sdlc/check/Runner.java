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
