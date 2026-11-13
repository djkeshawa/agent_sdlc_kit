# The sdlc tool as a container image: a jlink Java runtime, the tool's jar, git, and nothing else.
# Build from the repository root:
#   docker build -f packaging/Dockerfile -t sdlc .
# Run against a repository (read-only is enough for the checks):
#   docker run --rm -v "$PWD:/repo:ro" sdlc status
#
# The base images are tags today; CI pins them (and this image) by digest once it is the verifier.

# --- build: compile the tool and link a runtime with the same script as the local bundles -------
FROM eclipse-temurin:21-jdk-alpine AS build
ARG SDLC_VERSION=unknown
COPY template/.agent/src /kit/template/.agent/src
COPY packaging /kit/packaging
# The same modules as the bundles. java.desktop (19 MB) only draws the mind map PNG, but `selftest`
# loads the MindMap class, whose static colours are java.awt.Color, so it cannot go yet.
RUN SDLC_VERSION="$SDLC_VERSION" DIST=/dist sh /kit/packaging/build.sh \
 && rm /dist/*.tar.gz && mv /dist/sdlc-linux-* /dist/sdlc \
 && rm -f /dist/sdlc/README.md

# --- final: Alpine, git, the bundle -------------------------------------------------------------
FROM alpine:3.22
# The repository is mounted from the host and owned by another uid; without safe.directory git
# refuses to read it, and the tool would see no repository at all.
RUN apk add --no-cache git \
 && git config --system safe.directory '*' \
 && adduser -D -H -u 10001 -s /sbin/nologin sdlc
COPY --from=build /dist/sdlc /opt/sdlc
RUN ln -s /opt/sdlc/bin/sdlc /usr/local/bin/sdlc
LABEL org.opencontainers.image.title="sdlc" \
      org.opencontainers.image.description="The agentic SDLC kit's sdlc tool with its own Java runtime" \
      org.opencontainers.image.licenses="Apache-2.0"
USER 10001:10001
ENV HOME=/tmp
WORKDIR /repo
ENTRYPOINT ["sdlc"]
