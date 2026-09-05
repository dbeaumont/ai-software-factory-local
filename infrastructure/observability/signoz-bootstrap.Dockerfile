FROM alpine:3.22.1@sha256:4bcff63911fcb4448bd4fdacec207030997caf25e9bea4045fa6c8c44de311d1

RUN apk add --no-cache bash curl jq

WORKDIR /opt/ai-factory
COPY scripts/bootstrap-signoz.sh scripts/bootstrap-signoz.sh
COPY infrastructure/observability/signoz/dashboards infrastructure/observability/signoz/dashboards
COPY infrastructure/observability/signoz/rules infrastructure/observability/signoz/rules

USER 65534:65534
ENTRYPOINT ["/bin/bash", "/opt/ai-factory/scripts/bootstrap-signoz.sh"]
