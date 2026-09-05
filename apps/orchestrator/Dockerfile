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
COPY --from=agent-resources . /src/external-agents

# Build du projet avec le profil prod (tests ignorés — ils sont exécutés en CI séparément) avec le profil prod.
RUN --mount=type=secret,id=artifactory_token \
    --mount=type=cache,target=/root/.m2 \
  if [ -n "${MAVEN_MIRROR_URL}" ]; then \
    export ARTIFACTORY_TOKEN=$(cat /run/secrets/artifactory_token); \
    mvn package -DskipTests -B -s .mvn/settings.xml -Dmcp.schemas.directory=/src/external-schemas -Dmultiagent.schemas.directory=/src/external-multiagent-schemas -Dmultiagent.policies.directory=/src/external-multiagent-policies -Dagents.directory=/src/external-agents; \
  else \
    mvn package -DskipTests -B -Dmcp.schemas.directory=/src/external-schemas -Dmultiagent.schemas.directory=/src/external-multiagent-schemas -Dmultiagent.policies.directory=/src/external-multiagent-policies -Dagents.directory=/src/external-agents; \
  fi

# ─────────────────────────────────────────────────────────────────────────────
# Runtime stage
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10000 --create-home orchestrator
WORKDIR /app
COPY --from=build /src/target/ai-factory-orchestrator.jar app.jar
USER 10000
ENTRYPOINT ["java","-jar","/app/app.jar"]
