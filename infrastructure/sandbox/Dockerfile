# Ephemeral execution image used for patch validation, builds, tests, SBOM generation and vulnerability scans.
FROM maven:3.9.9-eclipse-temurin-21

# Tool versions are explicit so sandbox evidence remains reproducible.
ARG TRIVY_VERSION
ARG SYFT_VERSION
# Trivy normally downloads its database during a scan; set this only when the build environment can preload it.
ARG TRIVY_PRELOAD_DB

# Install build utilities, then add Syft and Trivy for the software bill of materials and security stages.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl jq ca-certificates patch \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL "https://github.com/anchore/syft/releases/download/v${SYFT_VERSION}/syft_${SYFT_VERSION}_linux_$(dpkg --print-architecture).tar.gz" \
       | tar -xz -C /usr/local/bin syft \
    && ARCH="$(dpkg --print-architecture)" \
    && case "$ARCH" in amd64) TVARCH=64bit ;; arm64) TVARCH=ARM64 ;; *) echo "Unsupported arch $ARCH"; exit 1 ;; esac \
    && curl -fsSL "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-${TVARCH}.tar.gz" \
       | tar -xz -C /usr/local/bin trivy \
    && if [ "$TRIVY_PRELOAD_DB" = "true" ]; then trivy image --download-db-only; fi

# All Maven projects executed in the sandbox are redirected to the local Artifactory virtual repository.
COPY maven-settings.xml /opt/ai-factory/maven-settings.xml

# The orchestrator mounts the checked-out task repository at this working directory.
WORKDIR /workspace
CMD ["bash"]
