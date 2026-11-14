package sdlc.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sdlc.check.JUnitXml.Status;

/**
 * A stand-in test runner for the self test, so the comparison logic is tested without a build
 * tool. A ".t" file lists one case per line, "name: outcome":
 *   pass | fail | skip | flaky (passes after the change, fails before it only in the first run)
 *   needs WORD          passes when prod.txt of the checkout holds the word, else fails by assertion
 *   error-unless WORD   passes when it does, else throws something that is not an assertion
 *   compile-needs WORD  passes when it does; else no file of the same run compiles
 */
final class SelfTestRunner implements Runner {
    private int calls;

    @Override
    public String name() {
        return "self test";
    }

    @Override
    public boolean runs(Path checkout, String path) {
        return path.endsWith(".t");
    }

    @Override
    public boolean support(String path) {
        return path.endsWith(".t");
    }

    @Override
    public Map<String, FileRun> run(Path checkout, List<String> files, int timeoutSeconds) {
        calls++;
        Map<String, FileRun> out = new LinkedHashMap<>();
        String prod;
        try {
            prod = Files.isRegularFile(checkout.resolve("prod.txt")) ? Files.readString(checkout.resolve("prod.txt")) : "";
        } catch (IOException e) {
            prod = "";
        }
        // "compile-needs WORD" in any file of the run: without the word nothing of the run compiles, as in one build module
        for (String f : files) {
            try {
                for (String line : Files.readAllLines(checkout.resolve(f))) {
                    String[] how = line.contains(": ") ? line.substring(line.indexOf(": ") + 2).trim().split(" ") : new String[0];
                    if (how.length > 1 && how[0].equals("compile-needs") && !prod.contains(how[1])) {
                        for (String all : files) out.put(all, FileRun.broken(Witness.DOES_NOT_COMPILE));
                        return out;
                    }
                }
            } catch (IOException e) {
                // reported per file below
            }
        }
        for (String f : files) {
            List<JUnitXml.Case> cases = new ArrayList<>();
            try {
                for (String line : Files.readAllLines(checkout.resolve(f))) {
                    if (!line.contains(": ")) continue;
                    String name = line.substring(0, line.indexOf(": "));
                    String[] how = line.substring(line.indexOf(": ") + 2).trim().split(" ");
                    boolean has = how.length > 1 && prod.contains(how[1]);
                    Status s = switch (how[0]) {
                        case "pass" -> Status.PASSED;
                        case "fail" -> Status.FAILED;
                        case "skip" -> Status.SKIPPED;
                        case "flaky" -> calls % 2 == 1 || calls % 4 == 0 ? Status.PASSED : Status.FAILED; // after, before, after, before: fails before only the first time
                        case "needs" -> has ? Status.PASSED : Status.FAILED;
                        case "error-unless" -> has ? Status.PASSED : Status.ERRORED;
                        case "compile-needs" -> Status.PASSED; // it compiled, so the word is there
                        default -> Status.ERRORED;
                    };
                    cases.add(new JUnitXml.Case(f, name, s, s == Status.ERRORED ? "java.lang.IllegalStateException" : s == Status.FAILED ? "AssertionError" : "", s == Status.FAILED ? "expected something else" : ""));
                }
                out.put(f, new FileRun(cases, null));
            } catch (IOException e) {
                out.put(f, FileRun.failed("cannot be read"));
            }
        }
        return out;
    }
}
