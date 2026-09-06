# ─────────────────────────────────────────────────────────────────────────────
# Build stage
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.16-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753 AS build

# Variable de configuration build — utilisée uniquement pour rediriger Maven vers le miroir interne
ARG MAVEN_MIRROR_URL

WORKDIR /src

# Copie des fichiers source
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw .

# 1. Installer openssl + ca-certificates (nécessaire pour extraire et enregistrer le CA du miroir Maven)
# 2. Importer le certificat CA du miroir dans le truststore Java (keytool) et le store système Debian (wget est utilisé par mvnw pour télécharger le JAR wrapper)
# 3. Pré-télécharger toutes les dépendances Maven (go-offline) → couche cachée.
# Maven fourni par l'image est utilisé directement : contrairement au Wrapper, il applique l'authentification
# de settings.xml avant tout téléchargement.
#
RUN --mount=type=secret,id=artifactory_token \
    --mount=type=cache,target=/root/.m2 \
  if [ -n "${MAVEN_MIRROR_URL}" ]; then \
       case "${MAVEN_MIRROR_URL}" in \
         https://*) \
           apt-get update; \
           apt-get install -y --no-install-recommends openssl ca-certificates; \
           rm -rf /var/lib/apt/lists/*; \
           MIRROR_HOST=$(echo "${MAVEN_MIRROR_URL}" | sed 's|^https://||' | cut -d'/' -f1); \
           openssl s_client -connect "${MIRROR_HOST}" -showcerts </dev/null 2>/dev/null \
             | awk '/-----BEGIN CERTIFICATE-----/{n++; f="/tmp/ca-"n".crt"} f{print > f} /-----END CERTIFICATE-----/{f=""}'; \
           for cert in /tmp/ca-*.crt; do \
             [ -f "${cert}" ] || break; \
             keytool -import -noprompt -alias "jfrog-$(basename ${cert} .crt)" -file "${cert}" \
               -keystore "${JAVA_HOME}/lib/security/cacerts" -storepass changeit 2>/dev/null || true; \
             cp "${cert}" /usr/local/share/ca-certificates/; \
           done; \
           update-ca-certificates ;; \
       esac; \
       export ARTIFACTORY_TOKEN=$(cat /run/secrets/artifactory_token); \
       mvn dependency:go-offline -B -s .mvn/settings.xml; \
     else \
       mvn dependency:go-offline -B; \
     fi

# Copier les sources après le go-offline pour que cette couche soit invalidée
# uniquement lors d'un changement de code source (pas de dépendance)
COPY src ./src
COPY --from=mcp-schemas . /src/external-schemas
COPY --from=multiagent-schemas . /src/external-multiagent-schemas
COPY --from=multiagent-policies . /src/external-multiagent-policies
COPY --from=multiagent-database . /src/external-multiagent-database
COPY --from=multiagent-fixtures . /src/external-multiagent-fixtures
COPY --from=agent-resources . /src/external-agents
COPY --from=a2a-resources . /src/external-a2a

# Le corpus de replay est une barrière d'image ; les autres tests restent exécutés par la CI avant publication.
RUN --mount=type=secret,id=artifactory_token \
    --mount=type=cache,target=/root/.m2 \
  set -e; \
  if [ -n "${MAVEN_MIRROR_URL}" ]; then \
    export ARTIFACTORY_TOKEN=$(cat /run/secrets/artifactory_token); \
    mvn test -Dtest='A2a*Test,AllowListedAgentRegistryTest,CachingAgentCardResolverTest,TemporalA2aNotificationReceiverTest,WorkflowDeterminismArchitectureTest,SoftwareFactoryWorkflowTest,TemporalFailureModesTest,TemporalCascadeCancellationTest,TaskControllerTest' -B -s .mvn/settings.xml -Dmcp.schemas.directory=/src/external-schemas -Dmultiagent.schemas.directory=/src/external-multiagent-schemas -Dmultiagent.policies.directory=/src/external-multiagent-policies -Dmultiagent.database.directory=/src/external-multiagent-database -Dmultiagent.fixtures.directory=/src/external-multiagent-fixtures -Dagents.directory=/src/external-agents -Da2a.directory=/src/external-a2a; \
    mvn package -DskipTests -B -s .mvn/settings.xml -Dmcp.schemas.directory=/src/external-schemas -Dmultiagent.schemas.directory=/src/external-multiagent-schemas -Dmultiagent.policies.directory=/src/external-multiagent-policies -Dmultiagent.database.directory=/src/external-multiagent-database -Dagents.directory=/src/external-agents -Da2a.directory=/src/external-a2a; \
  else \
    mvn test -Dtest='A2a*Test,AllowListedAgentRegistryTest,CachingAgentCardResolverTest,TemporalA2aNotificationReceiverTest,WorkflowDeterminismArchitectureTest,SoftwareFactoryWorkflowTest,TemporalFailureModesTest,TemporalCascadeCancellationTest,TaskControllerTest' -B -Dmcp.schemas.directory=/src/external-schemas -Dmultiagent.schemas.directory=/src/external-multiagent-schemas -Dmultiagent.policies.directory=/src/external-multiagent-policies -Dmultiagent.database.directory=/src/external-multiagent-database -Dmultiagent.fixtures.directory=/src/external-multiagent-fixtures -Dagents.directory=/src/external-agents -Da2a.directory=/src/external-a2a; \
    mvn package -DskipTests -B -Dmcp.schemas.directory=/src/external-schemas -Dmultiagent.schemas.directory=/src/external-multiagent-schemas -Dmultiagent.policies.directory=/src/external-multiagent-policies -Dmultiagent.database.directory=/src/external-multiagent-database -Dagents.directory=/src/external-agents -Da2a.directory=/src/external-a2a; \
  fi

# ─────────────────────────────────────────────────────────────────────────────
# Runtime stage
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682
RUN apk upgrade --no-cache \
    && apk add --no-cache git curl ca-certificates \
    && addgroup -S -g 10000 orchestrator \
    && adduser -S -D -H -u 10000 -G orchestrator orchestrator
WORKDIR /app
COPY --from=build /src/target/ai-factory-orchestrator.jar app.jar
USER 10000
ENTRYPOINT ["java","-jar","/app/app.jar"]
