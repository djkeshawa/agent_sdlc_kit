package sdlc.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.integrations.GitHub;
import sdlc.util.Json;

/**
 * Coupling the catalog does not show. `catalog cochange <EPIC>...` finds, through GitHub commit
 * search by Jira key, which components changed together for each past epic; a pair that changes
 * together again and again with no declared or scanned dependency is usually a hidden coupling
 * (a shared table, library or copied DTO). `catalog import-traces --file <json>` adds the calls
 * your tracing system saw between services (Jaeger's dependencies format, or a plain list).
 */
public final class CoChange {
    private final Workflow w;

    CoChange(Workflow w) {
        this.w = w;
    }

    /** A pair of components, how often and in which epics they changed together, and what explains it. */
    public record Pair(String a, String b, int count, List<String> epics, String known) {}

    public int run(List<String> epics, int min) {
        if (epics.isEmpty()) throw new UsageException("usage: sdlc catalog cochange <EPIC> [<EPIC> ...] [--min 2]");
        Map<String, Set<String>> byEpic = new LinkedHashMap<>();
        for (String epic : epics) {
            List<String> keys = Eval.keysOf(w, epic);
            Set<String> comps = new TreeSet<>();
            for (String repo : GitHub.reposFor(keys, Catalog.knownRepos()).keySet()) comps.add(Catalog.componentOfRepo(repo));
            byEpic.put(epic, comps);
            w.console.out(epic + ": " + comps);
        }
        Catalog c = Catalog.load();
        List<Pair> pairs = pairs(byEpic, declared(c), seen(c));
        StringBuilder sb = new StringBuilder("\n# Components that change together\n\n| Pair | Epics together | Explained by |\n| --- | --- | --- |\n");
        List<Object> json = new ArrayList<>();
        for (Pair p : pairs) {
            sb.append("| ").append(p.a()).append(" + ").append(p.b()).append(" | ").append(p.count()).append(" (").append(String.join(", ", p.epics())).append(") | ")
                    .append(p.known().equals("none") && p.count() >= min ? "**nothing: likely hidden coupling**" : p.known()).append(" |\n");
            json.add(Store.map("a", p.a(), "b", p.b(), "count", p.count(), "epics", p.epics(), "known", p.known()));
        }
        Store.write(Catalog.dir().resolve("cochange.json"), Store.map("epics", epics, "pairs", json));
        Store.writeText(Catalog.dir().resolve("cochange.md"), sb.toString());
        w.console.out(sb.toString());
        long hidden = pairs.stream().filter(p -> p.known().equals("none") && p.count() >= min).count();
        w.console.out(hidden + " pair(s) changed together at least " + min + " times with no declared or scanned dependency. Saved: " + Ctx.rel(Catalog.dir().resolve("cochange.md")));
        return 0;
    }

    /** Every pair of components that changed in the same epic, most frequent first. */
    public static List<Pair> pairs(Map<String, Set<String>> byEpic, Set<String> declared, Set<String> seen) {
        Map<String, List<String>> together = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : byEpic.entrySet()) {
            List<String> comps = new ArrayList<>(e.getValue());
            for (int i = 0; i < comps.size(); i++) for (int j = i + 1; j < comps.size(); j++) together.computeIfAbsent(key(comps.get(i), comps.get(j)), k -> new ArrayList<>()).add(e.getKey());
        }
        List<Pair> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : together.entrySet()) {
            String[] ab = e.getKey().split("\\|");
            String known = declared.contains(e.getKey()) ? "declared in the catalog" : seen.contains(e.getKey()) ? "found in code or traces (not declared)" : "none";
            out.add(new Pair(ab[0], ab[1], e.getValue().size(), e.getValue(), known));
        }
        out.sort((x, y) -> y.count() != x.count() ? y.count() - x.count() : (x.a() + x.b()).compareTo(y.a() + y.b()));
        return out;
    }

    static String key(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** Pairs the catalog declares: consumesApis of an API another component provides, and dependsOn. */
    static Set<String> declared(Catalog c) {
        Set<String> out = new TreeSet<>();
        for (Catalog.Component comp : c.components.values()) {
            for (String api : comp.consumesApis()) {
                Catalog.Api a = c.apis.get(api);
                if (a != null && !a.component().isBlank() && !a.component().equals(comp.name())) out.add(key(comp.name(), a.component()));
                for (Catalog.Component other : c.components.values()) if (other != comp && other.providesApis().contains(api)) out.add(key(comp.name(), other.name()));
            }
            for (String d : comp.dependsOn()) if (c.components.containsKey(d)) out.add(key(comp.name(), d));
        }
        return out;
    }

    /** Pairs scans and traces saw. */
    static Set<String> seen(Catalog c) {
        Set<String> out = new TreeSet<>();
        for (CatalogScan.Edge e : CatalogScan.allEdges()) {
            if (e.kind().equals("provides")) continue;
            String by = CatalogScan.provider(c, e.target());
            if (by != null && !by.equals(e.component())) out.add(key(e.component(), by));
        }
        return out;
    }

    // ---------------- runtime traces ----------------

    /** sdlc catalog import-traces --file <json>: Jaeger's /api/dependencies ({data: [{parent, child, callCount}]}) or [{from, to, calls}]. */
    public static int importTraces(Path file) throws IOException {
        Object root = Json.parse(Files.readString(file));
        List<Object> rows = root instanceof Map<?, ?> m ? Json.list(m.get("data")) : Json.list(root);
        List<Object> edges = new ArrayList<>();
        for (Object o : rows) {
            Map<String, Object> r = Json.map(o);
            String from = Work.str(r.containsKey("parent") ? r.get("parent") : r.get("from")).strip().toLowerCase(Locale.ROOT);
            String to = Work.str(r.containsKey("child") ? r.get("child") : r.get("to")).strip().toLowerCase(Locale.ROOT);
            if (from.isBlank() || to.isBlank() || from.equals(to)) continue;
            String calls = Work.str(r.containsKey("callCount") ? r.get("callCount") : r.getOrDefault("calls", "0"));
            edges.add(Store.map("from", from, "kind", "runtime", "target", "host " + to, "file", "traces (" + calls.replaceAll("\\.0$", "") + " calls)", "line", 0));
        }
        Store.write(Catalog.dir().resolve("edges").resolve("runtime.json"), Store.map("component", "runtime", "edges", edges));
        System.out.println(edges.size() + " service-to-service call(s) imported into " + Ctx.rel(Catalog.dir().resolve("edges/runtime.json")) + ". The graph and cochange use them.");
        return 0;
    }
}
