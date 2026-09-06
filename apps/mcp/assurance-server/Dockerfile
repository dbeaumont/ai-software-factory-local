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

FROM eclipse-temurin:25-jre-alpine@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682
RUN apk upgrade --no-cache \
    && apk add --no-cache curl ca-certificates \
    && addgroup -S -g 10004 assurance-mcp \
    && adduser -S -D -H -u 10004 -G assurance-mcp assurance-mcp
WORKDIR /app
COPY --from=build /src/target/assurance-mcp-server.jar app.jar
USER 10004
ENTRYPOINT ["java","--enable-native-access=ALL-UNNAMED","-jar","/app/app.jar"]
