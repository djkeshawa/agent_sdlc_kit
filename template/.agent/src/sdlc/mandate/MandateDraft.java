package sdlc.mandate;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sdlc.core.UsageException;
import sdlc.util.Json;
import sdlc.util.Proc;

// Finishes what a drafter wrote: what the tool knows for sure, never the approver's sentence, never an approval.
final class MandateDraft {
    private MandateDraft() {}

    static int draft(Path root, String id, PrintStream to) throws IOException {
        PrintStream out = sdlc.util.SafeOut.stream(to);
        String rel = Mandate.DIR + "/" + id + ".json";
        Path file = Mandate.file(root, id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            out.println(rel + " does not exist.");
            out.println("Write it first: one JSON object with \"intent\" (one sentence), \"scope\": { \"paths\": [...] } and \"oracle\": { \"scenarios\": [...] },");
            out.println("and \"mustStayTrue\" if something must stay true. Then run sdlc mandate draft " + id + " again.");
            return 1;
        }
        String text;
        try {
            text = Mandate.read(root, file, rel);
        } catch (UsageException e) {
            return refused(out, rel, List.of(e.getMessage()));
        }
        SelfApproval.Record approval = SelfApproval.of(root, id);
        if (approval != null && approval.blob().equals(Mandate.blobOf(root, text))) {
            out.println(rel + " is already approved; an edit needs a new approval. Nothing was changed.");
            return 0;
        }
        Object parsed;
        try {
            parsed = Json.parseStrict(text);
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        if (!(parsed instanceof Map<?, ?>)) return refused(out, rel, Mandate.problems(id, text));
        Map<String, Object> drafted = Json.obj(parsed);
        if (drafted.containsKey("failsIf")) {
            out.println(rel + " holds \"failsIf\". That sentence is the approver's own: sdlc mandate approve asks a person for it before it shows the draft, and writes it.");
            if (approval != null) out.println("This mandate was edited since it was approved. The edit needs a new approval, and the approver writes the sentence again.");
            out.println("Take \"failsIf\" out of the draft. Nothing was written.");
            return 1;
        }

        List<String> done = new ArrayList<>(), problems = new ArrayList<>(), unlocked = new ArrayList<>();
        Map<String, Object> json = new LinkedHashMap<>();
        if (!drafted.containsKey("mandate")) {
            json.put("mandate", "v0");
            done.add("added \"mandate\": \"v0\"");
        }
        if (!drafted.containsKey("id")) {
            json.put("id", id);
            done.add("added \"id\": \"" + id + "\"");
        }
        json.putAll(drafted);
        lock(root, json, done, problems, unlocked);
        String result = Json.pretty(json) + "\n";
        for (String p : Mandate.problems(id, result)) if (unlocked.stream().noneMatch(p::contains)) problems.add(p);
        if (problems.isEmpty() && result.getBytes(StandardCharsets.UTF_8).length > Mandate.MAX_BYTES)
            problems.add("it would be larger than " + Mandate.MAX_BYTES / 1024 + " KB once written in the layout the tool writes; make the mandate shorter");
        if (!problems.isEmpty()) return refused(out, rel, problems);

        boolean changed = !result.equals(text);
        if (changed) Files.writeString(file, result, StandardCharsets.UTF_8);
        out.print(MandateCard.render(Mandate.parse(id, result), Mandate.blobOf(root, result)));
        out.println();
        if (!changed) out.println("Nothing to change in " + rel + ".");
        else if (done.isEmpty()) out.println("Wrote " + rel + " in the layout the tool writes; nothing in it changed.");
        else {
            out.println("Wrote " + rel + ":");
            for (String d : done) out.println("  " + d);
        }
        out.println("This is a draft. It is not approved.");
        out.println("A person approves it in a terminal, with: sdlc mandate approve " + id);
        return 0;
    }

    // Each test that must stay true, and each test already locked, is locked as the file it is now in the working tree.
    private static void lock(Path root, Map<String, Object> json, List<String> done, List<String> problems, List<String> unlocked) {
        Map<String, String> where = new LinkedHashMap<>();
        if (json.get("mustStayTrue") instanceof List<?> truths) {
            for (int k = 0; k < truths.size(); k++)
                if (truths.get(k) instanceof Map<?, ?> t && t.get("test") instanceof String test && Mandate.plainFile(test)) where.putIfAbsent(test, "mustStayTrue[" + k + "].test");
        }
        if (!json.containsKey("oracle") && !where.isEmpty()) json.put("oracle", new LinkedHashMap<String, Object>());
        if (!(json.get("oracle") instanceof Map<?, ?> oracle)) return;
        Map<String, Object> o = Json.obj(oracle);
        if (!o.containsKey("locked") && !where.isEmpty()) o.put("locked", new LinkedHashMap<String, Object>());
        if (!(o.get("locked") instanceof Map<?, ?> lockedMap)) return;
        Map<String, Object> locked = Json.obj(lockedMap);
        for (String path : locked.keySet()) if (Mandate.plainFile(path)) where.putIfAbsent(path, "oracle.locked key");
        for (Map.Entry<String, String> e : where.entrySet()) {
            String path = e.getKey(), named = e.getValue() + " \"" + path + "\"";
            String why = unlockable(root, path);
            if (why != null) {
                problems.add(named + " " + why);
                unlocked.add(".test \"" + path + "\" is not in oracle.locked");
                continue;
            }
            String now = Mandate.hashObject(root, path, root.resolve(path));
            Object was = locked.get(path);
            if (now.equals(was)) continue;
            locked.put(path, now);
            if (was == null) done.add("locked " + path + " at blob " + short12(now));
            else done.add("oracle.locked[\"" + path + "\"] said " + (was instanceof String s ? short12(s) : "no blob id") + "; the file has blob " + short12(now) + ": replaced");
        }
    }

    // git keeps a file at its own path only: not a link, not a file behind a linked folder, and not a file it ignores and does not track.
    private static String unlockable(Path root, String path) {
        Path f = root.resolve(path);
        try {
            if (Files.isSymbolicLink(f)) return "is a link; a locked test is a file of its own";
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return "does not exist in the working tree. Write the test first: it is locked as the file it is now";
            if (!f.toRealPath().equals(root.toRealPath().resolve(path).normalize()))
                return "is reached through a link, or its name differs from the file on disk; git keeps no file at that path";
        } catch (IOException e) {
            return "cannot be read: " + e.getMessage();
        }
        // without --no-index it answers only for files the index does not hold: what git add -A leaves out of the check's snapshot
        Proc ignore = Proc.run(root, "git", "check-ignore", "-q", "--", path);
        if (ignore.code() == 0) return "is ignored by git, so the check will not find it. Take it out of .gitignore or name another test";
        if (ignore.code() != 1) return "cannot be checked against .gitignore: " + ignore.err().strip();
        return null;
    }

    private static int refused(PrintStream out, String rel, List<String> problems) {
        out.println(rel + " is not a valid mandate:");
        for (String p : problems) out.println("  " + p);
        out.println("Nothing was written.");
        return 1;
    }

    private static String short12(String blob) {
        return blob.length() > 12 ? blob.substring(0, 12) : blob;
    }
}
