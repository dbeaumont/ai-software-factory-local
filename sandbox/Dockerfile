FROM maven:3.9.9-eclipse-temurin-21

ARG TRIVY_VERSION=0.69.3
ARG SYFT_VERSION=1.30.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl jq ca-certificates patch \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL "https://github.com/anchore/syft/releases/download/v${SYFT_VERSION}/syft_${SYFT_VERSION}_linux_$(dpkg --print-architecture).tar.gz" \
       | tar -xz -C /usr/local/bin syft \
    && ARCH="$(dpkg --print-architecture)" \
    && case "$ARCH" in amd64) TVARCH=64bit ;; arm64) TVARCH=ARM64 ;; *) echo "Unsupported arch $ARCH"; exit 1 ;; esac \
    && curl -fsSL "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-${TVARCH}.tar.gz" \
       | tar -xz -C /usr/local/bin trivy \
    && trivy image --download-db-only

WORKDIR /workspace
CMD ["bash"]
