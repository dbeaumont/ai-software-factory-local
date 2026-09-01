# Migration Java 25 / Spring Boot 4

## Socle retenu

| Composant | Version | Vérification de compatibilité |
|---|---:|---|
| Java | 25 | cible de compilation Maven et toolchain Gradle ; images Temurin 25 au build et au runtime |
| Spring Boot | 4.1.1 | version stable, compatible Java 17 à 26 |
| Spring Framework | 7.0.9 | version gérée par Spring Boot 4.1.1 |
| Spring AI | 2.0.1 | ligne stable compatible Spring Boot 4.0.x et 4.1.x |
| MCP Java SDK | 2.0.0 | version transitive gérée par le BOM Spring AI 2.0.1 |
| Jackson | 3.1.5 | version gérée par Spring Boot ; imports applicatifs `tools.jackson.*` |
| json-schema-validator | 3.0.6 | dépendance directe de l'orchestrateur, ligne Jackson 3 / Java 17+ ; les modules MCP utilisent la 3.0.0 transitive gérée par Spring AI, également fondée sur Jackson 3 |
| Micrometer / Reactor / Netty | BOM Spring Boot | aucune version locale divergente |
| Lombok | BOM Spring Boot | compilation validée avec la cible Java 25 |

Gitea, SonarQube, LiteLLM, Docker et les outils Syft/Trivy sont appelés par HTTP ou processus et ne partagent pas le classpath Java. Leur protocole et leur configuration ne changent pas avec cette migration.

## Changements incompatibles pris en compte

- Migration de `com.fasterxml.jackson.databind.*` vers `tools.jackson.databind.*`.
- Migration de `json-schema-validator` 2.x (Jackson 2) vers 3.x (Jackson 3).
- Déplacement des API Actuator Health vers `org.springframework.boot.health.contributor`.
- Déplacement des annotations MCP de `org.springaicommunity.mcp.annotation` vers `org.springframework.ai.mcp.annotation`.
- Adaptation de `JsonNode.fields()` à `JsonNode.properties()` et de l'enregistrement automatique des modules Jackson au builder Jackson 3.
- Remplacement du starter MVC historique par `spring-boot-starter-webmvc` et ajout du starter de test modulaire `spring-boot-starter-webmvc-test` dans l'exemple.
- Images de build Maven 3.9.16 / Temurin 25 et images runtime Temurin 25.

## Validation

Depuis chaque module Maven :

```bash
mvn clean test
```

La construction complète des images se valide depuis la racine avec `make build`. Les images obtenues exécutent
Temurin 25 au runtime, y compris l'image de sandbox.

Résultats observés le 1er septembre 2026 :

- orchestrateur : 68 tests réussis ;
- `repository-context-mcp` : 18 tests réussis, dont la négociation MCP et les appels d'outils/ressources ;
- `sandbox-execution-mcp` : 28 tests réussis et 1 test d'intégration Docker optionnel ignoré ;
- exemple `customer-api` : 1 test MVC réussi ;
- `make build` réussi, puis Java `25.0.4` confirmé dans les quatre images Java.

L'exemple Gradle est aligné par toolchain Java 25. Son dépôt ne contient actuellement ni wrapper Gradle ni
installation Gradle autonome ; son exécution reste donc prise en charge uniquement lorsqu'un wrapper est fourni
par le dépôt traité.

La valeur `maven.compiler.release=25`, héritée de `java.version`, garantit du bytecode Java 25 même si Maven est lancé avec un JDK plus récent. Le fichier `.java-version` aligne les gestionnaires de JDK locaux compatibles.

Références :

- [Prérequis Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)
- [Guide de migration Spring Boot 4](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Compatibilité Spring AI](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Migration Spring AI 2](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- [Lignes Jackson 2/3 de json-schema-validator](https://github.com/networknt/json-schema-validator)
