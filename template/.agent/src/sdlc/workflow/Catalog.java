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
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.integrations.GitHub;
import sdlc.util.Json;
import sdlc.util.Yaml;

/**
 * The federated component catalog. Each repository describes itself in catalog-info.yaml, in
 * Backstage's descriptor format (kind Component and kind API), plus two annotations a scan
 * cannot know:
 *   sdlc/owns        domain nouns the component is the source of truth for ("refund, payment")
 *   sdlc/invariants  rules that must stay true ("a captured payment is never deleted")
 * `catalog sync` copies the files of the related repositories into .sdlc/catalog/repos/, with
 * the API definitions they point to, so agents and checks can read every component offline.
 */
public final class Catalog {
    /** An API a component provides: its spec file (path inside the component's repository) and type. */
    public record Api(String name, String type, String component, String definition, Path localCopy) {}

    public record Component(String name, String repo, String owner, String system, List<String> owns, List<String> invariants,
                            List<String> providesApis, List<String> consumesApis, List<String> dependsOn, boolean self) {}

    public final Map<String, Component> components = new LinkedHashMap<>();
    public final Map<String, Api> apis = new LinkedHashMap<>();
    public final List<String> problems = new ArrayList<>();

    private Catalog() {}

    public static Path file() {
        return Ctx.root.resolve(Ctx.cfg.str("catalog.file", "catalog-info.yaml"));
    }

    static Path dir() {
        return Ctx.work("catalog");
    }

    /** This repository's catalog plus every synced one. Empty when nothing is there. */
    public static Catalog load() {
        Catalog c = new Catalog();
        if (Files.isRegularFile(file())) c.add(read(file()), "", true, Ctx.root);
        Path repos = dir().resolve("repos");
        if (Files.isDirectory(repos)) {
            try (Stream<Path> s = Files.list(repos)) {
                for (Path f : s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                    String repo = Work.str(Store.read(f.resolveSibling(f.getFileName() + ".json")).get("repo"));
                    c.add(read(f), repo, false, dir().resolve("apis"));
                }
            } catch (IOException e) {
                c.problems.add("could not list " + Ctx.rel(repos) + ": " + e.getMessage());
            }
        }
        return c;
    }

    /** This repository's catalog from text (catalog-info.yaml as committed at some ref); empty for blank text. */
    static Catalog parse(String text) {
        Catalog c = new Catalog();
        if (!text.isBlank()) c.add(text, "", true, Ctx.root);
        return c;
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new UsageException("cannot read " + f + ": " + e.getMessage());
        }
    }

    /** Parses one catalog-info.yaml (several documents separated by ---) into this catalog. */
    @SuppressWarnings("unchecked")
    void add(String text, String repo, boolean self, Path apiBase) {
        for (String doc : documents(text)) {
            Object root;
            try {
                root = Yaml.parse(doc);
            } catch (RuntimeException e) {
                problems.add((repo.isBlank() ? "catalog-info.yaml" : repo) + ": " + e.getMessage());
                continue;
            }
            if (!(root instanceof Map<?, ?> m) || m.isEmpty()) continue;
            Map<String, Object> d = (Map<String, Object>) m;
            String kind = Work.str(d.get("kind"));
            String name = Work.str(Json.at(d, "metadata.name")).strip().toLowerCase(Locale.ROOT);
            if (name.isBlank()) continue;
            Map<String, Object> ann = Json.map(Json.at(d, "metadata.annotations"));
            if (kind.equals("Component")) {
                String r = repo.isBlank() ? Work.str(ann.getOrDefault("github.com/project-slug", "")) : repo;
                components.put(name, new Component(name, r, entity(Work.str(Json.at(d, "spec.owner"))), entity(Work.str(Json.at(d, "spec.system"))),
                        csv(ann.get("sdlc/owns")), semis(ann.get("sdlc/invariants")), names(Json.at(d, "spec.providesApis")),
                        names(Json.at(d, "spec.consumesApis")), names(Json.at(d, "spec.dependsOn")), self));
            } else if (kind.equals("API")) {
                String def = definition(Json.at(d, "spec.definition"));
                Path copy = def.isBlank() ? null : self ? apiBase.resolve(def) : apiBase.resolve(name + ext(def));
                apis.put(name, new Api(name, Work.str(Json.at(d, "spec.type")).toLowerCase(Locale.ROOT), "", def, copy));
            }
        }
        for (Component comp : components.values()) {
            for (String api : comp.providesApis()) {
                Api a = apis.get(api);
                if (a != null && a.component().isBlank()) apis.put(api, new Api(a.name(), a.type(), comp.name(), a.definition(), a.localCopy()));
            }
        }
    }

    static List<String> documents(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            if (line.strip().equals("---")) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(line).append('\n');
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** "$text: ./api/openapi.yaml" (or a plain string) -> api/openapi.yaml. */
    private static String definition(Object def) {
        String s = def instanceof Map<?, ?> m ? Work.str(m.get("$text")) : Work.str(def);
        s = s.strip();
        if (s.startsWith("http") || s.contains("\n")) return ""; // inline definitions and URLs are not files here
        while (s.startsWith("./")) s = s.substring(2);
        return s;
    }

    private static String ext(String def) {
        int dot = def.lastIndexOf('.');
        return dot < 0 ? ".yaml" : def.substring(dot);
    }

    /** "group:team-payments" or "component:default/orders" -> the name. */
    private static String entity(String ref) {
        String s = ref.strip();
        if (s.contains(":")) s = s.substring(s.indexOf(':') + 1);
        if (s.contains("/")) s = s.substring(s.lastIndexOf('/') + 1);
        return s.toLowerCase(Locale.ROOT);
    }

    private static List<String> names(Object o) {
        List<String> out = new ArrayList<>();
        for (String s : Work.strings(o)) out.add(entity(s));
        return out;
    }

    private static List<String> csv(Object o) {
        List<String> out = new ArrayList<>();
        for (String s : Work.str(o).split(",")) if (!s.isBlank()) out.add(s.strip().toLowerCase(Locale.ROOT));
        return out;
    }

    private static List<String> semis(Object o) {
        List<String> out = new ArrayList<>();
        for (String s : Work.str(o).split(";")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    public boolean isEmpty() {
        return components.isEmpty();
    }

    /** This repository's component, or null when it has no catalog-info.yaml. */
    public Component self() {
        return components.values().stream().filter(Component::self).findFirst().orElse(null);
    }

    /** The name of this repository's component: catalog-info.yaml, else project.name. */
    public static String selfName() {
        Component s = load().self();
        return s != null ? s.name() : Ctx.cfg.str("project.name").toLowerCase(Locale.ROOT);
    }

    /** API definition files of this repository (paths from the root): the files a change to a contract touches. */
    public List<String> contractFiles() {
        List<String> out = new ArrayList<>();
        Component s = self();
        if (s == null) return out;
        for (String api : s.providesApis()) {
            Api a = apis.get(api);
            if (a != null && !a.definition().isBlank()) out.add(a.definition());
        }
        return out;
    }

    /** The component name for a repository (owner/name): the synced catalog, github.relatedRepos, else the repository's own name. */
    public static String componentOfRepo(String repo) {
        String r = repo.strip();
        for (Component c : load().components.values()) if (r.equalsIgnoreCase(c.repo())) return c.name();
        for (Map<String, Object> rel : Ctx.cfg.maps("github.relatedRepos")) {
            if (r.equalsIgnoreCase(Work.str(rel.get("repo"))) && !Work.str(rel.get("name")).isBlank()) return Work.str(rel.get("name")).strip().toLowerCase(Locale.ROOT);
        }
        return r.substring(r.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    /** Repositories (owner/name) the tool knows: this one, github.relatedRepos and catalog.repos. */
    public static List<String> knownRepos() {
        Set<String> out = new LinkedHashSet<>();
        String origin = GitHub.originRepo();
        if (!origin.isBlank()) out.add(origin);
        for (Map<String, Object> r : Ctx.cfg.maps("github.relatedRepos")) if (!Work.str(r.get("repo")).isBlank()) out.add(Work.str(r.get("repo")));
        out.addAll(Ctx.cfg.list("catalog.repos"));
        return new ArrayList<>(out);
    }

    // ---------------- commands ----------------

    /** sdlc catalog check: this repository's catalog-info.yaml is complete and its API files exist. */
    public static int check() {
        List<String> p = checkProblems();
        if (p.isEmpty()) {
            Component s = load().self();
            System.out.println("catalog-info.yaml OK: component " + s.name() + ", owns " + s.owns() + ", provides " + s.providesApis() + ", consumes " + s.consumesApis());
            return 0;
        }
        p.forEach(x -> System.out.println("PROBLEM " + x));
        return 1;
    }

    public static List<String> checkProblems() {
        List<String> p = new ArrayList<>();
        if (!Files.isRegularFile(file())) {
            p.add(Ctx.rel(file()) + " is missing (copy .agent/templates/catalog-info.yaml, or let /analyse draft it)");
            return p;
        }
        Catalog c = new Catalog();
        c.add(read(file()), "", true, Ctx.root);
        p.addAll(c.problems);
        Component s = c.self();
        if (s == null) {
            p.add("no 'kind: Component' with metadata.name");
            return p;
        }
        if (s.owner().isBlank()) p.add(s.name() + ": spec.owner is empty");
        if (s.owns().isEmpty()) p.add(s.name() + ": annotation sdlc/owns is empty (the domain nouns this component is the source of truth for)");
        for (String api : s.providesApis()) {
            Api a = c.apis.get(api);
            if (a == null) p.add(s.name() + " provides " + api + " but the file has no 'kind: API' named " + api);
            else if (a.definition().isBlank()) p.add("API " + api + ": spec.definition needs $text: <path to the spec file>");
            else if (!Files.isRegularFile(Ctx.root.resolve(a.definition()))) p.add("API " + api + ": definition file " + a.definition() + " does not exist");
        }
        return p;
    }

    /**
     * sdlc catalog sync: copies the catalog-info.yaml of every related repository (github.relatedRepos
     * and catalog.repos), and the API definitions it names, into .sdlc/catalog/. Local copies from
     * `repos sync` are used when present, else gh reads the default branch.
     */
    public static int sync() {
        Set<String> repos = new LinkedHashSet<>();
        Map<String, String> local = new LinkedHashMap<>();
        for (Map<String, Object> r : Ctx.cfg.maps("github.relatedRepos")) {
            String repo = Work.str(r.get("repo"));
            if (repo.isBlank()) continue;
            repos.add(repo);
            local.put(repo, Work.str(r.get("name")));
        }
        repos.addAll(Ctx.cfg.list("catalog.repos"));
        if (repos.isEmpty()) {
            System.out.println("No repositories to sync: set github.relatedRepos or catalog.repos in .agent/sdlc.yml.");
            return 0;
        }
        int ok = 0;
        for (String repo : repos) {
            String name = local.getOrDefault(repo, "");
            Path clone = name.isBlank() ? null : Ctx.work("repos", name);
            String text = readRepoFile(repo, clone, Ctx.cfg.str("catalog.file", "catalog-info.yaml"));
            if (text == null) {
                System.out.println("- " + repo + ": no catalog-info.yaml");
                continue;
            }
            Catalog one = new Catalog();
            one.add(text, repo, false, dir().resolve("apis"));
            Component comp = one.components.values().stream().findFirst().orElse(null);
            String file = comp != null ? comp.name() : repo.substring(repo.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            Store.writeText(dir().resolve("repos").resolve(file + ".yaml"), text);
            Store.write(dir().resolve("repos").resolve(file + ".yaml.json"), Store.map("repo", repo, "syncedFrom", clone != null && Files.isDirectory(clone) ? "local copy" : "gh"));
            int defs = 0;
            for (Api a : one.apis.values()) {
                if (a.definition().isBlank()) continue;
                String spec = readRepoFile(repo, clone, a.definition());
                if (spec == null) continue;
                Store.writeText(a.localCopy(), spec);
                defs++;
            }
            String edges = readRepoFile(repo, clone, EDGES_FILE);
            if (edges != null) Store.writeText(dir().resolve("edges").resolve(file + ".json"), edges);
            System.out.println("- " + repo + ": " + (comp == null ? "no Component" : "component " + comp.name()) + ", " + defs + " API definition(s)" + (edges == null ? "" : ", scanned edges"));
            ok++;
        }
        System.out.println(ok + " of " + repos.size() + " repositories synced into " + Ctx.rel(dir()) + ". Next: java .agent/Sdlc.java catalog graph");
        return 0;
    }

    private static String readRepoFile(String repo, Path clone, String path) {
        if (clone != null && Files.isRegularFile(clone.resolve(path))) {
            try {
                return Files.readString(clone.resolve(path));
            } catch (IOException e) {
                return null;
            }
        }
        try {
            return GitHub.file(repo, path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Where `catalog scan --write` puts this repository's scanned edges, for other repositories' sync. */
    public static final String EDGES_FILE = "docs/agent/catalog-edges.json";

    /** sdlc catalog graph: one Markdown page with every component, API, operation and scanned dependency, for agents. */
    public static int graph() {
        Catalog c = load();
        if (c.isEmpty()) throw new UsageException("the catalog is empty: add catalog-info.yaml (catalog check) and run: java .agent/Sdlc.java catalog sync");
        String text = c.graphText();
        Store.writeText(dir().resolve("graph.md"), text);
        System.out.println(text);
        System.out.println("Saved: " + Ctx.rel(dir().resolve("graph.md")));
        return 0;
    }

    public String graphText() {
        StringBuilder sb = new StringBuilder("# Component catalog\n\nBuilt by `sdlc catalog graph` from catalog-info.yaml files and scans. "
                + "Use it to find candidates; confirm each in the code.\n\n## Components\n\n| Component | Repository | Owner | Owns (domain nouns) | Provides | Consumes | Invariants |\n| --- | --- | --- | --- | --- | --- | --- |\n");
        for (Component c : components.values()) {
            sb.append("| ").append(c.name()).append(c.self() ? " (this repo)" : "").append(" | ").append(c.repo()).append(" | ").append(c.owner()).append(" | ")
                    .append(String.join(", ", c.owns())).append(" | ").append(String.join(", ", c.providesApis())).append(" | ").append(String.join(", ", c.consumesApis()))
                    .append(" | ").append(String.join("; ", c.invariants())).append(" |\n");
        }
        sb.append("\n## APIs and their operations\n\n");
        for (Api a : apis.values()) {
            sb.append("- **").append(a.name()).append("** (").append(a.type()).append(") by ").append(a.component().isBlank() ? "?" : a.component())
                    .append(a.definition().isBlank() ? "" : ", `" + a.definition() + "`").append('\n');
            Contracts.Spec spec = Contracts.read(a);
            if (spec != null) for (String op : spec.operations().keySet()) sb.append("  - ").append(op).append('\n');
        }
        List<CatalogScan.Edge> edges = CatalogScan.allEdges();
        if (!edges.isEmpty()) {
            sb.append("\n## Dependencies found in code (scans)\n\n| From | Uses | Provided by | Evidence |\n| --- | --- | --- | --- |\n");
            for (CatalogScan.Edge e : edges) {
                if (e.kind().equals("provides")) continue;
                String by = CatalogScan.provider(this, e.target());
                sb.append("| ").append(e.component()).append(" | ").append(e.kind()).append(' ').append(e.target()).append(" | ").append(by == null ? "?" : by)
                        .append(" | ").append(e.file()).append(':').append(e.line()).append(" |\n");
            }
        }
        Path co = dir().resolve("cochange.md");
        if (Files.isRegularFile(co)) {
            try {
                sb.append('\n').append(Files.readString(co).strip()).append('\n');
            } catch (IOException e) {
                // the graph stands without it
            }
        }
        return sb.toString();
    }
}
