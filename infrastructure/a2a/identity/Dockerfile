FROM python:3.13.7-alpine3.22@sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844

RUN apk add --no-cache openssl \
    && addgroup -S -g 10001 identity \
    && adduser -S -D -H -u 10001 -G identity identity

COPY server.py /opt/a2a-identity/server.py
COPY smoke.py /opt/a2a-identity/smoke.py

USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["python", "/opt/a2a-identity/server.py"]
