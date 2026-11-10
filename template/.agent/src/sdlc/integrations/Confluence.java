package sdlc.integrations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.util.Http;
import sdlc.util.Json;
import sdlc.workflow.Wiki;

/** The wiki on Confluence Cloud (REST v2 for pages, v1 for search and attachments) and Data Center (REST v1). */
public final class Confluence implements Wiki {
    private final Config cfg;

    public Confluence(Config cfg) {
        this.cfg = cfg;
    }

    private String base() {
        String b = Ctx.secret("CONFLUENCE_BASE_URL", cfg.str("confluence.url"));
        if (b.isBlank()) throw new UsageException("confluence.url is not set in .agent/sdlc.yml");
        return b.replaceAll("/+$", "");
    }

    private String auth() {
        String token = Ctx.secret("CONFLUENCE_TOKEN", Ctx.secret("JIRA_TOKEN", ""));
        if (token.isBlank()) throw new UsageException("CONFLUENCE_TOKEN (or JIRA_TOKEN on Cloud) is not set in .agent/local.env");
        if (cfg.cloud()) return Http.basic(Ctx.secret("CONFLUENCE_USER", Ctx.need("JIRA_USER", "your Atlassian e-mail")), token);
        return "Bearer " + token;
    }

    private Object call(String method, String path, Object body) {
        return Http.json(method, base() + path, auth(), body);
    }

    @Override
    public Page page(String id) {
        if (cfg.cloud()) {
            Object p = call("GET", "/api/v2/pages/" + Http.enc(id) + "?body-format=storage", null);
            String spaceId = Json.str(p, "spaceId");
            String spaceKey = spaceId.isBlank() ? "" : Json.str(call("GET", "/api/v2/spaces/" + Http.enc(spaceId), null), "key");
            return new Page(id, Json.str(p, "title"), spaceKey, (int) Double.parseDouble(Json.str(p, "version.number")), Json.str(p, "body.storage.value"), base() + Json.str(p, "_links.webui"), Json.str(p, "version.authorId"));
        }
        Object p = call("GET", "/rest/api/content/" + Http.enc(id) + "?expand=body.storage,version,space", null);
        String by = Json.str(p, "version.by.username").isBlank() ? Json.str(p, "version.by.accountId") : Json.str(p, "version.by.username");
        return new Page(id, Json.str(p, "title"), Json.str(p, "space.key"), (int) Double.parseDouble(Json.str(p, "version.number")), Json.str(p, "body.storage.value"), base() + Json.str(p, "_links.webui"), by);
    }

    @Override
    public List<Ref> titled(String words, List<String> spaces) {
        String in = spaces.isEmpty() ? "" : " AND space in (" + String.join(",", spaces.stream().map(x -> "\"" + x + "\"").toList()) + ")";
        return search("type = page AND title ~ \"\\\"" + words + "\\\"\"" + in, 50);
    }

    @Override
    public void ping() {
        search("type = space", 1);
    }

    /** CQL search. */
    private List<Ref> search(String cql, int limit) {
        List<Ref> out = new ArrayList<>();
        if (cfg.cloud()) {
            Object r = call("GET", "/rest/api/search" + Http.query(Map.of("cql", cql, "limit", String.valueOf(limit), "expand", "content.space,content.version")), null);
            for (Object o : Json.list(Json.at(r, "results"))) {
                Object c = Json.at(o, "content");
                if (c == null) continue;
                out.add(new Ref(Json.str(c, "id"), Json.str(c, "title"), Json.str(c, "space.key"), Json.str(c, "version.number"), base() + Json.str(c, "_links.webui")));
            }
        } else {
            Object r = call("GET", "/rest/api/content/search" + Http.query(Map.of("cql", cql, "limit", String.valueOf(limit), "expand", "space,version")), null);
            for (Object c : Json.list(Json.at(r, "results"))) {
                out.add(new Ref(Json.str(c, "id"), Json.str(c, "title"), Json.str(c, "space.key"), Json.str(c, "version.number"), base() + Json.str(c, "_links.webui")));
            }
        }
        return out;
    }

    @Override
    public Ref byTitle(String spaceKey, String title) {
        if (cfg.cloud()) {
            List<Object> spaces = Json.list(Json.at(call("GET", "/api/v2/spaces?keys=" + Http.enc(spaceKey), null), "results"));
            if (spaces.isEmpty()) return null;
            List<Object> found = Json.list(Json.at(call("GET", "/api/v2/pages?space-id=" + Http.enc(Json.str(spaces.get(0), "id")) + "&title=" + Http.enc(title), null), "results"));
            if (found.isEmpty()) return null;
            Object p = found.get(0);
            return new Ref(Json.str(p, "id"), Json.str(p, "title"), spaceKey, Json.str(p, "version.number"), base() + Json.str(p, "_links.webui"));
        }
        List<Object> found = Json.list(Json.at(call("GET", "/rest/api/content?spaceKey=" + Http.enc(spaceKey) + "&title=" + Http.enc(title) + "&expand=version", null), "results"));
        if (found.isEmpty()) return null;
        Object p = found.get(0);
        return new Ref(Json.str(p, "id"), Json.str(p, "title"), spaceKey, Json.str(p, "version.number"), base() + Json.str(p, "_links.webui"));
    }

    /** Creates the page, or a new version of the page with the same title in the space. */
    @Override
    public Page publish(String spaceKey, String parentId, String title, String storage) {
        Page result = cfg.cloud() ? publishCloud(spaceKey, parentId, title, storage) : publishDc(spaceKey, parentId, title, storage);
        Store.log("atlassian", Store.map("action", "confluence-publish", "title", title, "id", result.id(), "version", result.version()));
        return result;
    }

    private Page publishCloud(String spaceKey, String parentId, String title, String storage) {
        List<Object> spaces = Json.list(Json.at(call("GET", "/api/v2/spaces?keys=" + Http.enc(spaceKey), null), "results"));
        if (spaces.isEmpty()) throw new UsageException("Confluence space " + spaceKey + " not found or not visible to this account.");
        String spaceId = Json.str(spaces.get(0), "id");
        List<Object> found = Json.list(Json.at(call("GET", "/api/v2/pages?space-id=" + Http.enc(spaceId) + "&title=" + Http.enc(title), null), "results"));
        Map<String, Object> body = Store.map("representation", "storage", "value", storage);
        Object r;
        if (found.isEmpty()) {
            Map<String, Object> b = Store.map("spaceId", spaceId, "status", "current", "title", title, "body", body);
            if (!parentId.isBlank()) b.put("parentId", parentId);
            r = call("POST", "/api/v2/pages", b);
        } else {
            String id = Json.str(found.get(0), "id");
            int version = (int) Double.parseDouble(Json.str(found.get(0), "version.number"));
            r = call("PUT", "/api/v2/pages/" + id, Store.map("id", id, "status", "current", "title", title, "body", body,
                    "version", Store.map("number", version + 1, "message", "updated by sdlc from git")));
        }
        return new Page(Json.str(r, "id"), title, spaceKey, (int) Double.parseDouble(Json.str(r, "version.number")), storage, base() + Json.str(r, "_links.webui"), "");
    }

    private Page publishDc(String spaceKey, String parentId, String title, String storage) {
        List<Object> found = Json.list(Json.at(call("GET", "/rest/api/content?spaceKey=" + Http.enc(spaceKey) + "&title=" + Http.enc(title) + "&expand=version", null), "results"));
        Map<String, Object> b = Store.map("type", "page", "title", title, "space", Store.map("key", spaceKey),
                "body", Store.map("storage", Store.map("value", storage, "representation", "storage")));
        Object r;
        if (found.isEmpty()) {
            if (!parentId.isBlank()) b.put("ancestors", List.of(Store.map("id", parentId)));
            r = call("POST", "/rest/api/content", b);
        } else {
            String id = Json.str(found.get(0), "id");
            b.put("version", Store.map("number", (int) Double.parseDouble(Json.str(found.get(0), "version.number")) + 1));
            r = call("PUT", "/rest/api/content/" + id, b);
        }
        return new Page(Json.str(r, "id"), title, spaceKey, (int) Double.parseDouble(Json.str(r, "version.number")), storage, base() + Json.str(r, "_links.webui"), "");
    }

    /** Adds or replaces an attachment (e.g. the mind map image). */
    @Override
    public void attach(String pageId, String fileName, byte[] data, String mime) {
        String path = "/rest/api/content/" + Http.enc(pageId) + "/child/attachment";
        if (cfg.cloud()) {
            Http.upload("PUT", base() + path, auth(), fileName, data, mime);
        } else {
            List<Object> existing = Json.list(Json.at(call("GET", path + "?filename=" + Http.enc(fileName), null), "results"));
            String url = existing.isEmpty() ? base() + path : base() + path + "/" + Json.str(existing.get(0), "id") + "/data";
            Http.upload("POST", url, auth(), fileName, data, mime);
        }
        Store.log("atlassian", Store.map("action", "confluence-attach", "page", pageId, "file", fileName));
    }
}
