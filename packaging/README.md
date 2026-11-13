# Packaging: the sdlc tool without a JDK

Two ways to run the tool with no Java installed and nothing copied into your repository:

- **a bundle** for your machine (`sdlc-linux-x64`, `sdlc-macos-arm64`): the tool, its own Java
  runtime, and a launcher;
- **a container image**: the same tool and runtime with `git`, for CI.

Both run against the git repository of the current folder.

## Run a bundle

Unpack it anywhere and put `bin` on your PATH (or link the launcher):

```sh
tar -xzf sdlc-linux-x64.tar.gz -C ~/.local/opt
ln -s ~/.local/opt/sdlc-linux-x64/bin/sdlc ~/.local/bin/sdlc
cd your-repository
sdlc status
sdlc selftest
```

You need `git` on the PATH. On macOS, a bundle downloaded with a browser is quarantined; clear
that once with `xattr -dr com.apple.quarantine sdlc-macos-arm64`.

## Run the image

```sh
docker run --rm -v "$PWD:/repo:ro" sdlc status
```

The image runs as a non-root user (uid 10001) in `/repo`. A read-only mount is enough for the
checks; commands that write `.sdlc/` (for example `analyse`, `verify`) need a writable mount.
Tokens go in as environment variables (`-e JIRA_TOKEN`), never baked into the image.

## Build

You need a JDK 21 or newer (`javac`, `jar`, `jlink`) for the platform you build for. Nothing is
downloaded.

```sh
packaging/build.sh                      # -> dist/sdlc-<os>-<arch>/ and dist/sdlc-<os>-<arch>.tar.gz
packaging/smoke.sh                      # bundle with no java on the PATH; image too if Docker runs
docker build -f packaging/Dockerfile -t sdlc .
```

`build.sh` makes a bundle for the machine it runs on only; jlink cannot build a runtime for
another operating system from here. CI (`.github/workflows/package.yml`) builds Linux x64 on
Ubuntu and macOS arm64 on a macOS runner, builds the image, runs the smoke test, and keeps the
bundles as workflow artifacts. It publishes nothing.

## What is in it

| | bundle | image |
|---|---|---|
| `bin/sdlc` launcher (POSIX sh) | yes | yes, on the PATH |
| `lib/sdlc.jar`, compiled from `template/.agent/src` for Java 17+ | yes | yes |
| Java runtime from `jlink`, with a class-data-sharing archive for faster start | yes | yes (Alpine, musl) |
| Modules `java.base`, `java.net.http`, `jdk.crypto.ec` | yes | yes |
| Module `java.desktop` (`sdlc mindmap` draws its PNG with it; `selftest` loads it) | yes | yes, but no fonts: `mindmap` fails in the image |
| `git` | no, use yours | yes |

Not in it: a compiler, a JDK, `jshell`, `java.logging`, the other JDK modules, man pages, and the
kit's templates, agent definitions and hooks (`template/.agent/templates`, `.github/agents`,
`.githooks`). The tool's own `selftest` needs none of them.

`jdk.crypto.ec` is not something the code imports, so `jdeps` does not list it. It provides the
elliptic-curve key exchange most HTTPS servers insist on; without it the TLS handshake fails.

Sizes from a Linux x64 build: bundle 72 MB unpacked, 39 MB as `.tar.gz`; image 92 MB
unpacked, 52 MB compressed (Alpine 9 MB, git 13 MB, the bundle 71 MB). Without `java.desktop`
the image would be 73 MB unpacked, 37 MB compressed.

## Limits today

The tool was written to live in `.agent/` of the repository it checks. Packaged, it uses the
built-in defaults when the repository has no `.agent/sdlc.yml` (stack `java-maven` unless set),
and `setup` and the IDE and git hooks still expect `.agent/`. `Sdlc.java` and `.agent/bin/sdlc`
are not used by the packaged tool.
