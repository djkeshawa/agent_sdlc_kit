package sdlc.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.util.Glob;

/**
 * Delivery lanes (lanes.enabled): how much process a story needs, decided from facts, not only
 * from what an agent says.
 *   patch            one repository, no change to a contract file it provides
 *   component        one repository, its tasks may change a contract file (additive)
 *   cross-component  a parent epic's solution design names this repository's component
 * The LLD's # sdlc-tasks block declares `lane:`; `tasks create` refuses a lane below what the
 * task scopes and the solution design imply, and an LLD written from an old solution slice.
 */
public final class Lanes {
    private Lanes() {}

    static final List<String> ORDER = List.of("patch", "component", "cross-component");

    public static boolean enabled() {
        return Ctx.cfg.bool("lanes.enabled", false);
    }

    static int rank(String lane) {
        return ORDER.indexOf(lane);
    }

    /** The lowest lane the facts allow, with why. */
    public record Floor(String lane, List<String> reasons, Solution solution, Docs.Doc solutionDoc) {}

    static Floor floor(Workflow w, String story, List<List<String>> scopes) {
        List<String> reasons = new ArrayList<>();
        String lane = "patch";
        Catalog c = Catalog.load();
        for (String f : c.contractFiles()) {
            for (int i = 0; i < scopes.size(); i++) {
                if (Glob.any(scopes.get(i), f)) {
                    lane = "component";
                    reasons.add("task " + (i + 1) + " may edit contract file " + f);
                }
            }
        }
        Docs.Doc d = null;
        Solution s = null;
        try {
            d = w.docs.solution(story);
        } catch (RuntimeException e) {
            reasons.add("could not look up a solution design: " + e.getMessage());
        }
        if (d != null) {
            s = Solution.parseStorage(w.wiki.page(d.id()).storage());
            lane = "cross-component";
            reasons.add("solution design " + d.title() + " governs " + story);
        }
        return new Floor(lane, reasons, s, d);
    }

    /** Problems with the LLD's declared lane and slice; empty when tasks may be created. */
    static List<String> problems(Workflow w, String story, Map<String, Object> block, List<List<String>> scopes) {
        List<String> p = new ArrayList<>();
        String declared = Work.str(block.getOrDefault("lane", "patch")).strip();
        if (rank(declared) < 0) {
            p.add("lane '" + declared + "' must be one of " + ORDER);
            return p;
        }
        Floor f = floor(w, story, scopes);
        if (rank(declared) < rank(f.lane())) p.add("the LLD says lane: " + declared + ", but " + String.join("; ", f.reasons()) + ". Set lane: " + f.lane() + " (the lane can be raised, not lowered)");
        if (f.solution() != null) {
            String self = Catalog.selfName();
            if (!w.approvals.gate(f.solutionDoc().key(), "solution")) p.add("the solution design " + f.solutionDoc().title() + " is not approved");
            if (!f.solution().components.containsKey(self)) {
                p.add("the solution design does not include this repository's component '" + self + "' (it has " + f.solution().components.keySet() + "). Ask the architect for a solution change.");
            } else {
                String want = f.solution().sliceHash(self);
                String have = Work.str(block.get("solutionSlice")).strip();
                if (!want.equals(have)) p.add("the LLD's solutionSlice is '" + have + "' but the approved solution's slice for " + self + " is " + want
                        + ": update the LLD from sources/solution-slice.md (java .agent/Sdlc.java docs pull " + story + ") and get it approved again");
            }
        }
        return p;
    }

    /** sdlc classify <KEY>: the lowest lane the facts allow, before or after the LLD. */
    public static int classify(Workflow w, String key) {
        List<List<String>> scopes = new ArrayList<>();
        Docs.Doc lld = w.docs.own(key, "lld");
        if (lld != null) {
            try {
                for (Map<String, Object> t : Tasks.parseStorage(w.wiki.page(lld.id()).storage())) scopes.add(Work.strings(t.get("scope")));
            } catch (UsageException e) {
                w.console.err("[sdlc] the LLD's tasks block: " + e.getMessage());
            }
        }
        Floor f = floor(w, key, scopes);
        w.console.out("Lowest lane for " + key + ": " + f.lane());
        f.reasons().forEach(r -> w.console.out("  - " + r));
        List<String> contracts = Catalog.load().contractFiles();
        if (f.lane().equals("patch")) {
            w.console.out(contracts.isEmpty() ? "  (no contract files known: add catalog-info.yaml with your APIs so a contract change is noticed)"
                    : "  - no task touches a contract file " + contracts + (lld == null ? " (no LLD yet: checked again at tasks create)" : ""));
        }
        w.console.out("A human may raise the lane in the LLD's # sdlc-tasks block (lane: ...). The tool refuses a lower one.");
        return 0;
    }

    /** Where tasks create records what the story may change in its contracts; committed, so check-pr in CI can read it. */
    public static java.nio.file.Path allowanceFile(String story) {
        return Ctx.root.resolve("docs/agent/contracts").resolve(story + ".json");
    }

    /**
     * What the story may change in this repository's contracts, from its lane: patch nothing,
     * component additions to its own APIs, cross-component exactly what its slice provides.
     */
    static Map<String, Object> allowance(String story, String lane, Floor f) {
        Catalog c = Catalog.load();
        Map<String, Object> apis = new java.util.LinkedHashMap<>();
        Catalog.Component self = c.self();
        if (self != null && !lane.equals("patch")) {
            for (String api : self.providesApis()) {
                Catalog.Api a = c.apis.get(api);
                if (a == null || a.definition().isBlank()) continue;
                if (lane.equals("component")) {
                    apis.put(api, Store.map("file", a.definition(), "add", List.of("*"), "remove", List.of(), "breaking", false));
                } else if (f.solution() != null) {
                    if (f.solution().allowedAdds(api).isEmpty() && f.solution().allowedRemoves(api).isEmpty()) continue;
                    apis.put(api, Store.map("file", a.definition(), "add", new ArrayList<>(f.solution().allowedAdds(api)),
                            "remove", new ArrayList<>(f.solution().allowedRemoves(api)), "breaking", f.solution().breakingAllowed(api)));
                }
            }
        }
        Map<String, Object> out = Store.map("story", story, "lane", lane, "apis", apis);
        if (f.solution() != null && self != null && f.solution().components.containsKey(self.name())) out.put("solutionSlice", f.solution().sliceHash(self.name()));
        return out;
    }
}
