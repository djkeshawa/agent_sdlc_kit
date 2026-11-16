package sdlc.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sdlc.check.JUnitXml.Status;

final class SelfTestRunner implements Runner {
    private final Map<Path, Integer> calls = new java.util.HashMap<>();
    static int order;
    private final String under;

    SelfTestRunner() {
        this("");
    }

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

    private static String suite(String f, List<String> lines) {
        if (!f.endsWith(".java")) return f;
        String cls = f.substring(f.lastIndexOf('/') + 1, f.length() - ".java".length());
        for (String line : lines) {
            java.util.regex.Matcher m = PACKAGE.matcher(line.strip());
            if (m.matches()) return m.group(1) + "." + cls;
        }
        return cls;
    }

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
        for (String f : files) {
            try {
                for (String line : Files.readAllLines(checkout.resolve(f))) {
                    String[] how = line.contains(": ") ? line.substring(line.indexOf(": ") + 2).trim().split(" ") : new String[0];
                    boolean later = how.length > 1 && how[0].equals("compile-later") && prod.contains(how[1]) && call > 1;
                    if (later || how.length > 1 && how[0].equals("compile-needs") && !prod.contains(how[1])) {
                        for (String all : files) out.put(all, FileRun.broken(Witness.DOES_NOT_COMPILE));
                        return out;
                    }
                }
            } catch (IOException e) {
            }
        }
        for (String f : files) {
            List<JUnitXml.Case> cases = new ArrayList<>();
            try {
                Path built = checkout.resolve(".built").resolve(f);
                sdlc.util.Fs.makeParent(built);
                Files.writeString(built, "");
                final String words = prod;
                boolean late = call > 1 && Files.readAllLines(checkout.resolve(f)).stream().anyMatch(line -> line.matches(".*: (timeout|cut)-later \\S+") && words.contains(line.substring(line.lastIndexOf(' ') + 1)));
                boolean once = call == 1 && Files.readAllLines(checkout.resolve(f)).stream().anyMatch(line -> line.matches(".*: cut-once \\S+") && words.contains(line.substring(line.lastIndexOf(' ') + 1)));
                if (late || once || Files.readAllLines(checkout.resolve(f)).stream().anyMatch(line -> line.endsWith(": timeout"))) {
                    out.put(f, FileRun.failed("timed out after " + timeoutSeconds + " s"));
                    continue;
                }
                String absent = Files.readAllLines(checkout.resolve(f)).stream().filter(line -> line.matches(".*: (timeout|unstarted)-unless \\S+")
                        && !words.contains(line.substring(line.lastIndexOf(' ') + 1))).findFirst().orElse(null);
                if (absent != null) {
                    out.put(f, FileRun.failed(absent.contains(": timeout-") ? "timed out after " + timeoutSeconds + " s" : "Maven could not be started (mvn: not found)"));
                    continue;
                }
                List<String> all = Files.readAllLines(checkout.resolve(f));
                String sees = words(prod, all), fileSuite = suite(f, all);
                for (String line : all) {
                    if (!line.contains(": ")) continue;
                    String name = line.substring(0, line.indexOf(": "));
                    String[] how = line.substring(line.indexOf(": ") + 2).trim().split(" ");
                    boolean has = how.length > 1 && sees.contains(how[1]);
                    if (how[0].equals("skip-without") && has) continue;
                    if (how[0].equals("skip-later") && call == 1) continue;
                    if (how[0].equals("pass-later") && call == 1) continue;
                    if (how[0].equals("pass-with") && !has) continue;
                    if (how[0].equals("needs-with") && !(how.length > 2 && sees.contains(how[2]))) continue;
                    if (how[0].equals("pass-without") && has) continue;
                    if (how[0].equals("gone-later") && has && call > 1) continue;
                    if (how[0].equals("tears-down-with") && !has) continue;
                    if (how[0].equals("error-without") && has) continue;
                    Status s = switch (how[0]) {
                        case "pass", "pass-with", "pass-later", "pass-without", "gone-later", "pass-retried", "retried-with", "retried-without", "cut-later", "cut-once",
                                "timeout-unless", "unstarted-unless" -> Status.PASSED;
                        case "fail", "fail-retried", "tears-down-with" -> Status.FAILED;
                        case "error-retried" -> Status.ERRORED;
                        case "fails-later" -> has && call > 1 ? Status.FAILED : Status.PASSED;
                        case "skip", "skip-without", "skip-later" -> Status.SKIPPED;
                        case "skip-unless" -> has ? Status.PASSED : Status.SKIPPED;
                        case "skip-once" -> has && call == 1 ? Status.SKIPPED : Status.PASSED;
                        case "skip-odd" -> has && call % 2 != 0 ? Status.SKIPPED : Status.PASSED;
                        case "pass-once" -> call == 1 ? Status.PASSED : Status.SKIPPED;
                        case "fails-with" -> has ? Status.FAILED : Status.PASSED;
                        case "breaks-once" -> has && call == 1 ? Status.FAILED : Status.PASSED;
                        case "flaky" -> has || call > 1 ? Status.PASSED : Status.FAILED;
                        case "needs", "needs-with", "compile-later", "timeout-later" -> has ? Status.PASSED : Status.FAILED;
                        case "error-unless", "throws-unless" -> has ? Status.PASSED : Status.ERRORED;
                        case "error-odd" -> has ? Status.PASSED : call % 2 != 0 ? Status.ERRORED : Status.FAILED;
                        case "error-even" -> has ? Status.PASSED : call % 2 == 0 ? Status.ERRORED : Status.FAILED;
                        case "error-once" -> has ? Status.PASSED : call == 1 ? Status.ERRORED : Status.PASSED;
                        case "error-without" -> Status.ERRORED;
                        case "error-varies", "error-twins" -> has ? Status.PASSED : Status.ERRORED;
                        case "compile-needs" -> Status.PASSED;
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
                        String thrown = how[0].equals("throws-unless") && how.length > 2 ? how[2]
                                : how[0].equals("error-twins") ? (call % 2 == 0 ? "a.ValidationException" : "b.ValidationException")
                                : how[0].equals("error-varies") && call % 2 == 0 ? "java.lang.UnsupportedOperationException" : "java.lang.IllegalStateException";
                        cases.add(new JUnitXml.Case(suite, one, s, s == Status.ERRORED ? thrown : s == Status.FAILED ? "AssertionError" : "",
                                s == Status.FAILED ? "expected something else" : "", retried));
                    }
                }
                if (order == 1 && call % 2 == 0 || order == 2 && call % 2 != 0) java.util.Collections.reverse(cases);
                boolean untold = Files.readAllLines(checkout.resolve(f)).contains("retries not said");
                out.put(f, new FileRun(cases, null, false, untold ? "the stand-in runner does not say whether a test passed only when it ran it again" : null));
            } catch (IOException e) {
                out.put(f, FileRun.failed("cannot be read"));
            }
        }
        return out;
    }
}
