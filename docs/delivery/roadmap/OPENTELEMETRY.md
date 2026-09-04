# Mise en place d'OpenTelemetry

## Objectif

Ajouter une chaîne de traces distribuées OpenTelemetry (OTel) au projet, sans remplacer les métriques Prometheus,
les dashboards Grafana, ni les identifiants de corrélation déjà en place.

L'état actuel fournit `ExecutionTracer`, Micrometer, Prometheus, Grafana et la propagation d'une enveloppe MCP
contenant `traceparent`. En revanche, aucun exporteur OTLP ni Collecteur OpenTelemetry ne sont raccordés au runtime.

## Architecture cible

```text
orchestrateur + serveurs MCP
  └─ Micrometer Observation / Tracing
       └─ OTLP HTTP ou gRPC
            └─ OpenTelemetry Collector
                 ├─ Jaeger ou Grafana Tempo en local
                 └─ backend d'observabilité cible (par exemple Cloud Trace)
```

Le Collector découple les applications du backend : le code applicatif exporte uniquement en OTLP. Le choix du
backend, les redactions, l'échantillonnage et le routage restent centralisés dans sa configuration.

## Plan d'implémentation

### 1. Activer le bridge Micrometer vers OpenTelemetry

Ajouter dans le `pom.xml` de l'orchestrateur, puis dans ceux des cinq serveurs MCP, les dépendances suivantes :

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

`ExecutionTracer` construit déjà des `Observation` Micrometer. Avec ce bridge, ces observations deviennent des
spans OpenTelemetry sans devoir réécrire les appels existants autour des workflows, activités, LLM et MCP.

### 2. Configurer les applications

Déclarer une identité de service et l'export OTLP dans `infrastructure/compose.yaml`. Exemple pour
l'orchestrateur :

```yaml
environment:
  OTEL_SERVICE_NAME: ai-factory-orchestrator
  OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
  OTEL_TRACES_EXPORTER: otlp
  OTEL_METRICS_EXPORTER: otlp
  OTEL_TRACES_SAMPLER: parentbased_traceidratio
  OTEL_TRACES_SAMPLER_ARG: "0.10"
```

Adapter `OTEL_SERVICE_NAME` par service. La configuration locale peut exporter les traces à 100 % pendant le POC ;
en environnement partagé, conserver toutes les erreurs et échantillonner les succès est préférable.

Prometheus reste la source des métriques et alertes actuelles durant la migration. L'export de métriques OTLP est
facultatif dans le premier lot : le gain immédiat d'OTel est la trace distribuée.

### 3. Ajouter le Collecteur

Ajouter un service `otel-collector` au Compose, exposant les récepteurs OTLP :

```yaml
otel-collector:
  image: otel/opentelemetry-collector:latest
  volumes:
    - ./observability/otel-collector.yaml:/etc/otelcol/config.yaml:ro
  ports:
    - "4317:4317" # OTLP gRPC
    - "4318:4318" # OTLP HTTP
```

Créer `infrastructure/observability/otel-collector.yaml` avec un récepteur `otlp`, un processeur `batch`, et un
exporteur `debug` pour la première validation. Brancher ensuite Jaeger ou Grafana Tempo en local, puis le backend
d'observabilité choisi en cible.

### 4. Unifier la propagation de contexte

Le `traceparent` actuel est validé et transmis dans les enveloppes MCP. Lorsqu'un span OpenTelemetry est actif,
`McpRequestMetadata` doit utiliser son contexte pour propager le `traceparent`, plutôt que d'en produire un indépendant.
Ainsi, les spans HTTP, MCP et le traitement du serveur appartiennent à une même trace.

Les identifiants métier existants (`task_id`, `attempt_id`, `run_id`, `delegation_id`, `agent_run_id`) restent utiles
pour la recherche et la corrélation. Les ajouter comme attributs de span, en contrôlant leur volume et leur durée de
rétention.

### 5. Valider avant extension

Le premier incrément couvre uniquement l'orchestrateur. Une exécution de test doit produire une trace contenant au
minimum l'entrée HTTP, le workflow, l'appel LLM et l'appel MCP. Après validation, étendre l'instrumentation aux
serveurs MCP et à Temporal.

Critères de sortie du POC :

- une trace est consultable de l'API jusqu'au serveur MCP ;
- les erreurs et durées de chaque segment sont visibles ;
- aucun prompt, résultat, preuve ou secret ne figure dans les attributs exportés ;
- le fonctionnement et les alertes Prometheus existants restent inchangés.

## Convention d'attributs et confidentialité

Conserver la politique actuelle : la capture des prompts, résultats et preuves reste désactivée par défaut. Ne pas
exporter de contenu sensible dans les spans. Privilégier des métadonnées bornées : type d'opération, rôle agent,
statut, classe d'erreur, modèle, compteurs de tokens et coût estimé, lorsque ces valeurs sont autorisées.

Ne pas utiliser des identifiants uniques (`trace_id`, `run_id`, etc.) comme labels de métriques Prometheus : leur forte
cardinalité est adaptée aux traces, mais dégrade les bases de métriques. Les conserver dans les spans et journaux
structurés.

## Bénéfices attendus

- **Diagnostic de bout en bout** : suivre une demande de l'API à Temporal, aux agents, au LLM, aux MCP, au sandbox et
  à la livraison SCM.
- **Analyse de performance** : localiser les latences des LLM, des appels MCP, des files Temporal ou du sandbox.
- **Corrélation d'incident** : partir d'une alerte Prometheus et accéder à la trace exacte, avec les identifiants
  métier existants.
- **Suivi FinOps et qualité** : comparer modèle, tokens, coût estimé, retries et verdict sans exposer le contenu.
- **Portabilité** : le protocole OTLP permet de changer de backend sans modifier le code applicatif.
- **Gouvernance centralisée** : le Collector porte les règles de redaction, d'échantillonnage et de routage.

## Références

- [Spring Boot — Observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html)
- [OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)
- [OpenTelemetry Collector avec Docker](https://opentelemetry.io/docs/collector/install/docker/)
