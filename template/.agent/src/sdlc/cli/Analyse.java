package sdlc.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import sdlc.core.Ctx;
import sdlc.core.Git;
import sdlc.core.Store;
import sdlc.util.Glob;
import sdlc.util.Proc;
import sdlc.workflow.Checks;

/**
 * Deterministic facts about the repository for the analyst agent: stack, modules, dependencies,
 * frameworks, file and naming statistics, code idioms, lint and CI setup. The agent reads the
 * facts, samples representative files and writes docs/agent/project-profile.md.
 */
public final class Analyse {
    private static final Map<String, String> MARKERS = new LinkedHashMap<>();

    static {
        // dependency/import marker -> what it means
        MARKERS.put("spring-boot", "Spring Boot");
        MARKERS.put("spring-webflux", "Spring WebFlux (reactive)");
        MARKERS.put("quarkus", "Quarkus");
        MARKERS.put("micronaut", "Micronaut");
        MARKERS.put("lombok", "Lombok");
        MARKERS.put("mapstruct", "MapStruct");
        MARKERS.put("hibernate", "Hibernate/JPA");
        MARKERS.put("spring-boot-starter-data-jpa", "Spring Data JPA");
        MARKERS.put("mybatis", "MyBatis");
        MARKERS.put("flyway", "Flyway migrations");
        MARKERS.put("liquibase", "Liquibase migrations");
        MARKERS.put("kafka", "Kafka");
        MARKERS.put("rabbit", "RabbitMQ");
        MARKERS.put("junit-jupiter", "JUnit 5");
        MARKERS.put("junit:junit", "JUnit 4");
        MARKERS.put("mockito", "Mockito");
        MARKERS.put("assertj", "AssertJ");
        MARKERS.put("testcontainers", "Testcontainers");
        MARKERS.put("wiremock", "WireMock");
        MARKERS.put("archunit", "ArchUnit");
        MARKERS.put("resilience4j", "Resilience4j");
        MARKERS.put("springdoc", "springdoc OpenAPI");
        MARKERS.put("openapi-generator", "OpenAPI generator (contract first)");
        MARKERS.put("\"react\"", "React");
        MARKERS.put("\"next\"", "Next.js");
        MARKERS.put("\"@angular/core\"", "Angular");
        MARKERS.put("\"vue\"", "Vue");
        MARKERS.put("\"express\"", "Express");
        MARKERS.put("\"@nestjs/core\"", "NestJS");
        MARKERS.put("\"typescript\"", "TypeScript");
        MARKERS.put("\"jest\"", "Jest");
        MARKERS.put("\"vitest\"", "Vitest");
        MARKERS.put("\"mocha\"", "Mocha");
        MARKERS.put("\"@playwright/test\"", "Playwright");
        MARKERS.put("\"cypress\"", "Cypress");
        MARKERS.put("\"@testing-library/react\"", "Testing Library");
        MARKERS.put("\"eslint\"", "ESLint");
        MARKERS.put("\"prettier\"", "Prettier");
        MARKERS.put("\"zod\"", "zod validation");
        MARKERS.put("\"redux\"", "Redux");
        MARKERS.put("\"@reduxjs/toolkit\"", "Redux Toolkit");
        MARKERS.put("\"axios\"", "axios");
        MARKERS.put("\"fastify\"", "Fastify");
        MARKERS.put("\"koa\"", "Koa");
        MARKERS.put("\"@prisma/client\"", "Prisma");
        MARKERS.put("\"typeorm\"", "TypeORM");
        MARKERS.put("\"sequelize\"", "Sequelize");
        MARKERS.put("\"mongoose\"", "Mongoose");
        MARKERS.put("\"knex\"", "Knex");
        MARKERS.put("\"drizzle-orm\"", "Drizzle ORM");
        MARKERS.put("\"pino\"", "pino logging");
        MARKERS.put("\"winston\"", "winston logging");
        MARKERS.put("\"supertest\"", "Supertest");
        MARKERS.put("\"msw\"", "MSW (mocked HTTP)");
        MARKERS.put("\"@tanstack/react-query\"", "TanStack Query");
        MARKERS.put("\"svelte\"", "Svelte");
        MARKERS.put("\"type\": \"module\"", "ES modules (\"type\": \"module\")");
    }

    private static final Map<String, Pattern> IDIOMS = new LinkedHashMap<>();

    static {
        IDIOMS.put("@RestController", Pattern.compile("@RestController\\b"));
        IDIOMS.put("@Service", Pattern.compile("@Service\\b"));
        IDIOMS.put("@Repository / JpaRepository", Pattern.compile("@Repository\\b|extends (Jpa|Crud|PagingAndSorting)Repository"));
        IDIOMS.put("@Transactional", Pattern.compile("@Transactional\\b"));
        IDIOMS.put("field @Autowired", Pattern.compile("@Autowired\\s+(private|protected|public)?\\s*[A-Z]\\w*(<[^>]*>)?\\s+\\w+\\s*;"));
        IDIOMS.put("@RequiredArgsConstructor (Lombok ctor injection)", Pattern.compile("@RequiredArgsConstructor\\b"));
        IDIOMS.put("@Slf4j / LoggerFactory", Pattern.compile("@Slf4j\\b|LoggerFactory\\.getLogger"));
        IDIOMS.put("@ControllerAdvice", Pattern.compile("@(Rest)?ControllerAdvice\\b"));
        IDIOMS.put("record types", Pattern.compile("\\brecord\\s+[A-Z]\\w*\\s*\\("));
        IDIOMS.put("Optional", Pattern.compile("\\bOptional<"));
        IDIOMS.put("@Valid", Pattern.compile("@Valid(ated)?\\b"));
        IDIOMS.put("@Mapper (MapStruct)", Pattern.compile("@Mapper\\b"));
        IDIOMS.put("@Builder", Pattern.compile("@Builder\\b"));
        IDIOMS.put("System.out", Pattern.compile("System\\.(out|err)\\.print"));
        IDIOMS.put("async/await", Pattern.compile("\\bawait\\b"));
        IDIOMS.put("console.log", Pattern.compile("console\\.log\\("));
        IDIOMS.put("React function components", Pattern.compile("export (default )?function [A-Z]\\w*\\(|const [A-Z]\\w* = \\([^)]*\\) =>"));
        IDIOMS.put("React hooks (useX)", Pattern.compile("\\buse[A-Z]\\w*\\("));
        IDIOMS.put("any type", Pattern.compile(":\\s*any\\b"));
        IDIOMS.put("ESM import", Pattern.compile("^import .+ from ['\"]", Pattern.MULTILINE));
        IDIOMS.put("CommonJS require", Pattern.compile("\\brequire\\(['\"]"));
        IDIOMS.put("Express/router routes", Pattern.compile("\\b(app|router)\\.(get|post|put|patch|delete)\\("));
        IDIOMS.put("NestJS @Controller/@Injectable", Pattern.compile("@(Controller|Injectable)\\("));
        IDIOMS.put("classes", Pattern.compile("^export (default )?class ", Pattern.MULTILINE));
        IDIOMS.put("default exports", Pattern.compile("^export default ", Pattern.MULTILINE));
        IDIOMS.put("promise .then chains", Pattern.compile("\\.then\\("));
        IDIOMS.put("zod schemas", Pattern.compile("\\bz\\.object\\("));
        IDIOMS.put("process.env reads", Pattern.compile("process\\.env\\."));
        IDIOMS.put("@ts-ignore / @ts-expect-error", Pattern.compile("@ts-(ignore|expect-error)"));
    }

    private Analyse() {}

    public static int facts() throws IOException {
        // the project's own files: the kit (.agent, .github agents, ...) and local state would skew the facts
        List<String> files = Git.lines(Proc.run(Ctx.root, "git", "ls-files").out()).stream().filter(f -> !Glob.any(Ctx.cfg.list("paths.protected"), f)).toList();
        StringBuilder sb = new StringBuilder("# Repository facts\n\nGenerated by `java .agent/Sdlc.java analyse` at commit " + Git.shortHead() + ". Facts only; the analyst agent turns them into docs/agent/project-profile.md.\n\n");
        sb.append("## Stack\n\n- Configured stack: ").append(Ctx.cfg.stack()).append('\n');
        for (String marker : List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "package.json", "tsconfig.json", "angular.json", "nx.json", "lerna.json", "pnpm-workspace.yaml", "Jenkinsfile", "Dockerfile", "docker-compose.yml", "sonar-project.properties", ".editorconfig"))
            if (files.contains(marker)) sb.append("- Has `").append(marker).append("`\n");
        sb.append("\n## Size\n\n- Tracked files: ").append(files.size()).append('\n');
        Map<String, Long> byExt = files.stream().collect(Collectors.groupingBy(f -> Checks.ext(f).isEmpty() ? "(none)" : Checks.ext(f), TreeMap::new, Collectors.counting()));
        sb.append("- By extension: ").append(top(byExt, 15)).append('\n');
        Map<String, Long> byTop = files.stream().collect(Collectors.groupingBy(f -> f.contains("/") ? f.substring(0, f.indexOf('/')) : "(root)", TreeMap::new, Collectors.counting()));
        sb.append("- By top-level folder: ").append(top(byTop, 15)).append('\n');
        long tests = files.stream().filter(f -> Glob.any(Ctx.cfg.list("paths.test"), f)).count();
        long main = files.stream().filter(Checks::isMainCode).count();
        sb.append("- Main code files: ").append(main).append(", test files: ").append(tests).append('\n');

        sb.append("\n## Modules\n\n");
        for (String f : files) {
            String name = f.substring(f.lastIndexOf('/') + 1);
            if (name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts") || name.equals("package.json") && !f.contains("node_modules")) sb.append("- `").append(f).append("`\n");
        }
        sb.append("\n## Frameworks and libraries detected\n\n");
        StringBuilder build = new StringBuilder();
        for (String f : files) {
            String name = f.substring(f.lastIndexOf('/') + 1);
            if (name.equals("pom.xml") || name.startsWith("build.gradle") || name.equals("package.json")) build.append(read(f));
        }
        String b = build.toString();
        for (Map.Entry<String, String> m : MARKERS.entrySet()) if (b.contains(m.getKey())) sb.append("- ").append(m.getValue()).append('\n');
        Matcher jv = Pattern.compile("<(java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.source)>([^<]+)<").matcher(b);
        if (jv.find()) sb.append("- Java version: ").append(jv.group(2)).append('\n');
        Matcher sbv = Pattern.compile("spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)<").matcher(b);
        if (sbv.find()) sb.append("- Spring Boot version: ").append(sbv.group(1)).append('\n');

        sb.append("\n## Naming (class / file name suffixes)\n\n");
        Map<String, Long> suffix = new TreeMap<>();
        Pattern sfx = Pattern.compile("([A-Z][a-z]+)\\.(java|kt|ts|tsx)$");
        for (String f : files) {
            Matcher m = sfx.matcher(f);
            if (m.find() && Checks.isMainCode(f)) suffix.merge(m.group(1), 1L, Long::sum);
        }
        Pattern jsSfx = Pattern.compile("[.-]([a-z]+)\\.(ts|tsx|js|jsx|mjs|cjs)$");
        for (String f : files) {
            Matcher m = jsSfx.matcher(f);
            if (m.find() && Checks.isMainCode(f) && !f.matches(".*\\.(test|spec)\\.[^/]+$")) suffix.merge("*" + f.charAt(m.start()) + m.group(1), 1L, Long::sum);
        }
        sb.append("- Main code: ").append(top(suffix, 20)).append('\n');
        Map<String, Long> testSuffix = new TreeMap<>();
        for (String f : files) {
            if (!Glob.any(Ctx.cfg.list("paths.test"), f)) continue;
            String n = f.substring(f.lastIndexOf('/') + 1);
            String kind = n.matches(".*IT\\.(java|kt)$") ? "*IT" : n.matches(".*Test\\.(java|kt)$") ? "*Test" : n.matches(".*Tests\\.(java|kt)$") ? "*Tests" : n.matches(".*\\.test\\.[jt]sx?$") ? "*.test.*" : n.matches(".*\\.spec\\.[jt]sx?$") ? "*.spec.*" : "other";
            testSuffix.merge(kind, 1L, Long::sum);
        }
        sb.append("- Test files: ").append(testSuffix).append('\n');

        sb.append("\n## Code idioms (occurrences in main code, sample of up to 400 files)\n\n");
        List<String> sample = files.stream().filter(Checks::isMainCode).limit(400).toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> example = new LinkedHashMap<>();
        for (String f : sample) {
            String text = read(f);
            for (Map.Entry<String, Pattern> e : IDIOMS.entrySet()) {
                Matcher m = e.getValue().matcher(text);
                int n = 0;
                while (m.find()) n++;
                if (n > 0) {
                    counts.merge(e.getKey(), n, Integer::sum);
                    example.putIfAbsent(e.getKey(), f);
                }
            }
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append(" (e.g. `").append(example.get(e.getKey())).append("`)\n");

        sb.append("\n## Largest packages / folders of main code\n\n");
        Map<String, Long> pkg = files.stream().filter(Checks::isMainCode).collect(Collectors.groupingBy(f -> f.contains("/") ? f.substring(0, f.lastIndexOf('/')) : "", TreeMap::new, Collectors.counting()));
        pkg.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(15).forEach(e -> sb.append("- `").append(e.getKey()).append("`: ").append(e.getValue()).append(" files\n"));

        sb.append("\n## Quality tooling\n\n");
        for (String f : files) {
            String n = f.substring(f.lastIndexOf('/') + 1).toLowerCase();
            if (n.matches("(checkstyle.*\\.xml|spotbugs.*\\.xml|pmd.*\\.xml|\\.eslintrc.*|eslint\\.config\\..*|\\.prettierrc.*|tsconfig.*\\.json|jest\\.config\\..*|vitest\\.config\\..*|\\.editorconfig|sonar-project\\.properties|lombok\\.config)")) sb.append("- `").append(f).append("`\n");
        }
        for (String t : List.of("spotless", "jacoco", "checkstyle", "spotbugs", "pmd", "enforcer", "failsafe")) if (b.contains(t)) sb.append("- build uses ").append(t).append('\n');
        if (files.contains("Jenkinsfile")) {
            Matcher st = Pattern.compile("stage\\s*\\(\\s*['\"]([^'\"]+)['\"]").matcher(read("Jenkinsfile"));
            List<String> stages = new ArrayList<>();
            while (st.find()) stages.add(st.group(1));
            sb.append("- Jenkins stages: ").append(stages).append('\n');
        }
        sb.append("\n## Docs in the repository\n\n");
        files.stream().filter(f -> f.toLowerCase().endsWith(".md") && !f.startsWith(".github/") && !f.startsWith(".agent/")).limit(30).forEach(f -> sb.append("- `").append(f).append("`\n"));

        Path out = Ctx.work("analysis", "facts.md");
        Store.writeText(out, sb.toString());
        System.out.println(sb);
        System.out.println("Saved: " + Ctx.rel(out) + "\nNext: the analyst agent reads it, samples files, and writes " + Ctx.cfg.str("analyse.output") + " from .agent/templates/project-profile.md");
        return 0;
    }

    private static String top(Map<String, Long> m, int n) {
        return m.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(n).map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "));
    }

    private static String read(String rel) {
        try {
            Path p = Ctx.root.resolve(rel);
            return Files.size(p) > 2_000_000 ? "" : Files.readString(p);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
