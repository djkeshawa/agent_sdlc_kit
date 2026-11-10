package sdlc.selftest;

import static sdlc.selftest.Asserts.check;
import static sdlc.selftest.Asserts.expect;
import static sdlc.selftest.Asserts.json;
import static sdlc.selftest.Asserts.shell;
import static sdlc.selftest.Asserts.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sdlc.core.Config;
import sdlc.core.Ctx;
import sdlc.core.Store;
import sdlc.core.Work;
import sdlc.guard.Decision;
import sdlc.util.Proc;
import sdlc.workflow.Approvals;
import sdlc.workflow.Catalog;
import sdlc.workflow.CatalogScan;
import sdlc.workflow.Contracts;
import sdlc.workflow.Eval;
import sdlc.workflow.Lanes;
import sdlc.workflow.Solution;
import sdlc.workflow.Workflow;

/** Self test: the multi-component additions (scoping eval, catalog, solution design, contracts, locks). */
final class MultiRepoTests {
    private MultiRepoTests() {}

    static void run(Path tmp) throws IOException {
        eval(tmp);
        Config saved = Ctx.cfg;
        Ctx.cfg = Config.parse(CONFIG);
        try {
            catalog(tmp);
            contracts();
            scan(tmp);
            solution();
            epicFlow(tmp);
        } finally {
            Ctx.cfg = saved;
            for (String f : List.of("catalog-info.yaml", "api/openapi.yaml", "api/events.yaml")) Files.deleteIfExists(tmp.resolve(f));
        }
    }

    static final String CONFIG = "stack: java-maven\nproject:\n  name: payments-service\n  team: pay\njira:\n  projectKeys: [PROJ]\n"
            + "confluence:\n  publish:\n    lld: {space: DEV}\n    testDesign: {space: QE}\n    solution: {space: ARCH}\n"
            + "gates:\n  approvers:\n    solution: [arch]\nlanes:\n  enabled: true\n";

    static final String OPENAPI = """
            openapi: 3.0.3
            paths:
              /orders/{orderId}/refunds:
                post:
                  parameters:
                    - {name: Idempotency-Key, in: header, required: true, schema: {type: string}}
                  requestBody:
                    content:
                      application/json:
                        schema: {$ref: '#/components/schemas/RefundRequest'}
                  responses:
                    "201": {description: created}
                    "409": {description: closed}
              /refunds/{id}:
                get:
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              status: {type: string, enum: [PENDING, DONE]}
                              amount: {type: string}
            components:
              schemas:
                RefundRequest:
                  type: object
                  required: [amount]
                  properties:
                    amount: {type: string}
                    reason: {type: string, enum: [DAMAGED, OTHER]}
            """;

    static final String ASYNCAPI = """
            asyncapi: 2.6.0
            channels:
              refund-events:
                publish:
                  message:
                    name: RefundRequested
                    payload:
                      type: object
                      properties:
                        refundId: {type: string}
            """;

    static final String CATALOG = """
            apiVersion: backstage.io/v1alpha1
            kind: Component
            metadata:
              name: payments-service
              annotations:
                sdlc/owns: "payment, Refund"
                sdlc/invariants: "a captured payment is never deleted; refunds never exceed the payment"
            spec:
              type: service
              owner: group:default/team-payments
              providesApis: [payments-api, refund-events]
              consumesApis: [orders-api]
            ---
            apiVersion: backstage.io/v1alpha1
            kind: API
            metadata:
              name: payments-api
            spec:
              type: openapi
              owner: team-payments
              definition:
                $text: ./api/openapi.yaml
            ---
            apiVersion: backstage.io/v1alpha1
            kind: API
            metadata:
              name: refund-events
            spec:
              type: asyncapi
              owner: team-payments
              definition:
                $text: ./api/events.yaml
            """;

    static final String ORDERS_CATALOG = """
            apiVersion: backstage.io/v1alpha1
            kind: Component
            metadata:
              name: orders-service
              annotations:
                sdlc/owns: "order"
            spec:
              owner: team-orders
              providesApis: [orders-api]
              consumesApis: [refund-events]
            ---
            apiVersion: backstage.io/v1alpha1
            kind: API
            metadata:
              name: orders-api
            spec:
              type: openapi
              definition:
                $text: ./api/orders.yaml
            """;

    private static void catalog(Path tmp) throws IOException {
        check("catalog: missing file is a problem", Catalog.checkProblems().get(0).contains("missing"));
        Store.writeText(tmp.resolve("catalog-info.yaml"), CATALOG.replace("sdlc/owns: \"payment, Refund\"", "sdlc/owns: \"\""));
        Store.writeText(tmp.resolve("api/openapi.yaml"), OPENAPI);
        List<String> p = Catalog.checkProblems();
        check("catalog: empty owns and a missing API file are problems", p.stream().anyMatch(x -> x.contains("sdlc/owns")) && p.stream().anyMatch(x -> x.contains("api/events.yaml does not exist")));
        Store.writeText(tmp.resolve("catalog-info.yaml"), CATALOG);
        Store.writeText(tmp.resolve("api/events.yaml"), ASYNCAPI);
        check("catalog: complete file has no problems", Catalog.checkProblems().isEmpty());
        Catalog c = Catalog.load();
        check("catalog: this repo's component with owner, owns and invariants", c.self() != null && c.self().name().equals("payments-service") && c.self().owner().equals("team-payments")
                && c.self().owns().equals(List.of("payment", "refund")) && c.self().invariants().size() == 2);
        check("catalog: APIs belong to the component that provides them", "payments-service".equals(c.apis.get("refund-events").component()));
        check("catalog: contract files are the API definitions", c.contractFiles().equals(List.of("api/openapi.yaml", "api/events.yaml")));
        Store.writeText(tmp.resolve(".sdlc/catalog/repos/orders-service.yaml"), ORDERS_CATALOG);
        Store.write(tmp.resolve(".sdlc/catalog/repos/orders-service.yaml.json"), Store.map("repo", "acme/orders-service"));
        Store.writeText(tmp.resolve(".sdlc/catalog/apis/orders-api.yaml"), "openapi: 3.0.3\npaths:\n  /orders/{id}:\n    get:\n      responses:\n        \"200\": {description: ok}\n");
        c = Catalog.load();
        check("catalog: synced components load with their repository", c.components.get("orders-service") != null && c.components.get("orders-service").repo().equals("acme/orders-service"));
        check("catalog: repository -> component", Catalog.componentOfRepo("acme/orders-service").equals("orders-service") && Catalog.componentOfRepo("acme/Other").equals("other"));
        String graph = c.graphText();
        check("catalog: graph lists components, owns and operations", graph.contains("| orders-service | acme/orders-service |") && graph.contains("POST /orders/{orderId}/refunds")
                && graph.contains("message RefundRequested on refund-events") && graph.contains("GET /orders/{id}"));
    }

    private static void contracts() {
        Contracts.Spec v1 = Contracts.parse(OPENAPI);
        check("contracts: OpenAPI operations", v1.operations().keySet().equals(Set.of("POST /orders/{orderId}/refunds", "GET /refunds/{id}")));
        Contracts.Op post = v1.operations().get("POST /orders/{orderId}/refunds");
        check("contracts: $ref body, required field and header", post.requiredRequest().containsAll(Set.of("body:amount", "header:Idempotency-Key")) && post.requestEnums().get("body:reason").contains("OTHER"));
        check("contracts: AsyncAPI messages", Contracts.parse(ASYNCAPI).operations().containsKey("message RefundRequested on refund-events"));
        String v2 = OPENAPI.replace("required: [amount]", "required: [amount, reason]").replace("enum: [DAMAGED, OTHER]", "enum: [DAMAGED]")
                .replace("\"409\": {description: closed}", "\"422\": {description: closed}").replace("amount: {type: string}\n            components", "amount: {type: number}\n            components")
                .replace("  /refunds/{id}:\n    get:", "  /refunds/{id}:\n    delete:") + "";
        List<Contracts.Change> d = Contracts.diff(v1, Contracts.parse(v2));
        check("contracts: new required field is breaking", d.stream().anyMatch(x -> x.breaking() && x.detail().contains("new required body:reason")));
        check("contracts: removed enum value is breaking", d.stream().anyMatch(x -> x.breaking() && x.detail().contains("enum value OTHER removed")));
        check("contracts: removed response code is breaking", d.stream().anyMatch(x -> x.breaking() && x.detail().contains("response 409 removed")));
        check("contracts: removed and added operations", d.stream().anyMatch(x -> x.kind().equals("removed") && x.operation().equals("GET /refunds/{id}"))
                && d.stream().anyMatch(x -> x.kind().equals("added") && x.operation().equals("DELETE /refunds/{id}")));
        check("contracts: an unchanged spec has no changes", Contracts.diff(v1, Contracts.parse(OPENAPI)).isEmpty());
        check("contracts: operation names normalise", Contracts.normalize("post  /orders/{orderId}/refunds/").equals(Contracts.normalize("POST /orders/{id}/refunds")));
        check("contracts: called paths match templates", Contracts.pathMatches("/orders/{id}", "/orders/{orderId}") && Contracts.pathMatches("/orders/{id}", "/orders/42?x=1")
                && !Contracts.pathMatches("/orders/{id}", "/orders/42/lines"));
    }

    private static void scan(Path tmp) throws IOException {
        Store.writeText(tmp.resolve("src/main/resources/application.yml"), "clients:\n  orders:\n    url: http://orders-service.internal:8080\n    path: /orders\n"
                + "topics:\n  refunds: refund-events\n");
        Store.writeText(tmp.resolve("src/main/java/com/acme/pay/OrdersClient.java"), "@FeignClient(name = \"orders\", url = \"${clients.orders.url}\")\ninterface OrdersClient {\n"
                + "  @GetMapping(\"${clients.orders.path}/{id}\")\n  Order get(@PathVariable String id);\n}\n");
        Store.writeText(tmp.resolve("src/main/java/com/acme/pay/Events.java"), "class Events {\n  @KafkaListener(topics = {\"order-events\", \"${topics.refunds}\"}, groupId = \"pay\")\n  void on(String m) {}\n"
                + "  void send() { kafkaTemplate.send(\"refund-events\", m); }\n  void call() { webClient.get().uri(\"/customers/{id}\", id).retrieve(); }\n}\n");
        Store.writeText(tmp.resolve("src/test/java/com/acme/pay/FakeTest.java"), "class FakeTest { void t() { restTemplate.getForObject(\"/test-only\", String.class); } }\n");
        Catalog c = Catalog.load();
        List<CatalogScan.Edge> edges = CatalogScan.scan(tmp, "payments-service", c);
        java.util.function.Predicate<String> has = t -> edges.stream().anyMatch(e -> (e.kind() + " " + e.target()).equals(t));
        check("scan: provided operations from the API files", has.test("provides POST /orders/{orderId}/refunds") && has.test("provides message RefundRequested on refund-events"));
        check("scan: Feign call with resolved path and host", has.test("calls GET /orders/{id}") && has.test("calls host orders-service.internal"));
        check("scan: Kafka listener topics, placeholder resolved", has.test("consumes topic order-events") && has.test("consumes topic refund-events"));
        check("scan: KafkaTemplate send and WebClient call", has.test("produces topic refund-events") && has.test("calls GET /customers/{id}"));
        check("scan: test code is not scanned", !has.test("calls GET /test-only"));
        check("scan: evidence has file and line", edges.stream().anyMatch(e -> e.target().equals("GET /orders/{id}") && e.file().endsWith("OrdersClient.java") && e.line() == 3));
        check("scan: the call resolves to the providing component", "orders-service".equals(CatalogScan.provider(c, "GET /orders/{id}")) && "orders-service".equals(CatalogScan.provider(c, "host orders-service.internal")));
        check("scan: declared use is not reported", CatalogScan.undeclared(c, edges).isEmpty());
        Store.writeText(tmp.resolve("catalog-info.yaml"), CATALOG.replace("consumesApis: [orders-api]", "consumesApis: []"));
        check("scan: undeclared use is reported", CatalogScan.undeclared(Catalog.load(), edges).stream().anyMatch(x -> x.contains("uses orders-service")));
        Store.writeText(tmp.resolve("catalog-info.yaml"), CATALOG);
        Files.deleteIfExists(tmp.resolve("src/test/java/com/acme/pay/FakeTest.java"));
        check("scan: JS fetch, axios and kafkajs", jsEdges().containsAll(List.of("calls POST /orders/{}", "calls GET /refunds", "consumes topic order-events", "produces topic refund-events")));
    }

    private static List<String> jsEdges() {
        List<CatalogScan.Edge> out = new java.util.ArrayList<>();
        CatalogScan.scanCode("web", "src/api.ts", "await fetch(`${BASE}/orders/${id}`, { method: 'POST' });\nconst r = await axios.get('/refunds');\n"
                + "await consumer.subscribe({ topics: ['order-events'] });\nawait producer.send({ topic: 'refund-events', messages });\n", Map.of(), out);
        return out.stream().map(e -> e.kind() + " " + e.target()).toList();
    }

    static final String SOLUTION_MD = """
            # PROJ-50 - Solution design

            ## Requirements

            - R-01: When a paid order is less than 30 days old, the payments service shall accept a refund request.
            - R-02: When a refund is requested, the orders service shall show the refund status on the order.

            ```yaml
            # sdlc-solution
            requirements: [R-01, R-02]
            components:
              - name: payments-service
                role: provider
                change: new refund endpoint and RefundRequested event
                requirements: [R-01]
                evidence: [src/main/java/com/acme/pay/PaymentService.java:42]
              - name: orders-service
                role: consumer
                change: show refund status on the order
                requirements: [R-02]
            ruledOut:
              - {name: notification-service, reason: already notifies on any status change}
            disputed: []
            contracts:
              - {provider: payments-service, api: payments-api, add: ["POST /orders/{orderId}/refunds"], compatibility: additive}
            order: [payments-service, orders-service]
            ```
            """;

    private static void solution() {
        Solution s = Solution.parse(SOLUTION_MD);
        check("solution: parsed with requirement texts", s.components.keySet().equals(Set.of("payments-service", "orders-service")) && s.requirementText.get("R-01").startsWith("When a paid order"));
        check("solution: a valid design has no problems", s.problems(Catalog.load()).isEmpty());
        List<String> bad = Solution.parse(SOLUTION_MD.replace("requirements: [R-02]", "requirements: []").replace("disputed: []", "disputed: [ledger-service]")
                .replace("name: orders-service", "name: shipping-service").replace("compatibility: additive", "compatibility: additive, remove: [\"GET /refunds/{id}\"]")).problems(Catalog.load());
        check("solution: uncovered requirement", bad.stream().anyMatch(x -> x.contains("R-02 is covered by no component")));
        check("solution: disputed components block publishing", bad.stream().anyMatch(x -> x.contains("disputed")));
        check("solution: unknown component", bad.stream().anyMatch(x -> x.contains("shipping-service is not in the catalog")));
        check("solution: a removal must be declared breaking", bad.stream().anyMatch(x -> x.contains("breaking change")));
        check("solution: slice has the component's requirements and contracts", s.slice("orders-service").contains("R-02: When a refund") && s.slice("orders-service").contains("mayConsume:\n  - {provider: payments-service")
                && s.slice("orders-service").contains("mergeOrder: 2 of 2"));
        Solution s2 = Solution.parse(SOLUTION_MD.replace("change: new refund endpoint and RefundRequested event", "change: new refund endpoint only"));
        check("solution: a change to one component re-hashes only its slice", !s.sliceHash("payments-service").equals(s2.sliceHash("payments-service")) && s.sliceHash("orders-service").equals(s2.sliceHash("orders-service")));
        check("solution: allowed contract operations", s.allowedAdds("payments-api").equals(Set.of("POST /orders/{}/refunds")) && !s.breakingAllowed("payments-api"));
        check("solution: draft without block is refused", Asserts.throwsUsage(() -> Solution.parse("# no block")));
    }

    private static void epicFlow(Path tmp) throws IOException {
        Fakes.Tracker tracker = new Fakes.Tracker();
        Fakes.Wiki wiki = new Fakes.Wiki();
        Fakes.Recorder said = new Fakes.Recorder();
        Workflow w = new Workflow(tracker, wiki, said);
        Path draft = tmp.resolve(".sdlc/PROJ-50/drafts/solution.md");
        Store.writeText(draft, SOLUTION_MD.replace("disputed: []", "disputed: [ledger-service]"));
        check("epic: publishing refused while a component is disputed", Asserts.throwsUsage(() -> {
            try {
                w.docs.publish("PROJ-50", "solution", draft);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }));
        Store.writeText(draft, SOLUTION_MD);
        check("epic: solution design published", w.docs.publish("PROJ-50", "solution", draft) == 0 && wiki.byTitle("ARCH", "PROJ-50 - Solution design") != null);
        check("epic: stories refused before the architect approves", w.stories.create("PROJ-50") == 1 && tracker.created.isEmpty());
        String page = wiki.byTitle("ARCH", "PROJ-50 - Solution design").id();
        tracker.say("PROJ-50", Approvals.line("PROJ-50", "solution", wiki.page(page)), "dev", false);
        check("epic: only a named architect approves", !w.approvals.check("PROJ-50", "solution").approved());
        tracker.say("PROJ-50", Approvals.line("PROJ-50", "solution", wiki.page(page)), "arch", false);
        check("epic: one story per component, in merge order", w.stories.create("PROJ-50") == 0 && tracker.created.size() == 2
                && tracker.created.get(0).summary().startsWith("[payments-service]") && tracker.created.get(0).type().equals("Story")
                && tracker.parent.get(tracker.created.get(0).key()).equals("PROJ-50"));
        String payStory = tracker.created.get(0).key(), ordersStory = tracker.created.get(1).key();
        Solution s = Solution.parse(SOLUTION_MD);
        check("epic: the story carries its slice and hash", tracker.descriptions.get(payStory).contains("solutionSlice: " + s.sliceHash("payments-service")));
        check("epic: stories create again adds nothing", w.stories.create("PROJ-50") == 0 && tracker.created.size() == 2);

        check("epic: docs pull writes this repo's slice", w.docs.pull(payStory) == 0 && Files.readString(tmp.resolve(".sdlc/" + payStory + "/sources/solution-slice.md")).contains("solutionSlice: " + s.sliceHash("payments-service")));
        Proc.run(tmp, "git", "switch", "-q", "-c", "feature/" + payStory + "-refunds");
        Path lld = tmp.resolve(".sdlc/" + payStory + "/drafts/lld.md");
        Store.writeText(lld, lldText("cross-component", "000000000000"));
        w.docs.publish(payStory, "lld", lld);
        String lldPage = wiki.byTitle("DEV", payStory + " - LLD - pay").id();
        tracker.say(payStory, Approvals.line(payStory, "lld", wiki.page(lldPage)), "lead", false);
        check("lanes: an LLD from an old slice is refused", w.tasks.create(payStory) == 1 && String.join("\n", said.err).contains("solutionSlice is '000000000000'"));
        Store.writeText(lld, lldText("patch", s.sliceHash("payments-service")));
        w.docs.publish(payStory, "lld", lld);
        tracker.say(payStory, Approvals.line(payStory, "lld", wiki.page(lldPage)), "lead", false);
        check("lanes: a lane below the facts is refused", w.tasks.create(payStory) == 1 && String.join("\n", said.err).contains("Set lane: cross-component"));
        Store.writeText(lld, lldText("cross-component", s.sliceHash("payments-service")));
        w.docs.publish(payStory, "lld", lld);
        tracker.say(payStory, Approvals.line(payStory, "lld", wiki.page(lldPage)), "lead", false);
        check("lanes: the right lane and slice create tasks", w.tasks.create(payStory) == 0 && "cross-component".equals(Work.state(payStory).get("lane")));
        said.out.clear();
        check("feature: status is not ready while a component has no approved LLD", w.feature.status("PROJ-50") == 1
                && String.join("\n", said.out).contains("v3 approved") && String.join("\n", said.out).contains("no LLD yet") && String.join("\n", said.out).contains("1 of 2"));

        Store.writeText(draft, SOLUTION_MD.replace("change: new refund endpoint and RefundRequested event", "change: new refund endpoint only"));
        w.docs.publish("PROJ-50", "solution", draft);
        tracker.say("PROJ-50", Approvals.line("PROJ-50", "solution", wiki.page(page)), "arch", false);
        int before = tracker.comments.getOrDefault(ordersStory, List.of()).size();
        check("epic: a new solution version comments only on the changed story", w.stories.create("PROJ-50") == 0
                && tracker.comments.get(payStory).stream().anyMatch(c -> c.body().contains("changed this story's slice"))
                && tracker.comments.getOrDefault(ordersStory, List.of()).size() == before);
        said.out.clear();
        w.feature.status("PROJ-50");
        check("feature: an LLD from the old slice shows as outdated", String.join("\n", said.out).contains("OUTDATED"));
        Proc.run(tmp, "git", "switch", "-q", "feature/PROJ-7-refunds");

        Store.writeText(tmp.resolve(".sdlc/PROJ-60/drafts/lld.md"), lldText("patch", "").replace("src/main/java/com/acme/refund/**", "api/openapi.yaml"));
        w.docs.publish("PROJ-60", "lld", tmp.resolve(".sdlc/PROJ-60/drafts/lld.md"));
        tracker.say("PROJ-60", Approvals.line("PROJ-60", "lld", wiki.page(wiki.byTitle("DEV", "PROJ-60 - LLD - pay").id())), "lead", false);
        check("lanes: a patch whose task edits a contract file is refused", w.tasks.create("PROJ-60") == 1 && String.join("\n", said.err).contains("may edit contract file api/openapi.yaml"));
        said.out.clear();
        check("lanes: classify names the floor and why", Lanes.classify(w, "PROJ-60") == 0 && String.join("\n", said.out).contains("Lowest lane for PROJ-60: component"));
    }

    private static String lldText(String lane, String slice) {
        return "# LLD\n\n```yaml\n# sdlc-tasks\nlane: " + lane + "\n" + (slice.isEmpty() ? "" : "solutionSlice: " + slice + "\n") + "tasks:\n  - id: T1\n    title: Refund API\n"
                + "    scope: [src/main/java/com/acme/refund/**]\n    acceptance:\n      - Given a paid order, then 201\n```\n";
    }

    private static void eval(Path tmp) throws IOException {
        check("eval: components from a YAML list", Eval.components("components: [Orders-Service, payments-service]").equals(Set.of("orders-service", "payments-service")));
        check("eval: components from mappings", Eval.components("components:\n  - name: a\n    role: provider\n  - {name: b}\n").equals(Set.of("a", "b")));
        check("eval: components from a solution draft", Eval.components("# Sol\n\n```yaml\n# sdlc-solution\ncomponents:\n  - name: pay\n```\n").equals(Set.of("pay")));
        check("eval: no components in junk", Eval.components(": : [").isEmpty());
        Eval.CaseScore s = Eval.score("E-1", Set.of("a", "b"), List.of(Set.of("a", "b"), Set.of("a"), Set.of("a", "b", "c")));
        check("eval: recall averaged over runs", Math.abs(s.recall() - (1 + 0.5 + 1) / 3) < 1e-9);
        check("eval: precision averaged over runs", Math.abs(s.precision() - (1 + 1 + 2.0 / 3) / 3) < 1e-9);
        check("eval: pass@k when one run is exact, not pass^k", s.anyExact() && !s.allExact() && !s.consistent());
        Eval.CaseScore all = Eval.score("E-2", Set.of("a"), List.of(Set.of("a"), Set.of("a")));
        check("eval: pass^k and consistent when every run is exact", all.allExact() && all.consistent());
        Eval.CaseScore none = Eval.score("E-3", Set.of("a"), List.of(Set.of()));
        check("eval: an empty answer scores zero", none.recall() == 0 && none.precision() == 0);

        Store.writeText(tmp.resolve("docs/agent/eval/scoping/truth/E-9.yml"), "components: [a]\n");
        expect("eval: agents cannot read the ground truth", tool("read_file", "{\"filePath\":\"" + json(tmp) + "/docs/agent/eval/scoping/truth/E-9.yml\"}"), Decision.Kind.DENY);
        expect("eval: nor cat it", shell("cat docs/agent/eval/scoping/truth/E-9.yml"), Decision.Kind.DENY);
        expect("eval: nor by glob", shell("cat docs/agent/eval/scoping/truth/*"), Decision.Kind.DENY);
        expect("eval: nor edit it", tool("create_file", "{\"filePath\":\"" + json(tmp) + "/docs/agent/eval/scoping/truth/E-9.yml\"}"), Decision.Kind.DENY);
        expect("eval: case inputs stay readable", tool("read_file", "{\"filePath\":\"" + json(tmp) + "/docs/agent/eval/scoping/cases/E-9.md\"}"), Decision.Kind.ALLOW);
        expect("eval: hold-out folder is hidden", shell("cat .sdlc/hidden/holdout/T.java"), Decision.Kind.DENY);
        expect("eval: agents cannot score (the report shows the truth)", shell("java .agent/Sdlc.java eval score"), Decision.Kind.DENY);
        expect("eval: nor collect", shell("sh .agent/bin/sdlc eval collect E-9"), Decision.Kind.DENY);
        expect("eval: answers are agent-writable", tool("create_file", "{\"filePath\":\"" + json(tmp) + "/.sdlc/eval/inbox/run1/E-9.yml\"}"), Decision.Kind.ALLOW);
    }
}
