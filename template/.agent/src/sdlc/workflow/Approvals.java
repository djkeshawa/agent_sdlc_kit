package sdlc.workflow;

import java.util.ArrayList;
import java.util.List;

import sdlc.core.Ctx;
import sdlc.core.Markers;
import sdlc.core.UsageException;

/**
 * Human approval of a Confluence document version. The approver posts, as a new Jira comment on
 * the document's issue:
 *     SDLC-APPROVED <KEY> <test-design|lld> <pageId>v<version> <hash12>
 * It counts only while the page is still at that version with that content, the comment was
 * not edited, it is not a tool comment, the author may approve (gates.approvers) and is not the
 * person who saved that version of the page (four eyes; gates.allowSelfApproval switches it off).
 */
public final class Approvals {
    public static final String BOT_MARK = "[sdlc-bot]";

    public record Result(boolean approved, String by, Docs.Doc doc, List<String> reasons, String expectedLine) {}

    private final Workflow w;

    Approvals(Workflow w) {
        this.w = w;
    }

    static String typeName(String type) {
        return type.equals("testDesign") ? "test-design" : type;
    }

    public static String fromCli(String arg) {
        return switch (arg) {
            case "test-design", "testDesign", "test", "qa" -> "testDesign";
            case "lld", "design" -> "lld";
            default -> throw new UsageException("document type must be test-design or lld, not " + arg);
        };
    }

    public static String line(String key, String type, Wiki.Page p) {
        return Markers.APPROVAL_WORD + " " + key + " " + typeName(type) + " " + p.id() + "v" + p.version() + " " + hash(p.storage()).substring(0, 12);
    }

    public static String hash(String storage) {
        return Verify.sha256(storage.replaceAll(">\\s+<", "><").replaceAll("\\s+", " ").trim());
    }

    public Result check(String key, String type) {
        List<String> reasons = new ArrayList<>();
        Docs.Doc doc = w.docs.own(key, type);
        if (doc == null) {
            reasons.add("no " + Docs.label(type) + " titled '" + Docs.title(type, key) + "' (or for a parent issue) in space " + Ctx.cfg.str("confluence.publish." + type + ".space"));
            return new Result(false, "", null, reasons, "");
        }
        Wiki.Page page = w.wiki.page(doc.id());
        String expected = line(doc.key(), type, page);
        List<String> approvers = Ctx.cfg.list("gates.approvers." + type);
        List<String> bots = Ctx.cfg.list("gates.botAccounts");
        boolean selfApproval = Ctx.cfg.bool("gates.allowSelfApproval", false);
        for (IssueTracker.Comment c : w.issues.comments(doc.key())) {
            String body = c.body();
            if (!body.contains(Markers.APPROVAL_WORD) || body.contains(BOT_MARK)) continue;
            String id = c.authorId();
            boolean matches = body.lines().map(Approvals::plain).anyMatch(expected::equals);
            boolean sameDoc = body.contains(" " + typeName(type) + " ");
            if (!sameDoc) continue;
            if (!matches) reasons.add("comment by " + c.authorName() + " approves another version of the page (now v" + page.version() + ")");
            else if (bots.contains(id)) reasons.add("comment by bot account " + id + " does not count");
            else if (!approvers.isEmpty() && !approvers.contains(id)) reasons.add("comment by " + c.authorName() + " (" + id + "): not in gates.approvers." + type);
            else if (!selfApproval && sameAccount(id, page.author())) reasons.add("comment by " + c.authorName() + ": the person who saved this version of the page cannot approve it; "
                    + "someone else approves (or set gates.allowSelfApproval: true)");
            else if (c.edited()) reasons.add("comment by " + c.authorName() + " was edited; post a fresh one");
            else return new Result(true, c.authorName() + " on " + c.created(), doc, reasons, expected);
        }
        if (reasons.isEmpty()) reasons.add("no approval comment yet");
        return new Result(false, "", doc, reasons, expected);
    }

    /** Four eyes: the approver is the account that saved the page version. Unknown authors (older servers, mocks) never match. */
    public static boolean sameAccount(String approver, String pageAuthor) {
        return approver != null && pageAuthor != null && !approver.isBlank() && approver.equalsIgnoreCase(pageAuthor.strip());
    }

    /** A comment line without the code formatting Jira's editor adds around a pasted line. */
    public static String plain(String line) {
        return line.replaceAll("\\{(noformat|code)(:[^}]*)?\\}|`", "").strip();
    }

    /** Short line for pulled sources: approved / not approved. */
    String statusLine(String key, String type) {
        try {
            Result r = check(key, type);
            return r.approved() ? "APPROVED by " + r.by() : "NOT APPROVED (" + String.join("; ", r.reasons()) + ")";
        } catch (RuntimeException e) {
            return "approval unknown (" + e.getMessage() + ")";
        }
    }

    /** Enforces gates.<type>: required -> must be approved; optional -> warn; off -> skip. */
    boolean gate(String key, String type) {
        String mode = Ctx.cfg.str("gates." + type, "required");
        if (mode.equals("off")) return true;
        Result r = check(key, type);
        if (r.approved()) {
            w.console.out(Docs.label(type) + " of " + r.doc().key() + " APPROVED by " + r.by());
            return true;
        }
        w.console.err(Docs.label(type) + " of " + key + " is NOT approved: " + String.join("; ", r.reasons()));
        if (!r.expectedLine().isEmpty()) w.console.err("The approver posts this line as a new Jira comment on " + r.doc().key() + ":\n    " + r.expectedLine());
        if (mode.equals("optional")) {
            w.console.err("(gates." + type + " is optional: continuing)");
            return true;
        }
        return false;
    }

    public int cli(String action, String key, String type) {
        if (action.equals("line")) {
            Docs.Doc d = w.docs.own(key, type);
            if (d == null) throw new UsageException("no published " + Docs.label(type) + " for " + key);
            w.console.out(line(d.key(), type, w.wiki.page(d.id())));
            return 0;
        }
        Result r = check(key, type);
        if (r.approved()) {
            w.console.out("APPROVED: " + Docs.label(type) + " v" + r.doc().version() + " of " + r.doc().key() + " by " + r.by());
            return 0;
        }
        w.console.err("NOT APPROVED: " + String.join("; ", r.reasons()));
        if (!r.expectedLine().isEmpty()) w.console.err("Line to post on " + r.doc().key() + ":\n    " + r.expectedLine());
        return 1;
    }
}
