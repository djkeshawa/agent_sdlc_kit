package sdlc.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import sdlc.core.Work;
import sdlc.util.Json;
import sdlc.util.Yaml;

/**
 * Reads OpenAPI (3.x, and 2.x paths) and AsyncAPI (2.x and 3.x) files far enough to compare two
 * versions: which operations or messages were added or removed, and the breaking changes a
 * consumer would feel. Deliberately limited; what it detects is listed in docs/08:
 *   removed operation or message, removed response code, a new required request field or
 *   parameter, a removed enum value, a changed property type, a removed response or payload property.
 * Not detected: changes deeper than one level of properties, oneOf/allOf/anyOf, format and
 * pattern changes, security changes, servers. Teams that need more add oasdiff to their CI.
 */
public final class Contracts {
    private Contracts() {}

    /** What a consumer relies on in one operation or message. */
    public record Op(Set<String> responses, Set<String> requiredRequest, Map<String, String> requestTypes, Map<String, Set<String>> requestEnums,
                     Map<String, String> responseTypes, Map<String, Set<String>> responseEnums) {}

    /** A parsed spec: operation key ("POST /orders/{id}/refunds", "message RefundRequested on refund-events") -> details. */
    public record Spec(String kind, Map<String, Op> operations) {}

    public record Change(String operation, String kind, String detail) {
        public boolean breaking() {
            return kind.equals("breaking") || kind.equals("removed");
        }
    }

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options");

    /** The spec of an API from its local file (this repository's file, or the synced copy), or null. */
    static Spec read(Catalog.Api a) {
        if (a.localCopy() == null || !Files.isRegularFile(a.localCopy())) return null;
        try {
            return parse(Files.readString(a.localCopy()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static Spec parse(String text) {
        if (text == null || text.isBlank()) return new Spec("empty", new TreeMap<>());
        Object root = text.stripLeading().startsWith("{") ? Json.parse(text) : Yaml.parse(text);
        Map<String, Object> r = Json.map(root);
        if (r.containsKey("asyncapi")) return new Spec("asyncapi", async(r));
        return new Spec("openapi", openapi(r));
    }

    // ---------------- OpenAPI ----------------

    private static Map<String, Op> openapi(Map<String, Object> r) {
        Map<String, Op> ops = new TreeMap<>();
        for (Map.Entry<String, Object> p : Json.map(r.get("paths")).entrySet()) {
            Map<String, Object> item = Json.map(p.getValue());
            List<Object> shared = Json.list(item.get("parameters"));
            for (String m : METHODS) {
                if (!(item.get(m) instanceof Map<?, ?>)) continue;
                Map<String, Object> op = Json.map(item.get(m));
                Set<String> required = new TreeSet<>();
                Map<String, String> reqTypes = new TreeMap<>();
                Map<String, Set<String>> reqEnums = new TreeMap<>();
                List<Object> params = new ArrayList<>(shared);
                params.addAll(Json.list(op.get("parameters")));
                for (Object po : params) {
                    Map<String, Object> pm = Json.map(resolve(r, po));
                    String name = Work.str(pm.get("in")) + ":" + Work.str(pm.get("name"));
                    if (truthy(pm.get("required"))) required.add(name);
                    Map<String, Object> schema = Json.map(resolve(r, pm.get("schema")));
                    if (!schema.isEmpty()) {
                        reqTypes.put(name, type(r, schema));
                        Set<String> en = enums(r, schema);
                        if (!en.isEmpty()) reqEnums.put(name, en);
                    }
                }
                for (Object media : Json.map(Json.at(resolve(r, op.get("requestBody")), "content")).values()) {
                    Map<String, Object> schema = Json.map(resolve(r, Json.map(media).get("schema")));
                    for (String req : Work.strings(schema.get("required"))) required.add("body:" + req);
                    props(r, schema, "body:", reqTypes, reqEnums);
                }
                Set<String> responses = new TreeSet<>();
                Map<String, String> respTypes = new TreeMap<>();
                Map<String, Set<String>> respEnums = new TreeMap<>();
                for (Map.Entry<String, Object> resp : Json.map(op.get("responses")).entrySet()) {
                    responses.add(resp.getKey());
                    if (!resp.getKey().startsWith("2")) continue;
                    for (Object media : Json.map(Json.at(resolve(r, resp.getValue()), "content")).values()) {
                        props(r, Json.map(resolve(r, Json.map(media).get("schema"))), resp.getKey() + ":", respTypes, respEnums);
                    }
                }
                ops.put(m.toUpperCase(Locale.ROOT) + " " + p.getKey(), new Op(responses, required, reqTypes, reqEnums, respTypes, respEnums));
            }
        }
        return ops;
    }

    // ---------------- AsyncAPI ----------------

    private static Map<String, Op> async(Map<String, Object> r) {
        Map<String, Op> ops = new TreeMap<>();
        for (Map.Entry<String, Object> ch : Json.map(r.get("channels")).entrySet()) {
            Map<String, Object> c = Json.map(ch.getValue());
            String channel = Work.str(c.getOrDefault("address", ch.getKey()));
            Map<String, Object> messages = new LinkedHashMap<>();
            for (String dir : List.of("publish", "subscribe")) { // 2.x
                Object msg = Json.at(c, dir + ".message");
                if (msg == null) continue;
                List<Object> list = Json.map(msg).containsKey("oneOf") ? Json.list(Json.map(msg).get("oneOf")) : List.of(msg);
                for (Object m : list) messages.put(messageName(r, m), resolve(r, m));
            }
            for (Map.Entry<String, Object> m : Json.map(c.get("messages")).entrySet()) messages.put(messageName(r, m.getValue(), m.getKey()), resolve(r, m.getValue())); // 3.x
            for (Map.Entry<String, Object> m : messages.entrySet()) {
                Map<String, Object> payload = Json.map(resolve(r, Json.map(m.getValue()).get("payload")));
                Map<String, String> types = new TreeMap<>();
                Map<String, Set<String>> en = new TreeMap<>();
                props(r, payload, "payload:", types, en);
                ops.put("message " + m.getKey() + " on " + channel, new Op(Set.of(), new TreeSet<>(Work.strings(payload.get("required"))), Map.of(), Map.of(), types, en));
            }
        }
        return ops;
    }

    private static String messageName(Map<String, Object> r, Object m) {
        return messageName(r, m, "");
    }

    private static String messageName(Map<String, Object> r, Object m, String fallback) {
        Map<String, Object> mm = Json.map(m);
        String ref = Work.str(mm.get("$ref"));
        Map<String, Object> resolved = Json.map(resolve(r, m));
        for (String k : List.of("name", "messageId", "title")) if (!Work.str(resolved.get(k)).isBlank()) return Work.str(resolved.get(k));
        if (!ref.isBlank()) return ref.substring(ref.lastIndexOf('/') + 1);
        return fallback.isBlank() ? "message" : fallback;
    }

    // ---------------- schema helpers ----------------

    /** Follows a local $ref (#/components/...) up to 10 levels; anything else is returned as it is. */
    static Object resolve(Map<String, Object> root, Object o) {
        Object cur = o;
        for (int i = 0; i < 10 && cur instanceof Map<?, ?> m && m.get("$ref") instanceof String ref && ref.startsWith("#/"); i++) {
            Object next = root;
            for (String part : ref.substring(2).split("/")) next = Json.map(next).get(part.replace("~1", "/").replace("~0", "~"));
            if (next == null) return cur;
            cur = next;
        }
        return cur;
    }

    private static void props(Map<String, Object> root, Map<String, Object> schema, String prefix, Map<String, String> types, Map<String, Set<String>> enums) {
        Map<String, Object> s = schema;
        if (s.get("items") != null && "array".equals(Work.str(s.get("type")))) s = Json.map(resolve(root, s.get("items")));
        for (Map.Entry<String, Object> p : Json.map(s.get("properties")).entrySet()) {
            Map<String, Object> ps = Json.map(resolve(root, p.getValue()));
            types.put(prefix + p.getKey(), type(root, ps));
            Set<String> en = enums(root, ps);
            if (!en.isEmpty()) enums.put(prefix + p.getKey(), en);
        }
    }

    private static String type(Map<String, Object> root, Map<String, Object> s) {
        String t = Work.str(s.get("type"));
        if (t.equals("array")) return "array<" + type(root, Json.map(resolve(root, s.get("items")))) + ">";
        return t.isBlank() ? (s.containsKey("properties") ? "object" : "any") : t;
    }

    private static Set<String> enums(Map<String, Object> root, Map<String, Object> s) {
        return new TreeSet<>(Work.strings(s.get("enum")));
    }

    private static boolean truthy(Object o) {
        return o != null && o.toString().strip().equalsIgnoreCase("true");
    }

    // ---------------- diff ----------------

    /** What changed from the old spec to the new one. */
    public static List<Change> diff(Spec before, Spec after) {
        List<Change> out = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>(before.operations().keySet());
        all.addAll(after.operations().keySet());
        for (String key : all) {
            Op a = before.operations().get(key), b = after.operations().get(key);
            if (a == null) {
                out.add(new Change(key, "added", "new"));
                continue;
            }
            if (b == null) {
                out.add(new Change(key, "removed", "removed"));
                continue;
            }
            for (String code : a.responses()) if (!b.responses().contains(code) && !code.equals("default")) out.add(new Change(key, "breaking", "response " + code + " removed"));
            for (String req : b.requiredRequest()) if (!a.requiredRequest().contains(req)) out.add(new Change(key, "breaking", "new required " + req));
            types(key, a.requestTypes(), b.requestTypes(), false, out);
            types(key, a.responseTypes(), b.responseTypes(), true, out);
            enumsRemoved(key, a.requestEnums(), b.requestEnums(), out);
            enumsRemoved(key, a.responseEnums(), b.responseEnums(), out);
        }
        return out;
    }

    private static void types(String key, Map<String, String> a, Map<String, String> b, boolean response, List<Change> out) {
        for (Map.Entry<String, String> e : a.entrySet()) {
            String now = b.get(e.getKey());
            if (now == null) {
                if (response) out.add(new Change(key, "breaking", e.getKey() + " removed"));
            } else if (!now.equals(e.getValue()) && !e.getValue().equals("any")) {
                out.add(new Change(key, "breaking", e.getKey() + " type " + e.getValue() + " -> " + now));
            }
        }
    }

    private static void enumsRemoved(String key, Map<String, Set<String>> a, Map<String, Set<String>> b, List<Change> out) {
        for (Map.Entry<String, Set<String>> e : a.entrySet()) {
            Set<String> now = b.get(e.getKey());
            if (now == null) continue;
            for (String v : e.getValue()) if (!now.contains(v)) out.add(new Change(key, "breaking", e.getKey() + " enum value " + v + " removed"));
        }
    }

    /** "post /orders/{orderId}/refunds" and "POST /orders/{id}/refunds" name the same operation; messages compare by name and channel. */
    public static String normalize(String op) {
        String s = op.strip().replaceAll("\\s+", " ");
        int sp = s.indexOf(' ');
        if (sp > 0 && METHODS.contains(s.substring(0, sp).toLowerCase(Locale.ROOT))) {
            return s.substring(0, sp).toUpperCase(Locale.ROOT) + " " + s.substring(sp + 1).replaceAll("\\{[^}/]*\\}", "{}").replaceAll("/+$", "");
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** A path a client calls (with or without {params}) matches a spec path template. */
    public static boolean pathMatches(String template, String called) {
        String[] t = template.replaceAll("/+$", "").split("/"), c = called.replaceAll("[?#].*$", "").replaceAll("/+$", "").split("/");
        if (t.length != c.length) return false;
        for (int i = 0; i < t.length; i++) {
            boolean param = t[i].startsWith("{") || c[i].startsWith("{") || c[i].startsWith("$") || c[i].startsWith(":");
            if (!param && !t[i].equals(c[i])) return false;
        }
        return true;
    }

    // ---------------- PR policy ----------------

    public record Policy(List<String> passes, List<String> fails, List<String> warns) {}

    /**
     * For check-pr and the PR gate: every changed contract file of this repository, compared with
     * the point where the branch left the base branch. With lanes.enabled, each added or removed operation must be allowed by the
     * story's docs/agent/contracts/<STORY>.json (written by tasks create from its lane and slice),
     * and a breaking change needs compatibility: breaking in the solution. Without lanes, breaking
     * changes are warnings.
     */
    public static Policy prPolicy(String story, String base, List<String> changed) {
        List<String> passes = new ArrayList<>(), fails = new ArrayList<>(), warns = new ArrayList<>();
        String from = mergeBase(base);
        // the contract files are those of the base branch's catalog and of this branch's: a pull request
        // that drops an API from its own catalog-info.yaml still has that API's file checked
        Map<String, String> fileToApi = new LinkedHashMap<>();
        String catalogFile = sdlc.core.Ctx.root.relativize(Catalog.file()).toString().replace('\\', '/');
        apiFiles(Catalog.parse(show(from, catalogFile)), fileToApi);
        apiFiles(Catalog.load(), fileToApi);
        if (fileToApi.isEmpty()) return new Policy(passes, fails, warns);
        List<String> touched = changed.stream().filter(fileToApi::containsKey).toList();
        // --no-renames: `git mv api/openapi.yaml x.yaml` is the contract's removal, not a rename
        List<String> deleted = sdlc.core.Git.lines(sdlc.util.Proc.run(sdlc.core.Ctx.root, "git", "diff", "--name-only", "--no-renames", "--diff-filter=D", base + "...HEAD").out());
        List<String> all = new ArrayList<>(touched);
        for (String d : deleted) if (fileToApi.containsKey(d) && !all.contains(d)) all.add(d);
        if (all.isEmpty()) return new Policy(passes, fails, warns);
        String allowanceRel = "docs/agent/contracts/" + story + ".json";
        if (!story.isBlank() && changed.contains(allowanceRel)) warns.add("contract allowance " + allowanceRel + " is added or changed in this pull request (architects review it, CODEOWNERS)");
        boolean lanes = Lanes.enabled();
        Map<String, Object> allowance = story.isBlank() ? Map.of() : sdlc.core.Store.read(Lanes.allowanceFile(story));
        for (String file : all) {
            String api = fileToApi.get(file);
            List<Change> changes;
            try {
                changes = diff(parse(show(from, file)), parse(show("HEAD", file)));
            } catch (RuntimeException e) {
                fails.add("Contract " + file + " could not be read: " + e.getMessage());
                continue;
            }
            if (changes.isEmpty()) {
                passes.add("Contract " + file + " changed without an operation or breaking change");
                continue;
            }
            List<String> problems = new ArrayList<>();
            if (!lanes) {
                for (Change ch : changes) if (ch.breaking()) warns.add("contract " + api + ": breaking change " + ch.operation() + " (" + ch.detail() + ")");
                passes.add("Contract " + file + ": " + changes.size() + " change(s) (lanes off: not checked against a lane)");
                continue;
            }
            if (allowance.isEmpty()) {
                fails.add("Contract " + file + " changed, but " + (story.isBlank() ? "no story is known" : story + " has no " + Lanes.allowanceFile(story).getFileName()
                        + " (java .agent/Sdlc.java tasks create " + story + " writes it; commit it)"));
                continue;
            }
            String lane = Json.str(allowance, "lane");
            Map<String, Object> entry = Json.map(Json.map(allowance.get("apis")).get(api));
            if (entry.isEmpty()) {
                fails.add("Contract " + api + " (" + file + ") changed, but the " + lane + " lane of " + story + " allows no change to it" + (lane.equals("patch") ? " (raise the LLD's lane, get it approved, tasks create again)" : ""));
                continue;
            }
            Set<String> add = new LinkedHashSet<>(Work.strings(entry.get("add")));
            Set<String> remove = new LinkedHashSet<>(Work.strings(entry.get("remove")));
            boolean breaking = "true".equals(Work.str(entry.get("breaking")));
            for (Change ch : changes) {
                String op = normalize(ch.operation());
                if (ch.kind().equals("added") && !add.contains("*") && !add.contains(op)) problems.add(ch.operation() + " added, but the solution slice does not list it");
                else if (ch.kind().equals("removed") && !remove.contains(op)) problems.add(ch.operation() + " removed, but the solution slice does not list the removal (breaking)");
                else if (ch.kind().equals("breaking") && !breaking) problems.add(ch.operation() + ": " + ch.detail() + " is a breaking change; the " + lane + " lane allows additive changes only");
            }
            if (problems.isEmpty()) passes.add("Contract " + api + ": " + changes.size() + " change(s), all allowed by the " + lane + " lane of " + story);
            else fails.add("Contract " + api + " (" + file + "): " + String.join("; ", problems) + ". Change the code, or ask the architect for a solution change.");
        }
        return new Policy(passes, fails, warns);
    }

    private static void apiFiles(Catalog c, Map<String, String> fileToApi) {
        Catalog.Component self = c.self();
        if (self == null) return;
        for (String api : self.providesApis()) {
            Catalog.Api a = c.apis.get(api);
            if (a != null && !a.definition().isBlank()) fileToApi.putIfAbsent(a.definition(), api);
        }
    }

    /**
     * Where this branch left the base branch. The old contract is read there, as `git diff base...HEAD` does,
     * so an operation the base branch gained later is not seen as removed by this branch. Without a common
     * ancestor (a shallow clone), the base itself.
     */
    public static String mergeBase(String base) {
        sdlc.util.Proc r = sdlc.util.Proc.run(sdlc.core.Ctx.root, "git", "merge-base", base, "HEAD");
        return r.code() == 0 && !r.out().isBlank() ? r.out().strip() : base;
    }

    private static String show(String ref, String file) {
        sdlc.util.Proc r = sdlc.util.Proc.run(sdlc.core.Ctx.root, "git", "show", ref + ":" + file);
        return r.code() == 0 ? r.out() : "";
    }
}
