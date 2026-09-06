# Qualification du SDK Java A2A 1.1.0.Final

- Statut : qualifié pour l'implémentation du client A2A
- Date : 2026-09-06
- Protocole : A2A 1.0
- Artifact : `org.a2aproject.sdk:a2a-java-sdk-client:1.1.0.Final`
- Source : [release officielle](https://github.com/a2aproject/a2a-java/releases/tag/v1.1.0.Final)
- Licence déclarée : Apache-2.0

## Décision

La version `1.1.0.Final` est épinglée pour construire le client A2A de l'orchestrateur. Les types du SDK restent
derrière les ports applicatifs du projet afin de permettre une mise à niveau ou un remplacement sans modifier les
contrats métier.

La qualification du runtime serveur reste séparée : le serveur du projet doit passer le TCK A2A 1.0 et ne doit pas
dépendre d'une intégration Quarkus implicite dans l'application Spring Boot.

## Compatibilité vérifiée

| Élément | Projet | SDK A2A | Conclusion |
|---|---|---|---|
| Java | bytecode cible 25, runtime de qualification 26.0.1 | bytecode Java 17, major 61 | compatible ascendante |
| Protocole | A2A 1.0 imposé par ADR | modèles et transports 1.x | compatible |
| Client | Spring Boot 4.1.1 / WebFlux | client HTTP et transport JSON-RPC | encapsulation requise |
| Transport | JSON-RPC 2.0 sur HTTPS | module `client-transport-jsonrpc` transitif | disponible |
| Sérialisation | Jackson côté application | Gson 2.13.2 résolu par le build | ne pas mélanger les modèles sérialisés |
| Logs | SLF4J géré par Spring Boot | SLF4J 2.0.18 résolu | pas de conflit détecté |
| Protobuf/gRPC | Protobuf 4.35.1 via Spring/Temporal | BOM SDK 4.33.2, build résolu en 4.35.1 | compatible aux tests ; ne pas ajouter le transport gRPC |
| Temporal | SDK 1.38.0 | aucune autorité Temporal dans le client A2A | compatible par séparation des ports |
| OpenTelemetry | starter Spring et instrumentation existante | extensions SDK optionnelles | conserver l'instrumentation du projet |

## Vérifications reproductibles

```shell
mvn -q -s .mvn/settings-direct.xml \
  -Dmaven.repo.local=/tmp/a2a-sdk-m2 \
  dependency:get \
  -Dartifact=org.a2aproject.sdk:a2a-java-sdk-client:1.1.0.Final \
  -Dtransitive=true

javap -verbose \
  -classpath /tmp/a2a-sdk-m2/org/a2aproject/sdk/a2a-java-sdk-client/1.1.0.Final/a2a-java-sdk-client-1.1.0.Final.jar \
  org.a2aproject.sdk.client.Client
```

Le résultat de `javap` est `major version: 61`, soit Java 17.

## Résultats de qualification

| Gate | Résultat |
|---|---|
| Résolution de l'artifact et de ses transitifs depuis Maven Central | succès |
| Arbre de dépendances Maven Spring Boot + Temporal + A2A | succès, aucune convergence bloquante |
| Suite complète `./mvnw -q -s .mvn/settings-direct.xml test` | succès |
| Scan Trivy 0.69.3 du POM, vulnérabilités HIGH/CRITICAL | `0` |
| Vérification whitespace `git diff --check` | requise avant commit |

Le SDK apporte notamment le client HTTP, le SPI de transport, JSON-RPC, les modèles communs et les modèles
protocolaires. Le module client dépend aussi des types Protobuf du SDK même lorsque JSON-RPC est le seul binding
activé. Spring Boot/Temporal résout Protobuf en `4.35.1`, Gson en `2.13.2` et SLF4J en `2.0.18`; la suite complète
valide cette combinaison. Le runtime JDK 26 de qualification émet un avertissement de dépréciation `Unsafe` depuis
Protobuf, sans échec ; le bytecode produit par le projet reste ciblé sur Java 25.

## Contraintes retenues

- ne jamais utiliser une version `SNAPSHOT`, alpha, beta ou candidate en production ;
- conserver la version dans une propriété Maven unique ;
- n'ajouter que le client au contrôle-plane ;
- ajouter les composants serveur uniquement au runtime d'agent ;
- ne pas laisser le SDK négocier silencieusement A2A 0.3 ;
- ne pas exposer les types du SDK dans les contrôleurs publics ou les contrats métier ;
- surveiller les avis de sécurité et requalifier chaque changement de version ;
- produire un SBOM et conserver le rapport de vulnérabilités avec la gate de release.

## Gates de mise à niveau

Toute montée de version exige :

1. lecture des release notes et changements du modèle protocolaire ;
2. résolution Maven et arbre des dépendances ;
3. compilation sous le JDK cible ;
4. tests unitaires, intégration et replay Temporal ;
5. TCK A2A pour les capacités annoncées ;
6. analyse de licences, SBOM et scan de vulnérabilités ;
7. mise à jour de cette matrice et approbation de la release.
