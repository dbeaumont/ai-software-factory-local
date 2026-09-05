FROM maven:3.9.16-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753 AS build
ARG MAVEN_MIRROR_URL
WORKDIR /src
COPY pom.xml ./
COPY .mvn .mvn
COPY src ./src
RUN --mount=type=secret,id=artifactory_token \
    if [ -n "${MAVEN_MIRROR_URL}" ]; then \
      export ARTIFACTORY_TOKEN=$(cat /run/secrets/artifactory_token) \
      && mvn -B -DskipTests -s .mvn/settings.xml package; \
    else \
      mvn -B -DskipTests package; \
    fi

FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates git \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10002 --create-home sandbox-mcp \
    && mkdir -p /var/lib/ai-factory/sandbox-jobs \
    && chown sandbox-mcp:sandbox-mcp /var/lib/ai-factory/sandbox-jobs
WORKDIR /app
COPY --from=build /src/target/sandbox-execution-mcp-server.jar app.jar
USER 10002
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
