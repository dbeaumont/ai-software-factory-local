FROM clickhouse/clickhouse-server:25.12.5@sha256:cacf32d6884291dc2ff5e0156a97f46fc53ff7c929a7906d114e268a929dfd3a

ARG TARGETOS=linux
ARG TARGETARCH
ARG HISTOGRAM_QUANTILE_VERSION=v0.0.1

USER root
RUN set -eu; \
    case "${TARGETARCH}" in amd64|arm64) ;; *) echo "Unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; esac; \
    wget -q -O /tmp/histogram-quantile.tar.gz \
      "https://github.com/SigNoz/signoz/releases/download/histogram-quantile%2F${HISTOGRAM_QUANTILE_VERSION}/histogram-quantile_${TARGETOS}_${TARGETARCH}.tar.gz"; \
    tar -xzf /tmp/histogram-quantile.tar.gz -C /tmp; \
    install -m 0755 /tmp/histogram-quantile /opt/histogramQuantile; \
    rm -f /tmp/histogram-quantile /tmp/histogram-quantile.tar.gz
