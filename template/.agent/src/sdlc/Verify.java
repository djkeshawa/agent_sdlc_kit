package sdlc;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Runs the full local build and remembers that it passed for exactly these files. */
final class Verify {
    private static final List<String> IGNORE = List.of(".sdlc/**", ".agent/.build/**", "**/target/**", "**/build/**", "**/.gradle/**", "**/out/**",
            "**/node_modules/**", "**/dist/**", "**/coverage/**", "**/.next/**", ".idea/**", "**/*.log", "**/*.iml");

    private Verify() {}

    static int run() {
        String cmd = Ctx.cfg.verifyCommand();
        if (cmd.isBlank()) throw new Main.UsageException("build.verify is not set in .agent/sdlc.yml");
        String before = fingerprint();
        System.out.println("[sdlc] running: " + cmd);
        int code = Proc.shell(Ctx.root, cmd);
        if (code == 0) {
            if (!fingerprint().equals(before)) {
                System.err.println("[sdlc] files changed while the build ran; run verify again.");
                return 1;
            }
            Store.write(Ctx.work("verify.json"), Store.map("fingerprint", before, "at", Instant.now().toString(), "commit", Git.head(), "command", cmd));
            System.out.println("[sdlc] VERIFY PASSED for the current files.");
        } else {
            System.err.println("[sdlc] VERIFY FAILED (exit " + code + "). Fix the failures above.");
        }
        Store.log("verify", Store.map("exit", code, "commit", Git.shortHead(), "story", Work.currentStory()));
        return code;
    }

    static boolean current() {
        return fingerprint().equals(String.valueOf(Store.read(Ctx.work("verify.json")).get("fingerprint")));
    }

    /** Hash of every tracked and untracked (not ignored) file, build output excluded. */
    static String fingerprint() {
        List<String> ignore = new ArrayList<>(IGNORE);
        ignore.addAll(Ctx.cfg.list("verify.ignore"));
        List<String> files = new ArrayList<>();
        for (String f : Proc.run(Ctx.root, "git", "ls-files", "-co", "--exclude-standard", "-z").out().split("\0")) {
            if (f.isEmpty() || Glob.any(ignore, f)) continue;
            if (Files.isRegularFile(Ctx.root.resolve(f))) files.add(f);
        }
        files.sort(String::compareTo);
        String hashes = files.isEmpty() ? "" : Proc.runWithInput(Ctx.root, String.join("\n", files) + "\n", 120, "git", "hash-object", "--stdin-paths").out();
        return sha256(String.join("\n", files) + "\n" + hashes);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> last() {
        return Store.read(Ctx.work("verify.json"));
    }
}
