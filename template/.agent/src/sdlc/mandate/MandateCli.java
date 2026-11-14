package sdlc.mandate;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.core.UsageException;
import sdlc.util.Json;

/** sdlc mandate check|show|list: read and validate the mandates in docs/mandates. Needs no tracker. */
public final class MandateCli {
    private MandateCli() {}

    static final String USAGE = """
            usage: java .agent/Sdlc.java mandate <command>
              mandate check <ID>   is docs/mandates/<ID>.json a valid mandate? Exit 0 yes, 1 no
              mandate show <ID>    print the mandate as a card a person can read
              mandate list         every mandate in docs/mandates, valid or not
              mandate approve <ID> approve it yourself, in a terminal: you say first how it could fail
            """;

    /** args is the whole command line: args[0] is "mandate". */
    public static int run(String[] args, Map<String, String> opt) throws IOException {
        String sub = args.length > 1 ? args[1] : "";
        Path root = Ctx.root;
        PrintStream out = System.out;
        return switch (sub) {
            case "check" -> check(root, id(args), out);
            case "show" -> show(root, id(args), out);
            case "list" -> list(root, out);
            case "approve" -> approve(root, id(args), MandateCli::terminal, out);
            default -> {
                System.err.print(USAGE);
                yield 3;
            }
        };
    }

    /** Asks the person at the terminal. There must be one: an approval that arrives through a pipe is nobody's. */
    private static String terminal(String question) {
        java.io.Console console = System.console();
        if (console == null) throw new UsageException("mandate approve is for a person at a terminal. It does not read answers from a pipe or a file.");
        String answer = console.readLine("%s", question);
        return answer == null ? "" : answer.strip();
    }

    /**
     * Approval by the person at the keyboard. The order matters: they say how the change could
     * fail before they see what the draft says, so the draft cannot put the thought in their head.
     * ask puts one question and returns the answer.
     */
    public static int approve(Path root, String id, java.util.function.Function<String, String> ask, PrintStream out) throws IOException {
        // read once: what is shown, what is rewritten and what is approved all come from these bytes
        Mandate.Loaded read = Mandate.loaded(root, id);
        Mandate draft = read.mandate();
        out.println("Approving " + Mandate.DIR + "/" + id + ".json");
        out.println();
        out.println("Nothing of the draft is shown yet. You know what this change is for.");
        String fails = ask.apply("In your own words, this change fails if ... ");
        if (fails.isBlank()) {
            out.println("Nothing entered. Not approved.");
            return 1;
        }
        fails = fails.strip();
        if (fails.length() > 300 || !MandateCard.safe(fails).equals(fails)) throw new UsageException("keep the sentence to one line of plain text, at most 300 characters");
        out.println();
        out.print(MandateCard.render(draft, read.blob()));
        if (draft.failsIf() != null && !draft.failsIf().equals(fails)) out.println("The draft said it fails if: " + MandateCard.safe(draft.failsIf()));
        out.println("You said it fails if:      " + fails);
        out.println();
        out.println("Which of the tests that must pass covers what you said?");
        for (int i = 0; i < draft.scenarios().size(); i++) {
            Mandate.Scenario s = draft.scenarios().get(i);
            out.println("  " + (i + 1) + "  " + MandateCard.safe(s.path() + (s.wholeFile() ? "" : "#" + s.name())));
        }
        out.println("  0  none of them");
        String pick = ask.apply("Number: ");
        int n;
        try {
            n = Integer.parseInt(pick.strip());
        } catch (NumberFormatException e) {
            n = -1;
        }
        if (n < 0 || n > draft.scenarios().size()) {
            out.println("That is not one of the numbers. Not approved.");
            return 1;
        }
        String covers = "";
        if (n > 0) {
            Mandate.Scenario s = draft.scenarios().get(n - 1);
            covers = s.path() + (s.wholeFile() ? "" : "#" + s.name());
        } else {
            out.println("No test covers it. The mandate can still be approved; every verdict will say this is unresolved.");
        }
        if (!ask.apply("Type the mandate's id to approve it (anything else cancels): ").equals(id)) {
            out.println("Not approved.");
            return 1;
        }
        // the file must still be the one that was shown: a draft swapped in while the person was deciding is not approved
        Path file = Mandate.file(root, id);
        String rel = Mandate.DIR + "/" + id + ".json";
        if (!Mandate.read(root, file, rel).equals(read.text())) {
            out.println(rel + " changed while you were deciding. Nothing was approved; look at it again.");
            return 1;
        }
        // the sentence becomes part of the mandate, so the blob that is approved holds the approver's own words
        Map<String, Object> json = new java.util.LinkedHashMap<>(Json.map(Json.parseStrict(read.text())));
        json.put("failsIf", fails);
        String approved = Json.pretty(json) + "\n";
        Mandate.parse(id, approved); // what is about to be written is a valid mandate
        Files.writeString(file, approved, java.nio.charset.StandardCharsets.UTF_8);
        String blob = Mandate.blobOf(root, approved); // of the text just written, not of a second read
        SelfApproval.record(root, id, new SelfApproval.Record(blob, who(root), java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), covers));
        out.println();
        out.println("Approved by you (" + MandateCard.safe(who(root)) + "), blob " + blob.substring(0, Math.min(12, blob.length())) + ".");
        out.println("This is your own approval of your own mandate: it is recorded on this machine only and says so in every verdict.");
        out.println("Commit " + rel + " with the change. Editing it afterwards needs approving again.");
        return 0;
    }

    private static String who(Path root) {
        String name = sdlc.util.Proc.run(root, "git", "config", "user.name").out().strip();
        String mail = sdlc.util.Proc.run(root, "git", "config", "user.email").out().strip();
        return (name.isEmpty() ? "unnamed" : name) + (mail.isEmpty() ? "" : " <" + mail + ">");
    }

    private static String id(String[] args) {
        if (args.length < 3 || args[2].startsWith("--") || args[2].isBlank()) throw new UsageException("missing the mandate id\n" + USAGE);
        Mandate.requireId(args[2]);
        return args[2];
    }

    static int check(Path root, String id, PrintStream out) {
        Path f = Mandate.file(root, id);
        String rel = Mandate.DIR + "/" + id + ".json";
        if (!Files.isRegularFile(f)) {
            out.println(rel + " does not exist.");
            return 1;
        }
        List<String> problems;
        try {
            problems = Mandate.problems(id, Mandate.read(root, f, rel));
        } catch (UsageException e) {
            problems = List.of(e.getMessage());
        }
        if (!problems.isEmpty()) {
            out.println(rel + " is not a valid mandate:");
            for (String p : problems) out.println("  " + p);
            return 1;
        }
        Mandate m = Mandate.load(root, id);
        String blob = Mandate.blobId(root, id);
        out.println(rel + " is a valid mandate (blob " + blob.substring(0, Math.min(12, blob.length())) + ")");
        for (String w : warnings(root, m)) out.println("  warning: " + w);
        return 0;
    }

    /** What is odd about a valid mandate in this working tree, without making it invalid. */
    static List<String> warnings(Path root, Mandate m) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < m.mustStayTrue().size(); k++) {
            String test = m.mustStayTrue().get(k).test();
            if (!Files.isRegularFile(root.resolve(test))) out.add("mustStayTrue[" + k + "] names the test " + test + ", which does not exist in the working tree");
        }
        for (Map.Entry<String, String> e : m.locked().entrySet()) {
            Path f = root.resolve(e.getKey());
            if (!Files.isRegularFile(f)) {
                out.add("oracle.locked names " + e.getKey() + ", which does not exist in the working tree");
                continue;
            }
            String now = Mandate.hashObject(root, e.getKey(), f);
            if (!now.equals(e.getValue()))
                out.add(e.getKey() + " has blob " + short12(now) + " now, but oracle.locked says it must have " + short12(e.getValue()));
        }
        return out;
    }

    static int show(Path root, String id, PrintStream out) {
        Mandate m = Mandate.load(root, id);
        out.print(MandateCard.render(m, Mandate.blobId(root, id)));
        return 0;
    }

    static int list(Path root, PrintStream out) throws IOException {
        Path dir = root.resolve(Mandate.DIR);
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p)).sorted().toList();
            }
        }
        if (files.isEmpty()) {
            out.println("No mandates in " + Mandate.DIR + "/.");
            return 0;
        }
        int width = 0;
        for (Path f : files) width = Math.max(width, idOf(f).length());
        for (Path f : files) {
            String id = idOf(f);
            String state;
            String intent = "";
            if (Mandate.idProblem(id) != null) {
                state = "not valid";
                intent = "(the file name is not a mandate id)";
            } else {
                String json;
                try {
                    json = Mandate.read(root, f, Mandate.DIR + "/" + id + ".json");
                } catch (UsageException e) {
                    json = "";
                }
                List<String> problems = json.isEmpty() ? List.of("cannot be read") : Mandate.problems(id, json);
                state = problems.isEmpty() ? "valid" : "not valid";
                intent = intentOf(json);
            }
            out.println(String.format("%-" + width + "s  %-9s  %s", id, state, intent).stripTrailing());
        }
        return 0;
    }

    private static String idOf(Path f) {
        String name = f.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }

    /** The intent, read leniently so a mandate with problems still shows what it is about. */
    private static String intentOf(String json) {
        try {
            Object v = Json.at(Json.parse(json), "intent");
            return v instanceof String s ? s.replaceAll("\\s+", " ").strip() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String short12(String blob) {
        return blob.length() > 12 ? blob.substring(0, 12) : blob;
    }
}
