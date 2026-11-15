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
 *   skip-unless WORD    passes where prod.txt holds the word, skipped where it lacks it
 *   skip-once WORD      where it holds the word, skipped in the first run in that checkout and passes in later ones; else passes
 *   skip-odd WORD       where it holds the word, skipped in the first, third, ... run in that checkout and passes in the others; else passes
 *   pass-once           passes in the first run in that checkout, skipped in later ones
 *   fails-with WORD     fails by assertion where prod.txt holds the word, else passes: a test the change breaks
 *   breaks-once WORD    where it holds the word, fails in the first run in that checkout and passes in later ones; else passes
 *   skip-later          not reported in the first run in that checkout; skipped in later ones
 *   pass-with WORD      passes where prod.txt holds the word; where it lacks it, the case does not exist (a row an enum of the code gives)
 *   pass-later          not reported in the first run in that checkout; passes in later ones (a source that gives another number of rows each time)
 *   pass-without WORD   passes where prod.txt lacks the word; where it holds it, the case does not exist (a row the code no longer gives once it has the word)
 *   gone-later WORD     where it holds the word, passes in the first run in that checkout and is not reported in later ones; else passes
 *   fails-later WORD    where it holds the word, passes in the first run in that checkout and fails by assertion in later ones; else passes
 *   tears-down-with WORD  where prod.txt holds the word, a failed case of its own (a tear-down that fails after the tests ran, as vitest
 *                       reports a failing afterAll); where it lacks it, the case does not exist
 *   pass-retried        failed, and passed when the build ran it again (Surefire's flakyFailure)
 *   fail-retried        failed by assertion every time the build ran it (failure, then rerunFailure)
 *   error-retried       threw every time the build ran it (error, then rerunError), as a set-up that fails does
 *   compile-later WORD  as needs WORD; where prod.txt holds the word, no file of the run compiles from the second run in that checkout on
 *   timeout-later WORD  as needs WORD; where prod.txt holds the word, the whole file gives no result from the second run in that checkout on
 *   error-varies WORD   passes where prod.txt holds the word; else throws, IllegalStateException in the first, third, ... run in that checkout and UnsupportedOperationException in the others
 *   retried-with WORD   passes; where prod.txt holds the word, only when the runner ran it again (vitest's retry: a test that fails at the first try on that code)
 *   retried-without WORD  passes; where prod.txt lacks the word, only when the runner ran it again
 *   cut-later WORD      passes; where prod.txt holds the word, the whole file gives no result from the second run in that checkout on
 *   cut-once WORD       passes; where prod.txt holds the word, the whole file gives no result in the first run in that checkout
 *   agrees FILE WORD    passes where prod.txt and the checkout's FILE both hold the word or both lack it, else fails by assertion: a test that
 *                       compares the code with what a helper in another test file expects
 * A line "retries not said" (no ": ") makes the file's runs not say which passes were at the first try,
 * as a version of vitest that does not count retries.
 * A name is reported as a script's string would give it: \' is a quote. A name with %i in it is
 * reported twice, with 1 and with 2 in its place (it.each, a parameterized method). A line
 * without ": " is not a case: it stands for the test's body. A name is reported as it is written,
 * brackets and all: what a name means is never read from its shape. A name
 * "demo.Outer$A::works" is the case "works" of the class demo.Outer$A, as Surefire reports a
 * method of a nested class; any other case's suite is its file. A ".java" file under a folder
 * jvm/ is run too, as a JUnit class: its cases' suite is its package (from its line "package
 * NAME;") and its file's name, as a JUnit report names a class, and each line "import NAME;" of
 * it makes NAME a word its cases see as though prod.txt held it (an import that decides what the
 * tests run against).
 * A changed shared.lock in a test's folder or above it is a setup both sides share. Like a build,
 * a run leaves something behind for each file it ran (.built/), until forgetBuilt removes it.
 */
final class SelfTestRunner implements Runner {
    /** Runs so far in each checkout: a flaky test's result depends on it, not on the order in which the checkouts are run. */
    private final Map<Path, Integer> calls = new java.util.HashMap<>();
    /**
     * For the self test (see Check.order): 0 reports each file's cases in the order it lists them;
     * 1 reverses them in the second, fourth, ... run in a checkout; 2 in the first, third, ... run.
     * So the order of a report differs from run to run, as where a build runs tests concurrently.
     */
    static int order;
    /** The folder whose files this runner owns ("folder/"), or "" for every ".t" file. */
    private final String under;

    SelfTestRunner() {
        this("");
    }

    /** A runner that owns only the ".t" files under a folder: two runners in one check. */
    SelfTestRunner(String under) {
        this.under = under;
    }

    @Override
    public String name() {
        return under.isEmpty() ? "self test" : "self test of " + under;
    }

    @Override
    public boolean runs(Path checkout, String path) {
        return support(path);
    }

    @Override
    public boolean support(String path) {
        return (path.endsWith(".t") || path.endsWith(".java") && ("/" + path).contains("/jvm/")) && path.startsWith(under);
    }

    private static final java.util.regex.Pattern PACKAGE = java.util.regex.Pattern.compile("package ([\\w.]+);"), IMPORT = java.util.regex.Pattern.compile("import ([\\w.]+);");

    /** The suite of a file's cases: its path, or for a ".java" file its package and class name, as a JUnit report names it. */
    private static String suite(String f, List<String> lines) {
        if (!f.endsWith(".java")) return f;
        String cls = f.substring(f.lastIndexOf('/') + 1, f.length() - ".java".length());
        for (String line : lines) {
            java.util.regex.Matcher m = PACKAGE.matcher(line.strip());
            if (m.matches()) return m.group(1) + "." + cls;
        }
        return cls;
    }

    /** The words a file's cases see: prod.txt's, and the names the file imports. */
    private static String words(String prod, List<String> lines) {
        StringBuilder out = new StringBuilder(prod);
        for (String line : lines) {
            java.util.regex.Matcher m = IMPORT.matcher(line.strip());
            if (m.matches()) out.append('\n').append(m.group(1));
        }
        return out.toString();
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
                    boolean later = how.length > 1 && how[0].equals("compile-later") && prod.contains(how[1]) && call > 1; // a build that stops compiling in a repeat
                    if (later || how.length > 1 && how[0].equals("compile-needs") && !prod.contains(how[1])) {
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
                final String words = prod;
                boolean late = call > 1 && Files.readAllLines(checkout.resolve(f)).stream().anyMatch(line -> line.matches(".*: (timeout|cut)-later \\S+") && words.contains(line.substring(line.lastIndexOf(' ') + 1)));
                boolean once = call == 1 && Files.readAllLines(checkout.resolve(f)).stream().anyMatch(line -> line.matches(".*: cut-once \\S+") && words.contains(line.substring(line.lastIndexOf(' ') + 1)));
                if (late || once || Files.readAllLines(checkout.resolve(f)).stream().anyMatch(line -> line.endsWith(": timeout"))) {
                    out.put(f, FileRun.failed("timed out after " + timeoutSeconds + " s"));
                    continue;
                }
                List<String> all = Files.readAllLines(checkout.resolve(f));
                String sees = words(prod, all), fileSuite = suite(f, all);
                for (String line : all) {
                    if (!line.contains(": ")) continue;
                    String name = line.substring(0, line.indexOf(": "));
                    String[] how = line.substring(line.indexOf(": ") + 2).trim().split(" ");
                    boolean has = how.length > 1 && sees.contains(how[1]);
                    if (how[0].equals("skip-without") && has) continue; // a test declared only where the word is missing
                    if (how[0].equals("skip-later") && call == 1) continue; // a case a provider gives only from the second run on
                    if (how[0].equals("pass-later") && call == 1) continue;
                    if (how[0].equals("pass-with") && !has) continue; // a row the code gives only where it holds the word
                    if (how[0].equals("pass-without") && has) continue; // a row the code no longer gives where it holds the word
                    if (how[0].equals("gone-later") && has && call > 1) continue;
                    if (how[0].equals("tears-down-with") && !has) continue;
                    Status s = switch (how[0]) {
                        case "pass", "pass-with", "pass-later", "pass-without", "gone-later", "pass-retried", "retried-with", "retried-without", "cut-later", "cut-once" -> Status.PASSED;
                        case "fail", "fail-retried", "tears-down-with" -> Status.FAILED;
                        case "error-retried" -> Status.ERRORED;
                        case "fails-later" -> has && call > 1 ? Status.FAILED : Status.PASSED;
                        case "skip", "skip-without", "skip-later" -> Status.SKIPPED;
                        case "skip-unless" -> has ? Status.PASSED : Status.SKIPPED;
                        case "skip-once" -> has && call == 1 ? Status.SKIPPED : Status.PASSED; // with the word: skipped only the first time
                        case "skip-odd" -> has && call % 2 == 1 ? Status.SKIPPED : Status.PASSED; // with the word: skipped every other time, from the first
                        case "pass-once" -> call == 1 ? Status.PASSED : Status.SKIPPED;
                        case "fails-with" -> has ? Status.FAILED : Status.PASSED;
                        case "breaks-once" -> has && call == 1 ? Status.FAILED : Status.PASSED;
                        case "flaky" -> has || call > 1 ? Status.PASSED : Status.FAILED; // without the word: fails only the first time
                        case "needs", "compile-later", "timeout-later" -> has ? Status.PASSED : Status.FAILED;
                        case "error-unless" -> has ? Status.PASSED : Status.ERRORED;
                        case "error-varies" -> has ? Status.PASSED : Status.ERRORED;
                        case "compile-needs" -> Status.PASSED; // it compiled, so the word is there
                        case "agrees" -> how.length > 2 && prod.contains(how[2]) == (Files.isRegularFile(checkout.resolve(how[1])) && Files.readString(checkout.resolve(how[1])).contains(how[2]))
                                ? Status.PASSED : Status.FAILED;
                        case "leans-on" -> how.length > 1 && (Files.isRegularFile(checkout.resolve(how[1])) || Files.isRegularFile(checkout.resolve(".built").resolve(how[1])))
                                ? Status.PASSED : Status.FAILED;
                        default -> Status.ERRORED;
                    };
                    String shown = name.replace("\\'", "'");
                    String suite = fileSuite;
                    if (shown.contains("::")) {
                        suite = shown.substring(0, shown.indexOf("::"));
                        shown = shown.substring(shown.indexOf("::") + 2);
                    }
                    boolean retried = how[0].endsWith("-retried") || how[0].equals("retried-with") && has || how[0].equals("retried-without") && !has;
                    for (String one : shown.contains("%i") ? List.of(shown.replace("%i", "1"), shown.replace("%i", "2")) : List.of(shown)) {
                        String thrown = how[0].equals("error-varies") && call % 2 == 0 ? "java.lang.UnsupportedOperationException" : "java.lang.IllegalStateException";
                        cases.add(new JUnitXml.Case(suite, one, s, s == Status.ERRORED ? thrown : s == Status.FAILED ? "AssertionError" : "",
                                s == Status.FAILED ? "expected something else" : "", retried));
                    }
                }
                if (order == 1 && call % 2 == 0 || order == 2 && call % 2 == 1) java.util.Collections.reverse(cases);
                boolean untold = Files.readAllLines(checkout.resolve(f)).contains("retries not said");
                out.put(f, new FileRun(cases, null, false, untold ? "the stand-in runner does not say whether a test passed only when it ran it again" : null));
            } catch (IOException e) {
                out.put(f, FileRun.failed("cannot be read"));
            }
        }
        return out;
    }
}
