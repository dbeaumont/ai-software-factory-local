# Ephemeral execution image used for patch validation, builds, tests, SBOM generation and vulnerability scans.
FROM maven:3.9.16-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753

# Tool versions are explicit so sandbox evidence remains reproducible.
ARG TRIVY_VERSION
ARG SYFT_VERSION
ARG NODE_VERSION
# Trivy normally downloads its database during a scan; set this only when the build environment can preload it.
ARG TRIVY_PRELOAD_DB

# Install build utilities, then add Syft and Trivy for the software bill of materials and security stages.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl jq ca-certificates patch xz-utils python3 \
    && rm -rf /var/lib/apt/lists/* \
    && ARCH="$(dpkg --print-architecture)" \
    && case "$ARCH" in amd64) NODE_ARCH=x64 ;; arm64) NODE_ARCH=arm64 ;; *) echo "Unsupported arch $ARCH"; exit 1 ;; esac \
    && curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" \
    && curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt" \
    && grep " node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz$" SHASUMS256.txt | sha256sum -c - \
    && tar -xJf "node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" -C /usr/local --strip-components=1 --no-same-owner \
    && rm "node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" SHASUMS256.txt \
    && curl -fsSL "https://github.com/anchore/syft/releases/download/v${SYFT_VERSION}/syft_${SYFT_VERSION}_linux_$(dpkg --print-architecture).tar.gz" \
       | tar -xz -C /usr/local/bin syft \
    && ARCH="$(dpkg --print-architecture)" \
    && case "$ARCH" in amd64) TVARCH=64bit ;; arm64) TVARCH=ARM64 ;; *) echo "Unsupported arch $ARCH"; exit 1 ;; esac \
    && curl -fsSL "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-${TVARCH}.tar.gz" \
       | tar -xz -C /usr/local/bin trivy \
    && if [ "$TRIVY_PRELOAD_DB" = "true" ]; then trivy image --download-db-only; fi \
    && node --version \
    && npm --version

# All Maven projects executed in the sandbox are redirected to the local Artifactory virtual repository.
COPY maven-settings.xml /opt/ai-factory/maven-settings.xml
COPY maven-settings-public.xml /opt/ai-factory/maven-settings-public.xml
COPY gradle-mirror.init.gradle /opt/ai-factory/gradle-mirror.init.gradle
COPY runner.py /opt/ai-factory/runner.py

# The orchestrator mounts the checked-out task repository at this working directory.
WORKDIR /workspace
RUN useradd --system --uid 10000 --create-home sandbox \
    && mkdir -p /home/sandbox/.m2 /home/sandbox/.cache/trivy/db \
    && chown -R 10000:10000 /home/sandbox /opt/ai-factory
ENV HOME=/home/sandbox
USER 10000
CMD ["bash"]
