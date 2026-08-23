FROM docker.litellm.ai/berriai/litellm:main-latest

COPY config.template.yaml /app/config.template.yaml
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
