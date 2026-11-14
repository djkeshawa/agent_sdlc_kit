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
 *   pass | fail | skip
 *   needs WORD          passes when prod.txt of the checkout holds the word, else fails by assertion
 *   flaky WORD          passes when it does; else fails in the first run in that checkout and passes in later ones
 *   error-unless WORD   passes when it does, else throws something that is not an assertion
 *   compile-needs WORD  passes when it does; else no file of the same run compiles
 *   leans-on FILE       passes when the checkout holds that file or an earlier run built it, else fails by assertion
 *   timeout             the whole file gives no result, as a run cut off by the time limit
 *   skip-without WORD   skipped where prod.txt lacks the word; where it holds it, the case does not exist
 *   skip-once WORD      where it holds the word, skipped in the first run in that checkout and passes in later ones; else passes
 *   pass-once           passes in the first run in that checkout, skipped in later ones
 *   fails-with WORD     fails by assertion where prod.txt holds the word, else passes: a test the change breaks
 *   breaks-once WORD    where it holds the word, fails in the first run in that checkout and passes in later ones; else passes
 * A name is reported as a script's string would give it: \' is a quote. A name with %i in it is
 * reported twice, with 1 and with 2 in its place (it.each, a parameterized method). A line
 * without ": " is not a case: it stands for the test's body. A name such as "check(int)[3]" is a
 * run of a parameterized test, as Surefire names it, except in a file named *.vitest.t. A name
 * "demo.Outer$A::works" is the case "works" of the class demo.Outer$A, as Surefire reports a
 * method of a nested class; any other case's suite is its file.
 * A changed shared.lock in a test's folder or above it is a setup both sides share. Like a build,
 * a run leaves something behind for each file it ran (.built/), until forgetBuilt removes it.
 */
final class SelfTestRunner implements Runner {
    /** Runs so far in each checkout: a flaky test's result depends on it, not on the order in which the checkouts are run. */
    private final Map<Path, Integer> calls = new java.util.HashMap<>();

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

    /** A file named *.vitest.t names its tests as Vitest does (a title is a test, brackets and all); any other as Surefire does. */
    @Override
    public Invocation invocation(JUnitXml.Case c) {
        return c.suite().endsWith(".vitest.t") ? null : Runners.surefireInvocation(c.name());
    }

    @Override
    public String sharedBySides(Change change, Path checkout, String file) {
        for (Change.File f : change.files) {
            String path = f.path();
            if (!path.equals("shared.lock") && !path.endsWith("/shared.lock")) continue;
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
            if (file.startsWith(dir)) return "the change touches shared.lock, which both sides ran with";
        }
        return null;
    }

    @Override
    public void forgetBuilt(Path checkout, List<String> files) {
        Runners.deleteTree(checkout.resolve(".built"));
    }

    @Override
    public Map<String, FileRun> run(Path checkout, List<String> files, int timeoutSeconds) {
        int call = calls.merge(checkout, 1, Integer::sum);
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
                Path built = checkout.resolve(".built").resolve(f); // what a build leaves behind: the compiled form of the file
                Files.createDirectories(built.getParent());
                Files.writeString(built, "");
                if (Files.readAllLines(checkout.resolve(f)).stream().anyMatch(line -> line.endsWith(": timeout"))) {
                    out.put(f, FileRun.failed("timed out after " + timeoutSeconds + " s"));
                    continue;
                }
                for (String line : Files.readAllLines(checkout.resolve(f))) {
                    if (!line.contains(": ")) continue;
                    String name = line.substring(0, line.indexOf(": "));
                    String[] how = line.substring(line.indexOf(": ") + 2).trim().split(" ");
                    boolean has = how.length > 1 && prod.contains(how[1]);
                    if (how[0].equals("skip-without") && has) continue; // a test declared only where the word is missing
                    Status s = switch (how[0]) {
                        case "pass" -> Status.PASSED;
                        case "fail" -> Status.FAILED;
                        case "skip", "skip-without" -> Status.SKIPPED;
                        case "skip-once" -> has && call == 1 ? Status.SKIPPED : Status.PASSED; // with the word: skipped only the first time
                        case "pass-once" -> call == 1 ? Status.PASSED : Status.SKIPPED;
                        case "fails-with" -> has ? Status.FAILED : Status.PASSED;
                        case "breaks-once" -> has && call == 1 ? Status.FAILED : Status.PASSED;
                        case "flaky" -> has || call > 1 ? Status.PASSED : Status.FAILED; // without the word: fails only the first time
                        case "needs" -> has ? Status.PASSED : Status.FAILED;
                        case "error-unless" -> has ? Status.PASSED : Status.ERRORED;
                        case "compile-needs" -> Status.PASSED; // it compiled, so the word is there
                        case "leans-on" -> how.length > 1 && (Files.isRegularFile(checkout.resolve(how[1])) || Files.isRegularFile(checkout.resolve(".built").resolve(how[1])))
                                ? Status.PASSED : Status.FAILED;
                        default -> Status.ERRORED;
                    };
                    String shown = name.replace("\\'", "'");
                    String suite = f;
                    if (shown.contains("::")) {
                        suite = shown.substring(0, shown.indexOf("::"));
                        shown = shown.substring(shown.indexOf("::") + 2);
                    }
                    for (String one : shown.contains("%i") ? List.of(shown.replace("%i", "1"), shown.replace("%i", "2")) : List.of(shown)) {
                        cases.add(new JUnitXml.Case(suite, one, s, s == Status.ERRORED ? "java.lang.IllegalStateException" : s == Status.FAILED ? "AssertionError" : "", s == Status.FAILED ? "expected something else" : ""));
                    }
                }
                out.put(f, new FileRun(cases, null));
            } catch (IOException e) {
                out.put(f, FileRun.failed("cannot be read"));
            }
        }
        return out;
    }
}
