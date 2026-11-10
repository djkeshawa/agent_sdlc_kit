package sdlc.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.util.Json;
import sdlc.util.Markdown;
import sdlc.util.Yaml;

/**
 * The solution design's machine-readable block, on the epic's "Solution design" page:
 *
 *   # sdlc-solution
 *   requirements: [R-01, R-02]
 *   components:
 *     - {name: payments-service, role: provider, change: ..., requirements: [R-01], evidence: [...]}
 *   ruledOut:  [{name: notification-service, reason: ...}]
 *   disputed:  []
 *   contracts:
 *     - {provider: payments-service, api: payments-api, add: ["POST /refunds"], remove: [], compatibility: additive}
 *   order: [payments-service, orders-service]
 *
 * Each component gets a slice (its entry, the contracts it provides and may consume, its
 * requirements) with a hash: an LLD names the slice hash it was written from, so a changed slice
 * re-opens only the LLDs it touches.
 */
public final class Solution {
    public record Component(String name, String role, String change, List<String> requirements, List<String> evidence) {}

    public record Contract(String provider, String api, List<String> add, List<String> remove, String compatibility) {
        public boolean breaking() {
            return compatibility.equals("breaking");
        }
    }

    public final List<String> requirements;
    public final Map<String, Component> components;
    public final List<Map<String, Object>> ruledOut;
    public final List<String> disputed;
    public final List<Contract> contracts;
    public final List<String> order;
    /** R-01 -> its text, from the page's requirement lines (when the page has them). */
    public final Map<String, String> requirementText;

    private Solution(Map<String, Object> root, Map<String, String> requirementText) {
        this.requirements = Work.strings(root.get("requirements"));
        this.components = new LinkedHashMap<>();
        for (Object o : Json.list(root.get("components"))) {
            if (!(o instanceof Map<?, ?>)) throw new UsageException("'# sdlc-solution': each component must be a mapping with name, role, change");
            Map<String, Object> c = Json.map(o);
            String name = Work.str(c.get("name")).strip().toLowerCase(Locale.ROOT);
            if (name.isBlank()) throw new UsageException("'# sdlc-solution': a component has no name");
            if (components.containsKey(name)) throw new UsageException("'# sdlc-solution': component " + name + " is listed twice");
            components.put(name, new Component(name, Work.str(c.getOrDefault("role", "internal")).strip(), Work.str(c.get("change")).strip(),
                    Work.strings(c.get("requirements")), Work.strings(c.get("evidence"))));
        }
        this.ruledOut = new ArrayList<>();
        for (Object o : Json.list(root.get("ruledOut"))) if (o instanceof Map<?, ?>) ruledOut.add(Json.map(o));
        this.disputed = new ArrayList<>();
        for (Object o : Json.list(root.get("disputed"))) disputed.add(o instanceof Map<?, ?> m ? Work.str(m.get("name")) : Work.str(o));
        this.contracts = new ArrayList<>();
        for (Object o : Json.list(root.get("contracts"))) {
            Map<String, Object> c = Json.map(o);
            contracts.add(new Contract(Work.str(c.get("provider")).strip().toLowerCase(Locale.ROOT), Work.str(c.get("api")).strip().toLowerCase(Locale.ROOT),
                    Work.strings(c.get("add")), Work.strings(c.get("remove")), Work.str(c.getOrDefault("compatibility", "additive")).strip().toLowerCase(Locale.ROOT)));
        }
        List<String> ord = new ArrayList<>();
        for (String s : Work.strings(root.get("order"))) ord.add(s.toLowerCase(Locale.ROOT));
        this.order = ord;
        this.requirementText = requirementText;
    }

    private static final Pattern FENCED = Pattern.compile("(?s)```ya?ml\\s*\\n(#\\s*sdlc-solution.*?)```");
    private static final Pattern REQ_LINE = Pattern.compile("(?m)^\\s*(?:[-*|]\\s*)?\\**(R-[0-9]+)\\**\\s*[:|.)-]\\s*(.+?)\\s*\\|?\\s*$");

    /** From a Markdown draft. */
    public static Solution parse(String markdown) {
        Matcher m = FENCED.matcher(markdown);
        if (!m.find()) throw new UsageException("the solution design has no '# sdlc-solution' block: a ```yaml code block starting with '# sdlc-solution' (see .agent/templates/solution-design.md)");
        return fromYaml(m.group(1), requirementLines(markdown));
    }

    /** From a Confluence page (storage format). */
    public static Solution parseStorage(String storage) {
        for (String[] b : Markdown.codeBlocks(storage)) {
            if (b[1].stripLeading().startsWith("# sdlc-solution")) return fromYaml(b[1], requirementLines(Markdown.toMarkdown(storage)));
        }
        throw new UsageException("the solution design page has no '# sdlc-solution' code block");
    }

    static Solution fromYaml(String yaml, Map<String, String> reqText) {
        Object root;
        try {
            root = Yaml.parse(yaml);
        } catch (RuntimeException e) {
            throw new UsageException("'# sdlc-solution' block: " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?>)) throw new UsageException("'# sdlc-solution' block must be a mapping");
        return new Solution(Json.map(root), reqText);
    }

    private static Map<String, String> requirementLines(String md) {
        Map<String, String> out = new LinkedHashMap<>();
        String noBlock = FENCED.matcher(md).replaceAll("");
        Matcher m = REQ_LINE.matcher(noBlock);
        while (m.find()) out.putIfAbsent(m.group(1), m.group(2).strip());
        return out;
    }

    /** Problems that block publishing (and approving) the solution. An empty catalog skips the catalog checks. */
    public List<String> problems(Catalog catalog) {
        List<String> p = new ArrayList<>();
        if (components.isEmpty()) p.add("no components");
        if (requirements.isEmpty()) p.add("no requirements (list the R-.. ids the solution covers)");
        Set<String> covered = new LinkedHashSet<>();
        for (Component c : components.values()) {
            if (!catalog.isEmpty() && !catalog.components.containsKey(c.name())) p.add(c.name() + " is not in the catalog (catalog sync, or fix the name)");
            if (!c.role().matches("provider|consumer|both|internal")) p.add(c.name() + ": role must be provider, consumer, both or internal");
            if (c.change().isBlank()) p.add(c.name() + ": change is empty");
            if (c.requirements().isEmpty()) p.add(c.name() + ": no requirements");
            for (String r : c.requirements()) {
                if (!requirements.contains(r)) p.add(c.name() + ": " + r + " is not in requirements");
                covered.add(r);
            }
        }
        for (String r : requirements) if (!covered.contains(r)) p.add(r + " is covered by no component");
        if (!disputed.isEmpty()) p.add("disputed components " + disputed + ": the architect decides each one (move it to components or ruledOut) before publishing");
        for (Map<String, Object> r : ruledOut) if (Work.str(r.get("reason")).isBlank()) p.add("ruled out " + r.get("name") + " without a reason");
        for (Contract c : contracts) {
            if (!components.containsKey(c.provider())) p.add("contract " + c.api() + ": provider " + c.provider() + " is not a listed component");
            if (c.api().isBlank()) p.add("a contract of " + c.provider() + " names no api");
            if (!c.compatibility().matches("additive|breaking")) p.add("contract " + c.api() + ": compatibility must be additive or breaking");
            if (c.add().isEmpty() && c.remove().isEmpty()) p.add("contract " + c.api() + ": lists no operation or message to add or remove");
            if (!c.remove().isEmpty() && !c.breaking()) p.add("contract " + c.api() + ": removing " + c.remove() + " is a breaking change (compatibility: breaking, with an expand -> migrate -> contract plan)");
            if (!catalog.isEmpty()) {
                Catalog.Api a = catalog.apis.get(c.api());
                Catalog.Component prov = catalog.components.get(c.provider());
                boolean newApi = a == null && prov != null;
                if (!newApi && (a == null || !c.provider().equals(a.component()))) p.add("contract " + c.api() + ": the catalog says " + (a == null ? "no such API" : "it belongs to " + a.component()));
            }
        }
        for (String o : order) if (!components.containsKey(o)) p.add("order names " + o + ", which is not a listed component");
        return p;
    }

    /** What one component's team gets: its entry, its requirements, the contracts it provides, the ones it may consume. */
    public String slice(String component) {
        Component c = components.get(component);
        if (c == null) throw new UsageException(component + " is not in the solution");
        StringBuilder sb = new StringBuilder();
        sb.append("component: ").append(c.name()).append('\n');
        sb.append("role: ").append(c.role()).append('\n');
        sb.append("change: ").append(c.change()).append('\n');
        sb.append("requirements:\n");
        for (String r : c.requirements()) sb.append("  - ").append(r).append(requirementText.containsKey(r) ? ": " + requirementText.get(r) : "").append('\n');
        sb.append("provides:\n");
        for (Contract k : contracts) if (k.provider().equals(c.name())) sb.append(contractLine(k));
        sb.append("mayConsume:\n");
        for (Contract k : contracts) if (!k.provider().equals(c.name())) sb.append(contractLine(k));
        int at = order.indexOf(c.name());
        sb.append("mergeOrder: ").append(at < 0 ? "any" : (at + 1) + " of " + order.size() + " " + order).append('\n');
        return sb.toString();
    }

    private static String contractLine(Contract k) {
        return "  - {provider: " + k.provider() + ", api: " + k.api() + ", add: " + k.add() + ", remove: " + k.remove() + ", compatibility: " + k.compatibility() + "}\n";
    }

    public String sliceHash(String component) {
        return Verify.sha256(slice(component)).substring(0, 12);
    }

    /** Operations of an API this solution allows to be added (normalized), for the contract check. */
    public Set<String> allowedAdds(String api) {
        Set<String> out = new LinkedHashSet<>();
        for (Contract k : contracts) if (k.api().equals(api)) for (String op : k.add()) out.add(Contracts.normalize(op));
        return out;
    }

    public Set<String> allowedRemoves(String api) {
        Set<String> out = new LinkedHashSet<>();
        for (Contract k : contracts) if (k.api().equals(api)) for (String op : k.remove()) out.add(Contracts.normalize(op));
        return out;
    }

    public boolean breakingAllowed(String api) {
        return contracts.stream().anyMatch(k -> k.api().equals(api) && k.breaking());
    }
}
