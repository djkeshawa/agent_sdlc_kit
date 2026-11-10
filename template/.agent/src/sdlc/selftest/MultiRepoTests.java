package sdlc.selftest;

import static sdlc.selftest.Asserts.check;
import static sdlc.selftest.Asserts.expect;
import static sdlc.selftest.Asserts.json;
import static sdlc.selftest.Asserts.shell;
import static sdlc.selftest.Asserts.tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import sdlc.core.Store;
import sdlc.guard.Decision;
import sdlc.workflow.Eval;

/** Self test: the multi-component additions (scoping eval, catalog, solution design, contracts, locks). */
final class MultiRepoTests {
    private MultiRepoTests() {}

    static void run(Path tmp) throws IOException {
        eval(tmp);
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
        expect("eval: answers are agent-writable", tool("create_file", "{\"filePath\":\"" + json(tmp) + "/.sdlc/eval/inbox/run1/E-9.yml\"}"), Decision.Kind.ALLOW);
    }
}
