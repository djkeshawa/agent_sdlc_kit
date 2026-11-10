package sdlc.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import sdlc.core.Ctx;
import sdlc.core.Locks;
import sdlc.core.Store;
import sdlc.core.UsageException;
import sdlc.core.Work;
import sdlc.util.Glob;
import sdlc.util.Json;
import sdlc.util.Proc;

/**
 * sdlc tests lock <STORY> [--paths glob,glob] (a QE person runs it; agents are refused):
 * copies the acceptance tests the qa-verifier drafted in .sdlc/<STORY>/drafts/acceptance/
 * (mirroring repository paths) into the repository, and/or takes existing test files matching
 * --paths, and records their hashes in docs/test-locks/<STORY>.json. sdlc tests status shows
 * every lock and any locked file that changed.
 */
public final class TestLocks {
    private TestLocks() {}

    public static int lock(String story, String paths) throws IOException {
        Path draft = Work.storyDir(story).resolve("drafts").resolve("acceptance");
        List<String> tests = Ctx.cfg.list("paths.test");
        Set<String> files = new LinkedHashSet<>();
        if (Files.isDirectory(draft)) {
            try (Stream<Path> s = Files.walk(draft)) {
                for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = draft.relativize(f).toString().replace('\\', '/');
                    if (!Glob.any(tests, rel)) throw new UsageException("drafted file " + rel + " is not test code (paths.test): acceptance tests only");
                    Path to = Ctx.root.resolve(rel);
                    Files.createDirectories(to.getParent());
                    Files.copy(f, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    files.add(rel);
                }
            }
        }
        if (!paths.isBlank()) {
            List<String> globs = List.of(paths.split(","));
            try (Stream<Path> s = Files.walk(Ctx.root)) {
                for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = Ctx.root.relativize(f).toString().replace('\\', '/');
                    if (rel.startsWith(".git/") || rel.startsWith(".sdlc/")) continue;
                    if (Glob.any(globs, rel)) {
                        if (!Glob.any(tests, rel)) throw new UsageException(rel + " is not test code (paths.test): only tests are locked");
                        files.add(rel);
                    }
                }
            }
        }
        Path lockFile = Ctx.root.resolve(Locks.DIR).resolve(story + ".json");
        Map<String, Object> old = Store.read(lockFile);
        for (String f : Json.map(old.get("files")).keySet()) if (Files.isRegularFile(Ctx.root.resolve(f))) files.add(f); // re-locking keeps the earlier files
        if (files.isEmpty()) throw new UsageException("nothing to lock: no files in " + Ctx.rel(draft) + " and no --paths given");
        Map<String, Object> hashes = new TreeMap<>();
        for (String f : files) hashes.put(f, Locks.hash(Files.readAllBytes(Ctx.root.resolve(f))));
        String who = Proc.run(Ctx.root, "git", "config", "user.name").out().strip();
        Store.write(lockFile, Store.map("story", story, "lockedBy", who, "at", Instant.now().toString(), "files", hashes));
        System.out.println("Locked " + hashes.size() + " acceptance test file(s) of " + story + " in " + Ctx.rel(lockFile) + ":");
        hashes.keySet().forEach(f -> System.out.println("  " + f));
        System.out.println("Commit them with the lock file. Agents can no longer change them; the code must pass them.");
        return 0;
    }

    public static int status(String story) {
        Map<String, Locks.Entry> all = Locks.locked(Ctx.root);
        List<String> broken = Locks.broken(Ctx.root);
        int n = 0;
        for (Locks.Entry e : all.values()) {
            if (!story.isBlank() && !story.equals(e.story())) continue;
            n++;
            System.out.println(e.story() + "  " + e.path());
        }
        if (n == 0) System.out.println("No locked acceptance tests" + (story.isBlank() ? "" : " for " + story) + ".");
        List<String> mine = new ArrayList<>();
        for (String b : broken) if (story.isBlank() || b.contains("(" + story + ")")) mine.add(b);
        mine.forEach(b -> System.out.println("BROKEN " + b));
        return mine.isEmpty() ? 0 : 1;
    }
}
