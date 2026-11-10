package sdlc.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sdlc.util.Glob;
import sdlc.util.Json;
import sdlc.util.Yaml;

/** The merged configuration: built-in defaults, the stack preset, then .agent/sdlc.yml. */
public final class Config {
    final Map<String, Object> root;

    private Config(Map<String, Object> root) {
        this.root = root;
    }

    /**
     * .agent/sdlc.yml over .agent/org.yml (the organisation's rules, shipped with the kit by
     * `sdlc upgrade`) over the stack preset over the built-in defaults.
     */
    static Config load(Path file) throws IOException {
        Map<String, Object> user = Files.exists(file) ? asMap(Yaml.parse(Files.readString(file)), "sdlc.yml") : new LinkedHashMap<>();
        Path orgFile = file.resolveSibling("org.yml");
        Map<String, Object> org = Files.exists(orgFile) ? asMap(Yaml.parse(Files.readString(orgFile)), "org.yml") : new LinkedHashMap<>();
        return fromLayers(org, user);
    }

    static Config fromUser(Map<String, Object> user) {
        return fromLayers(new LinkedHashMap<>(), user);
    }

    static Config fromLayers(Map<String, Object> org, Map<String, Object> user) {
        Map<String, Object> merged = asMap(Yaml.parse(Defaults.COMMON), "defaults");
        Object st = user.get("stack") != null ? user.get("stack") : org.get("stack");
        String stack = st instanceof String s && !s.isBlank() ? s : "java-maven";
        merge(merged, asMap(Yaml.parse(Defaults.preset(stack)), "preset"));
        merge(merged, org);
        merge(merged, user);
        return new Config(merged);
    }

    /** Test hook: the merge of an organisation layer and a repository layer. */
    public static Config parseLayers(String orgYaml, String repoYaml) {
        return fromLayers(asMap(Yaml.parse(orgYaml), "org.yml"), asMap(Yaml.parse(repoYaml), "sdlc.yml"));
    }

    public static Config parse(String yaml) {
        return fromUser(asMap(Yaml.parse(yaml), "sdlc.yml"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String what) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (o == null) return new LinkedHashMap<>();
        throw new IllegalArgumentException(what + " must be a mapping (key: value) at the top level");
    }

    /** Deep merge: maps merge, everything else replaces; "key+" appends to a list. */
    @SuppressWarnings("unchecked")
    static void merge(Map<String, Object> base, Map<String, Object> over) {
        for (Map.Entry<String, Object> e : over.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k.endsWith("+")) {
                String key = k.substring(0, k.length() - 1);
                List<Object> list = new ArrayList<>(base.get(key) instanceof List<?> l ? (List<Object>) l : List.of());
                if (v instanceof List<?> add) list.addAll(add);
                else if (v != null) list.add(v);
                base.put(key, list);
            } else if (v instanceof Map<?, ?> vm && base.get(k) instanceof Map<?, ?> bm) {
                merge((Map<String, Object>) bm, (Map<String, Object>) vm);
            } else {
                base.put(k, v);
            }
        }
    }

    Object get(String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part);
        }
        return cur;
    }

    public String str(String path) {
        return str(path, "");
    }

    public String str(String path, String fallback) {
        Object v = get(path);
        return v == null || v.toString().isBlank() ? fallback : v.toString().trim();
    }

    public boolean bool(String path, boolean fallback) {
        Object v = get(path);
        if (v == null) return fallback;
        String s = v.toString().trim().toLowerCase();
        return s.equals("true") || s.equals("yes") || s.equals("on");
    }

    public int num(String path, int fallback) {
        try {
            return Integer.parseInt(str(path, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public List<String> list(String path) {
        Object v = get(path);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
        } else if (v != null && !v.toString().isBlank()) {
            for (String s : v.toString().split(",")) if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> maps(String path) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (get(path) instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String path) {
        return get(path) instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    // ---- frequently used values ----

    public String stack() { return str("stack", "java-maven"); }
    public boolean cloud() { return !str("jira.deployment", "cloud").toLowerCase().matches("datacenter|data-center|server|dc"); }
    public String baseBranch() { return str("delivery.baseBranch", "develop"); }
    public List<String> projectKeys() { return list("jira.projectKeys"); }

    public String verifyCommand() {
        String cmd = Ctx.WINDOWS ? str("build.verifyWindows", str("build.verify")) : str("build.verify");
        return cmd.equals(Defaults.NODE_VERIFY) && Ctx.root != null ? nodeVerify(Ctx.root, cmd) : cmd;
    }

    /**
     * The Node default uses npm. When build.verify was left alone and the repository has a pnpm or
     * yarn lock file, the same steps run with that package manager (yarn has no --if-present, so
     * lint and build run only when package.json has such a script).
     */
    static String nodeVerify(Path root, String npmDefault) {
        if (Files.exists(root.resolve("pnpm-lock.yaml"))) return "pnpm run --if-present lint && pnpm test && pnpm run --if-present build";
        if (!Files.exists(root.resolve("yarn.lock"))) return npmDefault;
        Map<String, Object> scripts = new LinkedHashMap<>();
        try {
            if (Json.at(Json.parse(Files.readString(root.resolve("package.json"))), "scripts") instanceof Map<?, ?> m) scripts = Json.obj(m);
        } catch (IOException | RuntimeException e) {
            // no readable package.json: run the tests only
        }
        return (scripts.containsKey("lint") ? "yarn run lint && " : "") + "yarn test" + (scripts.containsKey("build") ? " && yarn run build" : "");
    }

    public java.util.regex.Pattern keyPattern() {
        List<String> keys = projectKeys();
        String prefix = keys.isEmpty() ? "[A-Z][A-Z0-9]+" : "(?:" + String.join("|", keys) + ")";
        return java.util.regex.Pattern.compile("\\b" + prefix + "-[0-9]+\\b");
    }

    public boolean isProtectedBranch(String branch) {
        return !branch.isEmpty() && Glob.any(list("git.protectedBranches"), branch);
    }

    /** Problems a person should fix in sdlc.yml; empty when fine. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (projectKeys().isEmpty()) p.add("jira.projectKeys is empty (e.g. [PAY])");
        if (str("jira.url").isBlank()) p.add("jira.url is empty");
        if (str("confluence.url").isBlank()) p.add("confluence.url is empty");
        if (list("confluence.searchSpaces").isEmpty()) p.add("confluence.searchSpaces is empty: where one pagers, HLDs and LLDs live");
        for (String doc : List.of("testDesign", "lld")) {
            if (str("confluence.publish." + doc + ".space").isBlank()) p.add("confluence.publish." + doc + ".space is empty");
        }
        for (String gate : List.of("testDesign", "lld", "solution")) {
            String g = str("gates." + gate);
            if (!g.matches("required|optional|off")) p.add("gates." + gate + " must be required, optional or off");
        }
        int min = num("loops.review.min", 2), max = num("loops.review.max", 5);
        if (min < 1 || max < min) p.add("loops.review: need 1 <= min <= max");
        if (!str("delivery.qa").matches("auto|always|never")) p.add("delivery.qa must be auto, always or never");
        for (String re : List.of("git.branchPattern", "git.commitPattern")) {
            try {
                java.util.regex.Pattern.compile(str(re));
            } catch (RuntimeException e) {
                p.add(re + " is not a valid regular expression");
            }
        }
        return p;
    }
}
