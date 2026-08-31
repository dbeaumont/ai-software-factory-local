FROM maven:3.9.9-eclipse-temurin-21 AS build
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

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates git docker.io \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10002 --create-home --groups root sandbox-mcp \
    && mkdir -p /var/lib/ai-factory/sandbox-jobs \
    && chown sandbox-mcp:sandbox-mcp /var/lib/ai-factory/sandbox-jobs
WORKDIR /app
COPY --from=build /src/target/sandbox-execution-mcp-server.jar app.jar
USER 10002
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
