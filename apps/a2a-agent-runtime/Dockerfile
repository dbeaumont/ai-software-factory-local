# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753 AS build

ARG MAVEN_MIRROR_URL
WORKDIR /src

COPY pom.xml ./
COPY apps/agent-core/pom.xml apps/agent-core/pom.xml
COPY apps/a2a-agent-runtime/pom.xml apps/a2a-agent-runtime/pom.xml
COPY apps/orchestrator/pom.xml apps/orchestrator/pom.xml
COPY apps/orchestrator/.mvn apps/orchestrator/.mvn
COPY resources resources

RUN --mount=type=secret,id=artifactory_token,required=false \
    --mount=type=cache,target=/root/.m2 \
    if [ -n "${MAVEN_MIRROR_URL}" ]; then \
      export ARTIFACTORY_TOKEN="$(cat /run/secrets/artifactory_token)"; \
      mvn -B -s apps/orchestrator/.mvn/settings.xml -pl apps/a2a-agent-runtime -am dependency:go-offline; \
    else \
      mvn -B -pl apps/a2a-agent-runtime -am dependency:go-offline; \
    fi

COPY apps/agent-core/src apps/agent-core/src
COPY apps/a2a-agent-runtime/src apps/a2a-agent-runtime/src

RUN --mount=type=secret,id=artifactory_token,required=false \
    --mount=type=cache,target=/root/.m2 \
    if [ -n "${MAVEN_MIRROR_URL}" ]; then \
      export ARTIFACTORY_TOKEN="$(cat /run/secrets/artifactory_token)"; \
      mvn -B -s apps/orchestrator/.mvn/settings.xml -pl apps/a2a-agent-runtime -am package; \
    else \
      mvn -B -pl apps/a2a-agent-runtime -am package; \
    fi

FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112
RUN useradd --system --uid 10001 --create-home agent-runtime
WORKDIR /app
COPY --from=build /src/apps/a2a-agent-runtime/target/a2a-agent-runtime.jar /app/app.jar
USER 10001
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=agent-runtime"]
