# OpenAI-compatible gateway that routes requests to the local Ollama model or the optional cloud model.
# The default uses the corporate Docker proxy to avoid direct TLS access to GHCR.
ARG LITELLM_IMAGE
FROM ${LITELLM_IMAGE}

# The entrypoint expands runtime environment variables in this template before LiteLLM starts.
COPY config.template.yaml /app/config.template.yaml
COPY entrypoint.sh /app/entrypoint.sh
COPY sitecustomize.py /app/python/sitecustomize.py

RUN chmod +x /app/entrypoint.sh

# Keep configuration rendering at container start so secrets never need to be baked into the image.
ENTRYPOINT ["/app/entrypoint.sh"]
