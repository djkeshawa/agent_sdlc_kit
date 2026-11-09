package sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Jira Cloud and Data Center through REST v2 (plus v3 search on Cloud). Every write is logged. */
final class Jira {
    private Jira() {}

    static String base() {
        String b = Ctx.secret("JIRA_BASE_URL", Ctx.cfg.str("jira.url"));
        if (b.isBlank()) throw new Main.UsageException("jira.url is not set in .agent/sdlc.yml");
        return b.replaceAll("/+$", "");
    }

    static String auth() {
        String token = Ctx.need("JIRA_TOKEN", "Jira API token or personal access token");
        if (Ctx.cfg.cloud()) return Http.basic(Ctx.need("JIRA_USER", "your Atlassian e-mail"), token);
        return "Bearer " + token;
    }

    static Object call(String method, String path, Object body) {
        return Http.json(method, base() + path, auth(), body);
    }

    static Map<String, Object> issue(String key, String fields) {
        return Json.obj(call("GET", "/rest/api/2/issue/" + Http.enc(key) + "?fields=" + fields, null));
    }

    static Map<String, Object> myself() {
        return Json.obj(call("GET", "/rest/api/2/myself", null));
    }

    static String accountId(Map<String, Object> user) {
        for (String k : List.of("accountId", "name", "key")) if (user.get(k) != null) return user.get(k).toString();
        return "?";
    }

    static String display(Object user) {
        return user instanceof Map<?, ?> m ? String.valueOf(m.get("displayName")) : "unassigned";
    }

    /** The issue as text for agents, with its parent chain. */
    static String text(String key, int maxComments) {
        Map<String, Object> i = issue(key, "summary,status,issuetype,priority,assignee,reporter,labels,components,fixVersions,description,parent,subtasks,issuelinks,comment");
        Object f = i.get("fields");
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(key).append(": ").append(Json.str(f, "summary")).append("\n\n");
        sb.append("- Type: ").append(Json.str(f, "issuetype.name")).append("\n- Status: ").append(Json.str(f, "status.name"))
                .append("\n- Priority: ").append(Json.str(f, "priority.name")).append("\n- Assignee: ").append(display(Json.at(f, "assignee")))
                .append("\n- Reporter: ").append(display(Json.at(f, "reporter"))).append("\n- Labels: ").append(Json.at(f, "labels")).append('\n');
        List<String> parents = parents(key);
        if (!parents.isEmpty()) sb.append("- Parents: ").append(String.join(" -> ", parents)).append('\n');
        List<Object> subs = Json.list(Json.at(f, "subtasks"));
        if (!subs.isEmpty()) {
            sb.append("- Sub-tasks:\n");
            for (Object o : subs) sb.append("  - ").append(Json.str(o, "key")).append(" [").append(Json.str(o, "fields.status.name")).append("] ").append(Json.str(o, "fields.summary")).append('\n');
        }
        for (Object o : Json.list(Json.at(f, "issuelinks"))) {
            String dir = Json.at(o, "outwardIssue") != null ? "outwardIssue" : "inwardIssue";
            sb.append("- Link: ").append(Json.str(o, "type.name")).append(" ").append(Json.str(o, dir + ".key")).append(" ").append(Json.str(o, dir + ".fields.summary")).append('\n');
        }
        sb.append("\n## Description\n\n").append(Json.at(f, "description") == null ? "(empty)" : Json.at(f, "description")).append('\n');
        List<Object> comments = Json.list(Json.at(f, "comment.comments"));
        if (!comments.isEmpty() && maxComments > 0) {
            sb.append("\n## Last ").append(Math.min(maxComments, comments.size())).append(" of ").append(comments.size()).append(" comments\n\n");
            for (Object c : comments.subList(Math.max(0, comments.size() - maxComments), comments.size())) {
                sb.append("**").append(display(Json.at(c, "author"))).append("** (").append(Json.str(c, "created")).append("):\n").append(Json.str(c, "body")).append("\n\n");
            }
        }
        return sb.toString();
    }

    /** Parent keys, nearest first: sub-task -> story -> epic. */
    static List<String> parents(String key) {
        List<String> out = new ArrayList<>();
        String epicField = Ctx.cfg.str("jira.epicLinkField");
        String cur = key;
        for (int depth = 0; depth < 4; depth++) {
            Map<String, Object> i = issue(cur, "parent" + (epicField.isBlank() ? "" : "," + epicField));
            String p = Json.str(i, "fields.parent.key");
            if (p.isBlank() && !epicField.isBlank()) p = Json.str(i, "fields." + epicField);
            if (p.isBlank() || out.contains(p)) break;
            out.add(p);
            cur = p;
        }
        return out;
    }

    static String status(String key) {
        return Json.str(issue(key, "status"), "fields.status.name");
    }

    static List<Map<String, Object>> comments(String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        int startAt = 0;
        while (true) {
            Map<String, Object> page = Json.obj(call("GET", "/rest/api/2/issue/" + Http.enc(key) + "/comment?startAt=" + startAt + "&maxResults=100", null));
            List<Object> cs = Json.list(page.get("comments"));
            for (Object c : cs) out.add(Json.obj(c));
            startAt += cs.size();
            int total = page.get("total") instanceof Number n ? n.intValue() : 0;
            if (cs.isEmpty() || startAt >= total) return out;
        }
    }

    static void comment(String key, String body, boolean fromAgent) {
        if (fromAgent && body.toUpperCase(Locale.ROOT).contains(ShellRules.APPROVAL_WORD)) {
            throw new Main.UsageException("Refused: approvals are posted by a human in the Jira web page, never through this tool.");
        }
        Map<String, Object> r = Json.obj(call("POST", "/rest/api/2/issue/" + Http.enc(key) + "/comment", Store.map("body", body)));
        Store.log("atlassian", Store.map("action", "jira-comment", "key", key, "id", r.get("id")));
    }

    static List<Map<String, Object>> transitions(String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.list(Json.obj(call("GET", "/rest/api/2/issue/" + Http.enc(key) + "/transitions", null)).get("transitions"))) out.add(Json.obj(o));
        return out;
    }

    /** Moves the issue to a status by name; agents may only use the configured statuses. */
    static boolean transition(String key, String status, boolean fromAgent) {
        if (fromAgent) {
            List<String> allowed = new ArrayList<>(List.of(Ctx.cfg.str("jira.statuses.inProgress"), Ctx.cfg.str("jira.statuses.inReview")));
            if (Ctx.cfg.bool("jira.agentMayClose", false)) allowed.add(Ctx.cfg.str("jira.statuses.done"));
            if (allowed.stream().noneMatch(s -> s.equalsIgnoreCase(status))) {
                throw new Main.UsageException("Agents may move issues only to " + allowed + ". A human makes other moves in Jira.");
            }
        }
        for (Map<String, Object> t : transitions(key)) {
            if (status.equalsIgnoreCase(String.valueOf(t.get("name"))) || status.equalsIgnoreCase(Json.str(t, "to.name"))) {
                call("POST", "/rest/api/2/issue/" + Http.enc(key) + "/transitions", Store.map("transition", Store.map("id", t.get("id"))));
                Store.log("atlassian", Store.map("action", "jira-transition", "key", key, "to", status));
                return true;
            }
        }
        return false;
    }

    /** Moves forward only: To Do -> In Progress -> In Review -> Done. */
    static boolean forward(String key, String target) {
        List<String> order = List.of(Ctx.cfg.str("jira.statuses.inProgress"), Ctx.cfg.str("jira.statuses.inReview"), Ctx.cfg.str("jira.statuses.done"));
        String current = status(key);
        int cur = indexOf(order, current);
        int want = indexOf(order, target);
        if (want <= cur) return false;
        return transition(key, target, false);
    }

    private static int indexOf(List<String> order, String s) {
        for (int i = 0; i < order.size(); i++) if (order.get(i).equalsIgnoreCase(s)) return i;
        return -1;
    }

    static String createSubtask(String parent, String summary, String description) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Store.map("key", parent.substring(0, parent.indexOf('-'))));
        fields.put("parent", Store.map("key", parent));
        fields.put("summary", summary);
        fields.put("issuetype", Store.map("name", Ctx.cfg.str("jira.issueTypes.task", "Sub-task")));
        fields.put("description", description);
        Map<String, Object> r = Json.obj(call("POST", "/rest/api/2/issue", Store.map("fields", fields)));
        Store.log("atlassian", Store.map("action", "jira-create", "parent", parent, "key", r.get("key"), "summary", summary));
        return String.valueOf(r.get("key"));
    }

    static List<Map<String, Object>> search(String jql, int max) {
        Map<String, Object> body = Store.map("jql", jql, "maxResults", max, "fields", List.of("summary", "status", "issuetype", "assignee", "parent"));
        Object r = Ctx.cfg.cloud() ? call("POST", "/rest/api/3/search/jql", body) : call("POST", "/rest/api/2/search", body);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.list(Json.at(r, "issues"))) out.add(Json.obj(o));
        return out;
    }

    static void link(String key, String url, String title) {
        call("POST", "/rest/api/2/issue/" + Http.enc(key) + "/remotelink", Store.map("globalId", url, "object", Store.map("url", url, "title", title)));
        Store.log("atlassian", Store.map("action", "jira-link", "key", key, "url", url));
    }
}
