package sdlc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Human approval of a Confluence document version. The approver posts, as a new Jira comment on
 * the document's issue:
 *     SDLC-APPROVED <KEY> <test-design|lld> <pageId>v<version> <hash12>
 * It counts only while the page is still at that version with that content, the comment was
 * not edited, it is not a tool comment, and the author may approve (gates.approvers).
 */
final class Approvals {
    static final String BOT_MARK = "[sdlc-bot]";

    record Result(boolean approved, String by, Docs.Doc doc, List<String> reasons, String expectedLine) {}

    private Approvals() {}

    static String typeName(String type) {
        return type.equals("testDesign") ? "test-design" : type;
    }

    static String fromCli(String arg) {
        return switch (arg) {
            case "test-design", "testDesign", "test", "qa" -> "testDesign";
            case "lld", "design" -> "lld";
            default -> throw new Main.UsageException("document type must be test-design or lld, not " + arg);
        };
    }

    static String line(String key, String type, Confluence.Page p) {
        return ShellRules.APPROVAL_WORD + " " + key + " " + typeName(type) + " " + p.id() + "v" + p.version() + " " + hash(p.storage()).substring(0, 12);
    }

    static String hash(String storage) {
        return Verify.sha256(storage.replaceAll(">\\s+<", "><").replaceAll("\\s+", " ").trim());
    }

    static Result check(String key, String type) {
        List<String> reasons = new ArrayList<>();
        Docs.Doc doc = Docs.own(key, type);
        if (doc == null) {
            reasons.add("no " + Docs.label(type) + " titled '" + Docs.title(type, key) + "' (or for a parent issue) in space " + Ctx.cfg.str("confluence.publish." + type + ".space"));
            return new Result(false, "", null, reasons, "");
        }
        Confluence.Page page = Confluence.page(doc.id());
        String expected = line(doc.key(), type, page);
        List<String> approvers = Ctx.cfg.list("gates.approvers." + type);
        List<String> bots = Ctx.cfg.list("gates.botAccounts");
        for (Map<String, Object> c : Jira.comments(doc.key())) {
            String body = String.valueOf(c.get("body"));
            if (!body.contains(ShellRules.APPROVAL_WORD) || body.contains(BOT_MARK)) continue;
            Map<String, Object> author = c.get("author") instanceof Map<?, ?> ? Json.obj(c.get("author")) : Map.of();
            String id = Jira.accountId(author);
            boolean matches = body.lines().map(Approvals::plain).anyMatch(expected::equals);
            boolean sameDoc = body.contains(" " + typeName(type) + " ");
            if (!sameDoc) continue;
            if (!matches) reasons.add("comment by " + Jira.display(author) + " approves another version of the page (now v" + page.version() + ")");
            else if (bots.contains(id)) reasons.add("comment by bot account " + id + " does not count");
            else if (!approvers.isEmpty() && !approvers.contains(id)) reasons.add("comment by " + Jira.display(author) + " (" + id + "): not in gates.approvers." + type);
            else if (!String.valueOf(c.get("created")).equals(String.valueOf(c.get("updated")))) reasons.add("comment by " + Jira.display(author) + " was edited; post a fresh one");
            else return new Result(true, Jira.display(author) + " on " + c.get("created"), doc, reasons, expected);
        }
        if (reasons.isEmpty()) reasons.add("no approval comment yet");
        return new Result(false, "", doc, reasons, expected);
    }

    /** A comment line without the code formatting Jira's editor adds around a pasted line. */
    static String plain(String line) {
        return line.replaceAll("\\{(noformat|code)(:[^}]*)?\\}|`", "").strip();
    }

    /** Short line for pulled sources: approved / not approved. */
    static String statusLine(String key, String type) {
        try {
            Result r = check(key, type);
            return r.approved() ? "APPROVED by " + r.by() : "NOT APPROVED (" + String.join("; ", r.reasons()) + ")";
        } catch (RuntimeException e) {
            return "approval unknown (" + e.getMessage() + ")";
        }
    }

    /** Enforces gates.<type>: required -> must be approved; optional -> warn; off -> skip. */
    static boolean gate(String key, String type) {
        String mode = Ctx.cfg.str("gates." + type, "required");
        if (mode.equals("off")) return true;
        Result r = check(key, type);
        if (r.approved()) {
            System.out.println(Docs.label(type) + " of " + r.doc().key() + " APPROVED by " + r.by());
            return true;
        }
        System.err.println(Docs.label(type) + " of " + key + " is NOT approved: " + String.join("; ", r.reasons()));
        if (!r.expectedLine().isEmpty()) System.err.println("The approver posts this line as a new Jira comment on " + r.doc().key() + ":\n    " + r.expectedLine());
        if (mode.equals("optional")) {
            System.err.println("(gates." + type + " is optional: continuing)");
            return true;
        }
        return false;
    }

    static int cli(String action, String key, String type) {
        if (action.equals("line")) {
            Docs.Doc d = Docs.own(key, type);
            if (d == null) throw new Main.UsageException("no published " + Docs.label(type) + " for " + key);
            System.out.println(line(d.key(), type, Confluence.page(d.id())));
            return 0;
        }
        Result r = check(key, type);
        if (r.approved()) {
            System.out.println("APPROVED: " + Docs.label(type) + " v" + r.doc().version() + " of " + r.doc().key() + " by " + r.by());
            return 0;
        }
        System.err.println("NOT APPROVED: " + String.join("; ", r.reasons()));
        if (!r.expectedLine().isEmpty()) System.err.println("Line to post on " + r.doc().key() + ":\n    " + r.expectedLine());
        return 1;
    }
}
