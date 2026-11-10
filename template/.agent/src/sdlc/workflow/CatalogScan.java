package sdlc.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.util.Glob;
import sdlc.util.Json;
import sdlc.util.Yaml;

/**
 * Static scan of this repository for the dependencies its code really has: the HTTP calls it
 * makes (Feign clients, WebClient / RestClient / RestTemplate, axios and fetch), the topics and
 * queues it consumes and produces (Kafka, JMS, Rabbit, kafkajs), and the services its
 * configuration points at. Regular expressions, not a compiler: it finds the usual spellings and
 * says where it found each one, so a human can check. Placeholders like ${clients.orders.topic}
 * are resolved from application*.yml / *.properties.
 */
public final class CatalogScan {
    private CatalogScan() {}

    /** kind: provides | calls | consumes | produces | config. target: "GET /orders/{}", "topic order-events", "host orders-service". */
    public record Edge(String component, String kind, String target, String file, int line) {}

    private static final Set<String> SKIP = Set.of(".git", "node_modules", "target", "build", "dist", ".sdlc", ".agent", ".gradle", ".idea", "coverage", ".next", "out");
    private static final Set<String> CODE = Set.of("java", "kt", "groovy", "scala", "js", "jsx", "ts", "tsx", "mjs", "cjs");

    private static final Pattern FEIGN = Pattern.compile("@FeignClient\\s*\\(([^)]*)\\)");
    private static final Pattern ATTR = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(([^)]*)\\)");
    private static final Pattern FIRST_STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern REQ_METHOD = Pattern.compile("RequestMethod\\.(GET|POST|PUT|DELETE|PATCH)");
    private static final Pattern URI_CALL = Pattern.compile("\\.(uri|getForObject|getForEntity|postForObject|postForEntity|put|delete|exchange|patchForObject)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern HTTP_VERB = Pattern.compile("\\.(get|post|put|delete|patch)\\s*\\(\\s*\\)");
    private static final Pattern KAFKA_LISTENER = Pattern.compile("@KafkaListener\\s*\\(([^)]*)\\)");
    private static final Pattern TOPICS_START = Pattern.compile("\\btopics\\s*=");
    private static final Pattern STRINGS = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern KAFKA_SEND = Pattern.compile("(?i)(kafkaTemplate|template|producer)\\w*\\s*\\.\\s*send\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern JMS_LISTENER = Pattern.compile("@JmsListener\\s*\\([^)]*destination\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern JMS_SEND = Pattern.compile("\\.convertAndSend\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern RABBIT_LISTENER = Pattern.compile("@RabbitListener\\s*\\([^)]*queues\\s*=\\s*\\{?\\s*\"([^\"]+)\"");
    private static final Pattern JS_SUBSCRIBE = Pattern.compile("subscribe\\s*\\(\\s*\\{[^}]*?topics?\\s*:\\s*\\[?\\s*['\"`]([^'\"`]+)['\"`]");
    private static final Pattern JS_SEND = Pattern.compile("\\.send\\s*\\(\\s*\\{[^}]*?topic\\s*:\\s*['\"`]([^'\"`]+)['\"`]");
    private static final Pattern JS_HTTP = Pattern.compile("\\b(axios|http|client|api)\\s*\\.\\s*(get|post|put|delete|patch)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]");
    private static final Pattern JS_FETCH = Pattern.compile("\\bfetch\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]\\s*(,\\s*\\{[^}]*method\\s*:\\s*['\"`](\\w+)['\"`])?");
    private static final Pattern URL_VALUE = Pattern.compile("https?://([A-Za-z0-9._-]+)");

    /** sdlc catalog scan [--write]: prints the edges, warns about undeclared ones, saves them for the graph. */
    public static int run(boolean write) throws IOException {
        Catalog c = Catalog.load();
        String self = Catalog.selfName();
        if (self.isBlank()) throw new UsageException("no component name: add catalog-info.yaml (or project.name in .agent/sdlc.yml)");
        List<Edge> edges = scan(Ctx.root, self, c);
        Map<String, Object> doc = Store.map("component", self, "edges", edges.stream().map(e -> Store.map("kind", e.kind(), "target", e.target(), "file", e.file(), "line", e.line())).toList());
        Store.write(Catalog.dir().resolve("edges").resolve(self + ".json"), doc);
        if (write) Store.write(Ctx.root.resolve(Catalog.EDGES_FILE), doc);
        for (Edge e : edges) {
            String by = e.kind().equals("provides") ? self : provider(c, e.target());
            System.out.printf("%-9s %-50s %-18s %s:%d%n", e.kind(), e.target(), by == null ? "?" : by, e.file(), e.line());
        }
        List<String> undeclared = undeclared(c, edges);
        undeclared.forEach(u -> System.out.println("WARN  " + u));
        System.out.println(edges.size() + " edge(s) for " + self + (write ? "; saved to " + Catalog.EDGES_FILE + " (commit it, so other repositories' catalog sync sees them)" : ""));
        return 0;
    }

    /** Uses of other components that catalog-info.yaml does not declare (consumesApis or dependsOn). */
    public static List<String> undeclared(Catalog c, List<Edge> edges) {
        List<String> out = new ArrayList<>();
        Catalog.Component self = c.self();
        if (self == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (Edge e : edges) {
            if (e.kind().equals("provides") || e.kind().equals("produces")) continue;
            String by = provider(c, e.target());
            if (by == null || by.equals(self.name()) || !seen.add(by)) continue;
            Catalog.Component p = c.components.get(by);
            boolean declared = self.dependsOn().contains(by) || p != null && p.providesApis().stream().anyMatch(self.consumesApis()::contains);
            if (!declared) out.add(self.name() + " uses " + by + " (" + e.target() + " at " + e.file() + ":" + e.line() + ") but catalog-info.yaml declares neither consumesApis of it nor dependsOn: " + by);
        }
        return out;
    }

    /** The component that provides a target, or null. */
    public static String provider(Catalog c, String target) {
        if (target.startsWith("host ")) {
            String host = target.substring(5).toLowerCase(Locale.ROOT);
            for (String name : c.components.keySet()) if (host.contains(name)) return name;
            return null;
        }
        int sp = target.indexOf(' ');
        if (sp < 0) return null;
        String kind = target.substring(0, sp), rest = target.substring(sp + 1);
        for (Catalog.Api a : c.apis.values()) {
            Contracts.Spec spec = Contracts.read(a);
            if (spec == null) continue;
            for (String op : spec.operations().keySet()) {
                if (kind.equals("topic") || kind.equals("queue")) {
                    if (op.startsWith("message ") && op.endsWith(" on " + rest)) return a.component().isBlank() ? null : a.component();
                } else {
                    int s = op.indexOf(' ');
                    if (s < 0 || op.startsWith("message ")) continue;
                    boolean method = kind.equals("*") || op.substring(0, s).equalsIgnoreCase(kind);
                    if (method && Contracts.pathMatches(op.substring(s + 1), rest)) return a.component().isBlank() ? null : a.component();
                }
            }
        }
        return null;
    }

    /** Every saved scan: this repository's and the synced ones. */
    static List<Edge> allEdges() {
        List<Edge> out = new ArrayList<>();
        Path dir = Catalog.dir().resolve("edges");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                Map<String, Object> doc = Store.read(f);
                String comp = Work.str(doc.get("component"));
                for (Object o : Json.list(doc.get("edges"))) {
                    Map<String, Object> e = Json.map(o);
                    out.add(new Edge(comp, Work.str(e.get("kind")), Work.str(e.get("target")), Work.str(e.get("file")), (int) Double.parseDouble(Work.str(e.getOrDefault("line", "0")))));
                }
            }
        } catch (IOException | RuntimeException e) {
            // a damaged scan file counts as no scan
        }
        return out;
    }

    // ---------------- the scan ----------------

    public static List<Edge> scan(Path root, String self, Catalog c) throws IOException {
        List<Edge> out = new ArrayList<>();
        Catalog.Component me = c.self();
        if (me != null) {
            for (String api : me.providesApis()) {
                Catalog.Api a = c.apis.get(api);
                Contracts.Spec spec = a == null ? null : Contracts.read(a);
                if (spec != null) for (String op : spec.operations().keySet()) out.add(new Edge(self, "provides", op, a.definition(), 0));
            }
        }
        Map<String, String> props = properties(root);
        List<String> tests = Ctx.cfg.list("paths.test");
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(Files::isRegularFile).filter(p -> !skipped(root, p)).sorted().toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                String ext = Checks.ext(rel);
                if (f.toFile().length() > 512 * 1024) continue;
                if (CODE.contains(ext) && !Glob.any(tests, rel)) scanCode(self, rel, Files.readString(f), props, out);
                else if (rel.matches("(.*/)?application[^/]*\\.(ya?ml|properties)")) scanConfig(self, rel, Files.readString(f), out);
            }
        }
        return dedupe(out);
    }

    private static boolean skipped(Path root, Path p) {
        for (Path part : root.relativize(p)) if (SKIP.contains(part.toString())) return true;
        return false;
    }

    public static void scanCode(String self, String rel, String text, Map<String, String> props, List<Edge> out) {
        Matcher feign = FEIGN.matcher(text);
        if (feign.find()) {
            Map<String, String> attrs = attrs(feign.group(1));
            String base = resolve(attrs.getOrDefault("path", ""), props);
            String url = resolve(attrs.getOrDefault("url", ""), props);
            Matcher host = URL_VALUE.matcher(url);
            if (host.find()) out.add(new Edge(self, "calls", "host " + host.group(1), rel, line(text, feign.start())));
            else if (!attrs.getOrDefault("name", attrs.getOrDefault("value", "")).isBlank()) out.add(new Edge(self, "calls", "host " + attrs.getOrDefault("name", attrs.get("value")), rel, line(text, feign.start())));
            Matcher m = MAPPING.matcher(text);
            while (m.find()) {
                if (m.start() < feign.end()) continue;
                String verb = m.group(1).equals("Request") ? requestMethod(m.group(2)) : m.group(1).toUpperCase(Locale.ROOT);
                Matcher p = FIRST_STRING.matcher(m.group(2));
                String path = p.find() ? p.group(1) : "";
                out.add(new Edge(self, "calls", verb + " " + join(base, resolve(path, props)), rel, line(text, m.start())));
            }
            return; // a Feign interface has no other calls
        }
        Matcher u = URI_CALL.matcher(text);
        while (u.find()) {
            String target = resolve(u.group(2), props);
            Matcher host = URL_VALUE.matcher(target);
            String path = host.find() ? target.substring(host.end()) : target;
            if (!path.startsWith("/")) continue;
            String verb = switch (u.group(1)) {
                case "getForObject", "getForEntity" -> "GET";
                case "postForObject", "postForEntity" -> "POST";
                case "put" -> "PUT";
                case "delete" -> "DELETE";
                case "patchForObject" -> "PATCH";
                default -> verbBefore(text, u.start());
            };
            out.add(new Edge(self, "calls", verb + " " + path.replaceAll("[?#].*$", ""), rel, line(text, u.start())));
        }
        Matcher kl = KAFKA_LISTENER.matcher(text);
        while (kl.find()) {
            for (String topic : topics(kl.group(1))) out.add(new Edge(self, "consumes", "topic " + resolve(topic, props), rel, line(text, kl.start())));
        }
        add(text, KAFKA_SEND, 2, "produces", "topic ", self, rel, props, out);
        add(text, JMS_LISTENER, 1, "consumes", "queue ", self, rel, props, out);
        add(text, JMS_SEND, 1, "produces", "queue ", self, rel, props, out);
        add(text, RABBIT_LISTENER, 1, "consumes", "queue ", self, rel, props, out);
        add(text, JS_SUBSCRIBE, 1, "consumes", "topic ", self, rel, props, out);
        add(text, JS_SEND, 1, "produces", "topic ", self, rel, props, out);
        Matcher h = JS_HTTP.matcher(text);
        while (h.find()) {
            String path = h.group(3).replaceAll("^\\$\\{[^}]+\\}", "");
            Matcher host = URL_VALUE.matcher(path);
            if (host.find()) path = path.substring(host.end());
            if (path.startsWith("/")) out.add(new Edge(self, "calls", h.group(2).toUpperCase(Locale.ROOT) + " " + path.replaceAll("\\$\\{[^}]+\\}", "{}"), rel, line(text, h.start())));
        }
        Matcher f = JS_FETCH.matcher(text);
        while (f.find()) {
            String path = f.group(1).replaceAll("^\\$\\{[^}]+\\}", "");
            Matcher host = URL_VALUE.matcher(path);
            if (host.find()) path = path.substring(host.end());
            if (path.startsWith("/")) out.add(new Edge(self, "calls", (f.group(3) == null ? "GET" : f.group(3).toUpperCase(Locale.ROOT)) + " " + path.replaceAll("\\$\\{[^}]+\\}", "{}"), rel, line(text, f.start())));
        }
    }

    static void scanConfig(String self, String rel, String text, List<Edge> out) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].stripLeading().startsWith("#")) continue;
            Matcher m = URL_VALUE.matcher(lines[i]);
            while (m.find()) {
                String host = m.group(1);
                if (host.equals("localhost") || host.matches("[0-9.]+")) continue;
                out.add(new Edge(self, "config", "host " + host, rel, i + 1));
            }
        }
    }

    private static void add(String text, Pattern p, int group, String kind, String prefix, String self, String rel, Map<String, String> props, List<Edge> out) {
        Matcher m = p.matcher(text);
        while (m.find()) out.add(new Edge(self, kind, prefix + resolve(m.group(group), props), rel, line(text, m.start())));
    }

    /** The strings of a topics = ... attribute: up to the next attribute (name =) outside quotes. */
    static List<String> topics(String args) {
        List<String> out = new ArrayList<>();
        Matcher start = TOPICS_START.matcher(args);
        if (!start.find()) return out;
        String rest = args.substring(start.end());
        boolean quoted = false;
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (!quoted && ch == '=') {
                end = rest.lastIndexOf(',', i);
                break;
            }
        }
        Matcher str = STRINGS.matcher(rest.substring(0, Math.max(0, end)));
        while (str.find()) out.add(str.group(1));
        return out;
    }

    private static Map<String, String> attrs(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ATTR.matcher(s);
        while (m.find()) out.put(m.group(1), m.group(2));
        if (out.isEmpty()) {
            Matcher f = FIRST_STRING.matcher(s);
            if (f.find()) out.put("name", f.group(1));
        }
        return out;
    }

    private static String requestMethod(String args) {
        Matcher m = REQ_METHOD.matcher(args);
        return m.find() ? m.group(1) : "*";
    }

    /** The HTTP verb of a WebClient / RestClient chain: the last .get() / .post() ... before the .uri(...). */
    private static String verbBefore(String text, int at) {
        String before = text.substring(Math.max(0, at - 200), at);
        Matcher m = HTTP_VERB.matcher(before);
        String verb = "*";
        while (m.find()) verb = m.group(1).toUpperCase(Locale.ROOT);
        return verb;
    }

    private static String join(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (b.isEmpty()) return path.startsWith("/") ? path : "/" + path;
        return b + (path.startsWith("/") || path.isEmpty() ? path : "/" + path);
    }

    private static int line(String text, int offset) {
        int n = 1;
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(:([^}]*))?\\}");

    /** ${key} and ${key:default} from the application configuration; unknown ones stay as written. */
    static String resolve(String value, Map<String, String> props) {
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = props.get(m.group(1));
            if (v == null) v = m.group(3) != null ? m.group(3) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Flattened keys of src/main/resources/application*.yml and *.properties (first value wins). */
    static Map<String, String> properties(Path root) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(Files::isRegularFile).filter(p -> !skipped(root, p)).filter(p -> p.getFileName().toString().matches("application[^/]*\\.(ya?ml|properties)")).sorted().toList()) {
                String text = Files.readString(f);
                if (f.toString().endsWith(".properties")) {
                    for (String l : text.split("\n")) {
                        int eq = l.indexOf('=');
                        if (eq > 0 && !l.stripLeading().startsWith("#")) out.putIfAbsent(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
                    }
                } else {
                    for (String doc : Catalog.documents(text)) {
                        try {
                            flatten("", Yaml.parse(doc), out);
                        } catch (RuntimeException e) {
                            // a file the small YAML reader cannot read: its placeholders stay unresolved
                        }
                    }
                }
            }
        } catch (IOException e) {
            // no configuration: nothing to resolve
        }
        return out;
    }

    private static void flatten(String prefix, Object o, Map<String, String> out) {
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) flatten(prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey(), e.getValue(), out);
        } else if (o != null && !(o instanceof List<?>)) {
            out.putIfAbsent(prefix, o.toString());
        }
    }

    private static List<Edge> dedupe(List<Edge> in) {
        Map<String, Edge> seen = new LinkedHashMap<>();
        for (Edge e : in) seen.putIfAbsent(e.kind() + "|" + e.target() + "|" + e.file(), e);
        return new ArrayList<>(seen.values());
    }
}
