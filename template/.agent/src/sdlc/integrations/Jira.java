package sdlc.integrations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Markers;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.util.Http;
import sdlc.util.Json;
import sdlc.workflow.IssueTracker;

/** The issue tracker on Jira Cloud and Data Center, through REST v2 (plus v3 search on Cloud). Every write is logged. */
public final class Jira implements IssueTracker {
    private final Config cfg;

    public Jira(Config cfg) {
        this.cfg = cfg;
    }

    private String base() {
        String b = Ctx.secret("JIRA_BASE_URL", cfg.str("jira.url"));
        if (b.isBlank()) throw new UsageException("jira.url is not set in .agent/sdlc.yml");
        return b.replaceAll("/+$", "");
    }

    private String auth() {
        String token = Ctx.need("JIRA_TOKEN", "Jira API token or personal access token");
        if (cfg.cloud()) return Http.basic(Ctx.need("JIRA_USER", "your Atlassian e-mail"), token);
        return "Bearer " + token;
    }

    private Object call(String method, String path, Object body) {
        return Http.json(method, base() + path, auth(), body);
    }

    private Map<String, Object> issue(String key, String fields) {
        return Json.obj(call("GET", "/rest/api/2/issue/" + Http.enc(key) + "?fields=" + fields, null));
    }

    private Map<String, Object> myself() {
        return Json.obj(call("GET", "/rest/api/2/myself", null));
    }

    private static String accountId(Map<String, Object> user) {
        for (String k : List.of("accountId", "name", "key")) if (user.get(k) != null) return user.get(k).toString();
        return "?";
    }

    private static String display(Object user) {
        return user instanceof Map<?, ?> m ? String.valueOf(m.get("displayName")) : "unassigned";
    }

    /** The issue as text for agents, with its parent chain. */
    @Override
    public String text(String key, int maxComments) {
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
    @Override
    public List<String> parents(String key) {
        List<String> out = new ArrayList<>();
        String epicField = cfg.str("jira.epicLinkField");
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

    private String status(String key) {
        return Json.str(issue(key, "status"), "fields.status.name");
    }

    @Override
    public List<Comment> comments(String key) {
        List<Comment> out = new ArrayList<>();
        int startAt = 0;
        while (true) {
            Map<String, Object> page = Json.obj(call("GET", "/rest/api/2/issue/" + Http.enc(key) + "/comment?startAt=" + startAt + "&maxResults=100", null));
            List<Object> cs = Json.list(page.get("comments"));
            for (Object o : cs) {
                Map<String, Object> c = Json.obj(o);
                Map<String, Object> author = c.get("author") instanceof Map<?, ?> ? Json.obj(c.get("author")) : Map.of();
                out.add(new Comment(String.valueOf(c.get("body")), accountId(author), display(author), String.valueOf(c.get("created")), String.valueOf(c.get("updated"))));
            }
            startAt += cs.size();
            int total = page.get("total") instanceof Number n ? n.intValue() : 0;
            if (cs.isEmpty() || startAt >= total) return out;
        }
    }

    @Override
    public void comment(String key, String body, boolean fromAgent) {
        if (fromAgent && body.toUpperCase(Locale.ROOT).contains(Markers.APPROVAL_WORD)) {
            throw new UsageException("Refused: approvals are posted by a human in the Jira web page, never through this tool.");
        }
        Map<String, Object> r = Json.obj(call("POST", "/rest/api/2/issue/" + Http.enc(key) + "/comment", Store.map("body", body)));
        Store.log("atlassian", Store.map("action", "jira-comment", "key", key, "id", r.get("id")));
    }

    @Override
    public List<Transition> transitions(String key) {
        List<Transition> out = new ArrayList<>();
        for (Object o : Json.list(Json.obj(call("GET", "/rest/api/2/issue/" + Http.enc(key) + "/transitions", null)).get("transitions"))) {
            out.add(new Transition(String.valueOf(Json.obj(o).get("id")), String.valueOf(Json.obj(o).get("name")), Json.str(o, "to.name")));
        }
        return out;
    }

    /** Moves the issue to a status by name; agents may only use the configured statuses. */
    @Override
    public boolean transition(String key, String status, boolean fromAgent) {
        if (fromAgent) {
            List<String> allowed = new ArrayList<>(List.of(cfg.str("jira.statuses.inProgress"), cfg.str("jira.statuses.inReview")));
            if (cfg.bool("jira.agentMayClose", false)) allowed.add(cfg.str("jira.statuses.done"));
            if (allowed.stream().noneMatch(s -> s.equalsIgnoreCase(status))) {
                throw new UsageException("Agents may move issues only to " + allowed + ". A human makes other moves in Jira.");
            }
        }
        for (Transition t : transitions(key)) {
            if (status.equalsIgnoreCase(t.name()) || status.equalsIgnoreCase(t.to())) {
                call("POST", "/rest/api/2/issue/" + Http.enc(key) + "/transitions", Store.map("transition", Store.map("id", t.id())));
                Store.log("atlassian", Store.map("action", "jira-transition", "key", key, "to", status));
                return true;
            }
        }
        return false;
    }

    /** Moves forward only: To Do -> In Progress -> In Review -> Done. */
    @Override
    public boolean forward(String key, String target) {
        List<String> order = List.of(cfg.str("jira.statuses.inProgress"), cfg.str("jira.statuses.inReview"), cfg.str("jira.statuses.done"));
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

    @Override
    public String createSubtask(String parent, String summary, String description) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Store.map("key", parent.substring(0, parent.indexOf('-'))));
        fields.put("parent", Store.map("key", parent));
        fields.put("summary", summary);
        fields.put("issuetype", Store.map("name", cfg.str("jira.issueTypes.task", "Sub-task")));
        fields.put("description", description);
        Map<String, Object> r = Json.obj(call("POST", "/rest/api/2/issue", Store.map("fields", fields)));
        Store.log("atlassian", Store.map("action", "jira-create", "parent", parent, "key", r.get("key"), "summary", summary));
        return String.valueOf(r.get("key"));
    }

    @Override
    public List<Issue> search(String jql, int max) {
        Map<String, Object> body = Store.map("jql", jql, "maxResults", max, "fields", List.of("summary", "status", "issuetype", "assignee", "parent"));
        Object r = cfg.cloud() ? call("POST", "/rest/api/3/search/jql", body) : call("POST", "/rest/api/2/search", body);
        List<Issue> out = new ArrayList<>();
        for (Object o : Json.list(Json.at(r, "issues"))) {
            out.add(new Issue(Json.str(o, "key"), Json.str(o, "fields.summary"), Json.str(o, "fields.status.name"), Json.str(o, "fields.issuetype.name")));
        }
        return out;
    }

    @Override
    public List<Issue> children(String parent) {
        return search("parent = " + parent, 200);
    }

    @Override
    public String login() {
        try {
            Map<String, Object> me = myself();
            return "as " + me.get("displayName") + " (" + accountId(me) + ")";
        } catch (Http.ApiException e) {
            if (e.status != 401 || cfg.projectKeys().isEmpty()) throw e;
            String key = cfg.projectKeys().get(0); // scoped tokens often lack read:me; prove access with the project instead
            Object p = call("GET", "/rest/api/2/project/" + Http.enc(key), null);
            return "project " + key + " (" + Json.str(p, "name") + ") readable; the token has no read:me scope, which the tool does not need";
        }
    }

    @Override
    public void link(String key, String url, String title) {
        call("POST", "/rest/api/2/issue/" + Http.enc(key) + "/remotelink", Store.map("globalId", url, "object", Store.map("url", url, "title", title)));
        Store.log("atlassian", Store.map("action", "jira-link", "key", key, "url", url));
    }
}
