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
            // "approve" belongs here: the lead's part, not yet built
            default -> {
                System.err.print(USAGE);
                yield 3;
            }
        };
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
            problems = Mandate.problems(id, Mandate.read(f, rel));
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
                    json = Mandate.read(f, Mandate.DIR + "/" + id + ".json");
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
