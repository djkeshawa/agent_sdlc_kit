import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class Sdlc {
    public static void main(String[] args) throws Exception {
        Path agent = agentDir();
        Path src = agent.resolve("src");
        Path build = agent.resolve(".build");
        if (stale(src, build) && !compile(src, build)) System.exit(3);
        System.setProperty("sdlc.bootstrapped", "true");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {build.toUri().toURL()}, Sdlc.class.getClassLoader())) {
            Class<?> main = loader.loadClass("sdlc.Main");
            main.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    static Path agentDir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(".agent/src/sdlc"))) return p.resolve(".agent");
        }
        throw new IllegalStateException("run this from inside the repository: java .agent/Sdlc.java <command>");
    }

    static boolean stale(Path src, Path build) throws IOException {
        Path stamp = build.resolve(".stamp");
        return !Files.exists(stamp) || !Files.readString(stamp).trim().equals(fingerprint(src));
    }

    // Content, not timestamps: cp -p, rsync -a and archives keep old timestamps.
    // sdlc.Main.fingerprint must compute the same value.
    static String fingerprint(Path src) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (Stream<Path> files = Files.walk(src)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    md.update(src.relativize(p).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update(Files.readAllBytes(p));
                    md.update((byte) 0);
                }
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean compile(Path src, Path build) throws IOException {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("This Java has no compiler. Install a JDK (not a JRE), version 17 or newer.");
            return false;
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(src)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString()));
        }
        Path home = build.toAbsolutePath().getParent();
        if (home == null) throw new IOException("no folder to build in: " + build);
        Path tmp = Files.createTempDirectory(home, ".build-tmp");
        List<String> argv = new ArrayList<>(List.of("-nowarn", "-encoding", "UTF-8", "-d", tmp.toString()));
        argv.addAll(files);
        int rc = compiler.run(null, OutputStream.nullOutputStream(), System.err, argv.toArray(new String[0]));
        if (rc != 0) {
            System.err.println("Compiling .agent/src failed (see above).");
            delete(tmp);
            return false;
        }
        Files.createDirectories(build);
        List<Path> fresh = new ArrayList<>();
        try (Stream<Path> s = Files.walk(tmp)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                Path dest = build.resolve(tmp.relativize(p).toString());
                Path into = dest.getParent();
                if (into != null) Files.createDirectories(into);
                Files.move(p, dest, StandardCopyOption.REPLACE_EXISTING);
                fresh.add(dest);
            }
        }
        try (Stream<Path> s = Files.walk(build)) { // classes of sources that were deleted or moved must not stay loadable
            for (Path old : s.filter(p -> p.toString().endsWith(".class") && !fresh.contains(p)).toList()) Files.deleteIfExists(old);
        }
        delete(tmp);
        Files.writeString(build.resolve(".stamp"), fingerprint(src));
        return true;
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
