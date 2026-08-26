# ─────────────────────────────────────────────────────────────────────────────
# Build stage
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build

# Variable de configuration build — utilisée uniquement pour rediriger Maven vers le miroir interne
ARG MAVEN_MIRROR_URL

WORKDIR /src

# Copie des fichiers source
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw .

# 1. Installer openssl + ca-certificates (nécessaire pour extraire et enregistrer le CA du miroir Maven)
# 2. Importer le certificat CA du miroir dans le truststore Java (keytool) et le store système Debian (wget est utilisé par mvnw pour télécharger le JAR wrapper)
# 3. Rediriger wrapperUrl/distributionUrl vers le miroir interne (repo1.maven.org inaccessible en entreprise)
# 4. Pré-télécharger toutes les dépendances Maven (go-offline) → couche cachée
# 5. Neutraliser MAVEN_CONFIG: l'image Maven le définit sur /root/.m2, mais Maven Wrapper le lit comme un argument.
#
RUN --mount=type=secret,id=artifactory_token  \
  apt-get update \
  && apt-get install -y --no-install-recommends openssl ca-certificates \
  && rm -rf /var/lib/apt/lists/* \
    && MIRROR_HOST=$(echo "${MAVEN_MIRROR_URL}" | sed 's|^https://||' | cut -d'/' -f1) \
    && openssl s_client -connect "${MIRROR_HOST}" -showcerts </dev/null 2>/dev/null \
       | awk '/-----BEGIN CERTIFICATE-----/{n++; f="/tmp/ca-"n".crt"} f{print > f} /-----END CERTIFICATE-----/{f=""}' \
    && for cert in /tmp/ca-*.crt; do \
         [ -f "${cert}" ] || break; \
         keytool -import -noprompt \
           -alias "jfrog-$(basename ${cert} .crt)" \
           -file "${cert}" \
           -keystore "${JAVA_HOME}/lib/security/cacerts" \
           -storepass changeit 2>/dev/null || true; \
         cp "${cert}" /usr/local/share/ca-certificates/; \
       done \
    && update-ca-certificates \
    && chmod +x mvnw \
    && sed -i "s|https://repo1.maven.org/maven2|${MAVEN_MIRROR_URL}|g" .mvn/wrapper/maven-wrapper.properties \
    && export ARTIFACTORY_TOKEN=$(cat /run/secrets/artifactory_token) \
    && unset MAVEN_CONFIG \
    && ./mvnw dependency:go-offline -B -s .mvn/settings.xml

# Copier les sources après le go-offline pour que cette couche soit invalidée
# uniquement lors d'un changement de code source (pas de dépendance)
COPY src ./src

# Build du projet avec le profil prod (tests ignorés — ils sont exécutés en CI séparément) avec le profil prod.
# MAVEN_CONFIG est aussi neutralisé ici pour conserver le comportement du Maven Wrapper de l'étape précédente.
RUN --mount=type=secret,id=artifactory_token \
  export ARTIFACTORY_TOKEN=$(cat /run/secrets/artifactory_token) \
  && unset MAVEN_CONFIG \
  && ./mvnw package -DskipTests -B -s .mvn/settings.xml

# ─────────────────────────────────────────────────────────────────────────────
# Runtime stage
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
# The orchestrator starts isolated sandbox containers through the mounted Docker socket.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates docker.io \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/ai-factory-orchestrator.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
