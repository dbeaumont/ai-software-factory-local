FROM maven:3.9.16-eclipse-temurin-25 AS build
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

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10004 --create-home assurance-mcp
WORKDIR /app
COPY --from=build /src/target/assurance-mcp-server.jar app.jar
USER 10004
ENTRYPOINT ["java","--enable-native-access=ALL-UNNAMED","-jar","/app/app.jar"]
