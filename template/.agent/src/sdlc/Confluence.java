package sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Confluence Cloud (REST v2 for pages, v1 for search and attachments) and Data Center (REST v1). */
final class Confluence {
    record Page(String id, String title, String space, int version, String storage, String url) {}

    private Confluence() {}

    static String base() {
        String b = Ctx.secret("CONFLUENCE_BASE_URL", Ctx.cfg.str("confluence.url"));
        if (b.isBlank()) throw new Main.UsageException("confluence.url is not set in .agent/sdlc.yml");
        return b.replaceAll("/+$", "");
    }

    static String auth() {
        String token = Ctx.secret("CONFLUENCE_TOKEN", Ctx.secret("JIRA_TOKEN", ""));
        if (token.isBlank()) throw new Main.UsageException("CONFLUENCE_TOKEN (or JIRA_TOKEN on Cloud) is not set in .agent/local.env");
        if (Ctx.cfg.cloud()) return Http.basic(Ctx.secret("CONFLUENCE_USER", Ctx.need("JIRA_USER", "your Atlassian e-mail")), token);
        return "Bearer " + token;
    }

    static Object call(String method, String path, Object body) {
        return Http.json(method, base() + path, auth(), body);
    }

    static Page page(String id) {
        if (Ctx.cfg.cloud()) {
            Object p = call("GET", "/api/v2/pages/" + Http.enc(id) + "?body-format=storage", null);
            String spaceId = Json.str(p, "spaceId");
            String spaceKey = spaceId.isBlank() ? "" : Json.str(call("GET", "/api/v2/spaces/" + Http.enc(spaceId), null), "key");
            return new Page(id, Json.str(p, "title"), spaceKey, (int) Double.parseDouble(Json.str(p, "version.number")), Json.str(p, "body.storage.value"), base() + Json.str(p, "_links.webui"));
        }
        Object p = call("GET", "/rest/api/content/" + Http.enc(id) + "?expand=body.storage,version,space", null);
        return new Page(id, Json.str(p, "title"), Json.str(p, "space.key"), (int) Double.parseDouble(Json.str(p, "version.number")), Json.str(p, "body.storage.value"), base() + Json.str(p, "_links.webui"));
    }

    /** CQL search; returns id, title, space, version, url per page. */
    static List<Map<String, String>> search(String cql, int limit) {
        List<Map<String, String>> out = new ArrayList<>();
        if (Ctx.cfg.cloud()) {
            Object r = call("GET", "/rest/api/search" + Http.query(Map.of("cql", cql, "limit", String.valueOf(limit), "expand", "content.space,content.version")), null);
            for (Object o : Json.list(Json.at(r, "results"))) {
                Object c = Json.at(o, "content");
                if (c == null) continue;
                out.add(row(Json.str(c, "id"), Json.str(c, "title"), Json.str(c, "space.key"), Json.str(c, "version.number"), base() + Json.str(c, "_links.webui")));
            }
        } else {
            Object r = call("GET", "/rest/api/content/search" + Http.query(Map.of("cql", cql, "limit", String.valueOf(limit), "expand", "space,version")), null);
            for (Object c : Json.list(Json.at(r, "results"))) {
                out.add(row(Json.str(c, "id"), Json.str(c, "title"), Json.str(c, "space.key"), Json.str(c, "version.number"), base() + Json.str(c, "_links.webui")));
            }
        }
        return out;
    }

    private static Map<String, String> row(String id, String title, String space, String version, String url) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("space", space);
        m.put("version", version);
        m.put("url", url);
        return m;
    }

    /** The page with exactly this title in the space (id, title, space, version, url), or null. Not search: no index delay. */
    static Map<String, String> byTitle(String spaceKey, String title) {
        if (Ctx.cfg.cloud()) {
            List<Object> spaces = Json.list(Json.at(call("GET", "/api/v2/spaces?keys=" + Http.enc(spaceKey), null), "results"));
            if (spaces.isEmpty()) return null;
            List<Object> found = Json.list(Json.at(call("GET", "/api/v2/pages?space-id=" + Http.enc(Json.str(spaces.get(0), "id")) + "&title=" + Http.enc(title), null), "results"));
            if (found.isEmpty()) return null;
            Object p = found.get(0);
            return row(Json.str(p, "id"), Json.str(p, "title"), spaceKey, Json.str(p, "version.number"), base() + Json.str(p, "_links.webui"));
        }
        List<Object> found = Json.list(Json.at(call("GET", "/rest/api/content?spaceKey=" + Http.enc(spaceKey) + "&title=" + Http.enc(title) + "&expand=version", null), "results"));
        if (found.isEmpty()) return null;
        Object p = found.get(0);
        return row(Json.str(p, "id"), Json.str(p, "title"), spaceKey, Json.str(p, "version.number"), base() + Json.str(p, "_links.webui"));
    }

    /** Creates the page, or a new version of the page with the same title in the space. */
    static Page publish(String spaceKey, String parentId, String title, String storage) {
        Page result = Ctx.cfg.cloud() ? publishCloud(spaceKey, parentId, title, storage) : publishDc(spaceKey, parentId, title, storage);
        Store.log("atlassian", Store.map("action", "confluence-publish", "title", title, "id", result.id(), "version", result.version()));
        return result;
    }

    private static Page publishCloud(String spaceKey, String parentId, String title, String storage) {
        List<Object> spaces = Json.list(Json.at(call("GET", "/api/v2/spaces?keys=" + Http.enc(spaceKey), null), "results"));
        if (spaces.isEmpty()) throw new Main.UsageException("Confluence space " + spaceKey + " not found or not visible to this account.");
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
        return new Page(Json.str(r, "id"), title, spaceKey, (int) Double.parseDouble(Json.str(r, "version.number")), storage, base() + Json.str(r, "_links.webui"));
    }

    private static Page publishDc(String spaceKey, String parentId, String title, String storage) {
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
        return new Page(Json.str(r, "id"), title, spaceKey, (int) Double.parseDouble(Json.str(r, "version.number")), storage, base() + Json.str(r, "_links.webui"));
    }

    /** Adds or replaces an attachment (e.g. the mind map image). */
    static void attach(String pageId, String fileName, byte[] data, String mime) {
        String path = "/rest/api/content/" + Http.enc(pageId) + "/child/attachment";
        if (Ctx.cfg.cloud()) {
            Http.upload("PUT", base() + path, auth(), fileName, data, mime);
        } else {
            List<Object> existing = Json.list(Json.at(call("GET", path + "?filename=" + Http.enc(fileName), null), "results"));
            String url = existing.isEmpty() ? base() + path : base() + path + "/" + Json.str(existing.get(0), "id") + "/data";
            Http.upload("POST", url, auth(), fileName, data, mime);
        }
        Store.log("atlassian", Store.map("action", "confluence-attach", "page", pageId, "file", fileName));
    }
}
