package sdlc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs child processes. */
record Proc(int code, String out, String err) {

    static Proc run(Path dir, String... cmd) {
        return runWithInput(dir, null, 120, cmd);
    }

    static Proc runWithInput(Path dir, String stdin, int timeoutSeconds, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).start();
            // stdin on its own thread, so a large output cannot block the input
            Thread writer = new Thread(() -> {
                try (OutputStream o = p.getOutputStream()) {
                    if (stdin != null) o.write(stdin.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the process closed its input early
                }
            });
            writer.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t = new Thread(() -> copy(p.getErrorStream(), err));
            t.start();
            copy(p.getInputStream(), out);
            writer.join();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Proc(124, out.toString(StandardCharsets.UTF_8), "timeout");
            }
            t.join();
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
    static int shell(Path dir, String line) {
        return inherit(dir, Ctx.WINDOWS ? List.of("cmd", "/c", line) : List.of("sh", "-c", line));
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

    boolean ok() {
        return code == 0;
    }
}
