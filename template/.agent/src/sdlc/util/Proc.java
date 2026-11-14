package sdlc.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs child processes. */
public record Proc(int code, String out, String err) {
    public static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");

    /** Variables added to the environment of every child process of this run. */
    public static final java.util.Map<String, String> ENV = new java.util.concurrent.ConcurrentHashMap<>();

    public static Proc run(Path dir, String... cmd) {
        return runWithInput(dir, null, 120, cmd);
    }

    public static Proc runWithInput(Path dir, String stdin, int timeoutSeconds, String... cmd) {
        return runWithEnv(dir, java.util.Map.of(), stdin, timeoutSeconds, cmd);
    }

    /** As runWithInput, with environment variables added to (an empty value removes one from) the child's environment. */
    public static Proc runWithEnv(Path dir, java.util.Map<String, String> env, String stdin, int timeoutSeconds, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
            pb.environment().putAll(ENV);
            env.forEach((k, v) -> {
                if (v == null || v.isEmpty()) pb.environment().remove(k);
                else pb.environment().put(k, v);
            });
            Process p = pb.start();
            // stdin on its own thread, so a large output cannot block the input
            Thread writer = new Thread(() -> {
                try (OutputStream o = p.getOutputStream()) {
                    if (stdin != null) o.write(stdin.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the process closed its input early
                }
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread readErr = new Thread(() -> copy(p.getErrorStream(), err));
            Thread readOut = new Thread(() -> copy(p.getInputStream(), out)); // on its own thread: reading here would wait for ever on a process that hangs
            for (Thread t : List.of(writer, readErr, readOut)) t.setDaemon(true);
            writer.start();
            readErr.start();
            readOut.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new Proc(124, out.toString(StandardCharsets.UTF_8), "timeout after " + timeoutSeconds + " s");
            }
            // a background child of the command may keep the pipes open after it exits: do not wait for it for ever
            readOut.join(5000);
            readErr.join(5000);
            writer.join(5000);
            return new Proc(p.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Proc(127, "", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Proc(130, "", "interrupted");
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException ignored) {
            // the process ended
        }
    }

    /** Runs a command line through the platform shell with the console attached. */
    public static int shell(Path dir, String line) {
        return inherit(dir, WINDOWS ? List.of("cmd", "/c", line) : List.of("sh", "-c", line));
    }

    static int inherit(Path dir, List<String> cmd) {
        try {
            return new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            System.err.println("cannot run " + cmd + ": " + e.getMessage());
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    public boolean ok() {
        return code == 0;
    }
}
