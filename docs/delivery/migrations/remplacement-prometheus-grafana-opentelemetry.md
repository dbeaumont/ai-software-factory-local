# Plan de migration immédiate — remplacement complet de Prometheus/Grafana par OpenTelemetry et SigNoz

## 1. Objectif et clarification de la cible

Remplacer la chaîne locale Prometheus/Grafana par une chaîne fondée sur les standards OpenTelemetry, sans perdre
les métriques métier, les alertes, les tableaux de bord, les traces distribuées ni la capacité de diagnostic.

OpenTelemetry fournit l'instrumentation, le protocole OTLP et le Collector ; il ne fournit pas à lui seul de
stockage durable, d'interface de consultation ou de moteur d'alertes. La cible complète est donc :

```text
applications Java / Temporal / infrastructure
                 │
                 ├── OTLP métriques, traces et logs
                 ▼
        OpenTelemetry Collector
                 │
        ┌────────┴─────────┐
        ▼                  ▼
macOS : backend OTLP   GKE : Google Cloud Observability
local tout-en-un       Monitoring / Trace / Logging
```

Le backend local retenu est **SigNoz self-hosted**, qui fournit stockage, requêtes, dashboards et alerting à
partir des signaux OpenTelemetry. Il remplace l'interface Grafana et le stockage Prometheus tout en maintenant
un environnement Docker Compose autonome. La cible GKE utilise le Collector et les services Google Cloud
approuvés par la plateforme.

La bascule est franche : le premier changement de runtime retire Prometheus et Grafana en même temps qu'il
ajoute le Collector, SigNoz et l'export OTLP. Il n'existe ni période de double collecte, ni profil legacy dans
la branche active. La parité est contrôlée à partir de baselines et de fixtures capturées avant la suppression,
puis vérifiée directement dans SigNoz. Le document
[`strategie-opentelemetry.md`](../roadmap/strategie-opentelemetry.md) décrit l'introduction initiale d'OTel en
complément ; la décision du présent plan la remplace pour retenir une substitution immédiate et complète.

Décision de mise en œuvre : les lots décrivent les dépendances techniques, mais les lots 2 à 4 sont livrés dans
une même bascule atomique. La branche ne doit jamais contenir un runtime actif sans observabilité, ni un runtime
où Prometheus/Grafana et SigNoz fonctionnent simultanément.

## 2. État initial à préserver

Au début de la migration, le dépôt possède notamment :

- Prometheus `v3.5.0`, sans volume de données persistant déclaré ;
- Grafana `12.1.1`, avec un volume persistant et un provisioning versionné ;
- quatre cibles de scrape : orchestrateur, Repository Context MCP, Sandbox Execution MCP et Temporal ;
- six dashboards provisionnés : orchestrateur, Supervisor, agents, Temporal, MCP et sandbox ;
- neuf règles d'alerte dans `infrastructure/observability/alerts/multiagents.yml` ;
- des métriques Micrometer métier et techniques, exposées par Actuator ;
- des `Observation` Micrometer autour des workflows, agents, LLM, MCP et jobs sandbox ;
- une propagation `traceparent` partiellement manuelle et aucun export OTLP opérationnel ;
- des baselines historiques capturées depuis `/actuator/prometheus` ;
- un usage local macOS via Docker Compose et une cible partagée GKE.

## 3. Résultat attendu

- [ ] Toutes les applications Spring Boot émettent leurs métriques et traces via OTLP vers un Collector.
- [ ] Les logs structurés contiennent les identifiants OTel de corrélation sans contenu sensible.
- [ ] Une trace relie API, pipeline ou workflow, agents, LLM, MCP, sandbox, assurance et SCM.
- [ ] Les métriques Temporal et celles des composants non instrumentables sont intégrées sans dépendre d'un serveur Prometheus autonome.
- [ ] Les six dashboards actuels possèdent un équivalent validé dans le backend cible.
- [ ] Les neuf alertes actuelles possèdent un équivalent testé et routé.
- [ ] Les SLO, historiques et baselines indispensables restent consultables pendant la durée de rétention convenue.
- [ ] Le développement macOS reste autonome avec `docker compose up` et un backend OTLP local.
- [ ] GKE exporte vers Google Cloud Observability avec identité, chiffrement et réseau minimaux.
- [ ] Prometheus, Grafana, leurs ports, volumes et configurations sont retirés du runtime actif.
- [ ] Une panne du Collector ou du backend n'interrompt jamais un workflow métier.
- [ ] Le rollback repose sur le redéploiement du commit précédent, jamais sur la coexistence des deux chaînes.

## 4. Principes d'architecture

- [x] Conserver Micrometer Observation comme API d'instrumentation privilégiée dans le code Spring.
- [x] Utiliser OpenTelemetry comme modèle de télémétrie et OTLP comme protocole entre services et Collector.
- [x] Interdire les dépendances directes du code métier envers un backend d'observabilité particulier.
- [x] Centraliser filtrage, redaction, batch, limitation mémoire, retry et routage dans le Collector.
- [x] Séparer les Collectors locaux, les gateways GKE et leurs configurations par environnement.
- [x] Utiliser les conventions sémantiques OTel stables ; isoler les conventions expérimentales derrière une couche versionnée.
- [x] Conserver les identifiants métier comme attributs de traces/logs, jamais comme dimensions métriques non bornées.
- [x] Ne jamais exporter prompts, réponses, code source, patchs, preuves, secrets ou jetons par défaut.
- [x] Préférer la perte bornée de télémétrie au blocage du chemin métier en cas de panne d'observabilité.
- [x] Versionner dashboards, alertes, configurations Collector et tests de parité dans le dépôt.
- [ ] Épingler toutes les images par version puis par digest après qualification.
- [x] Documenter explicitement les différences acceptables entre les backends local et GKE.

## 5. Lot 0 — cadrage, inventaire et décisions

### 5.1 Inventaire mesurable

- [x] Exporter la liste de toutes les métriques exposées par chaque application et par Temporal.
- [x] Relever type, unité, description, labels et cardinalité observée pour chaque métrique.
- [x] Identifier les métriques réellement utilisées dans chacun des six dashboards.
- [x] Identifier les métriques et fenêtres utilisées par chacune des neuf alertes.
- [x] Recenser les liens depuis README, Makefile, application web, runbooks et documentation vers Prometheus/Grafana.
- [x] Inventorier les tests qui lisent `prometheus.yml`, les règles Prometheus ou les JSON Grafana.
- [x] Inventorier les scripts et preuves qui interrogent `/actuator/prometheus`.
- [x] Capturer une baseline de charge nominale et dégradée : débit, erreurs, p50, p95, p99 et cardinalité.
- [x] Mesurer l'empreinte CPU, mémoire et disque de Prometheus/Grafana afin de comparer la cible locale.
- [x] Définir les durées de rétention nécessaires pour métriques, traces, logs et audit.

### 5.2 Décisions à consigner dans une ADR

- [ ] Qualifier SigNoz self-hosted et sa compatibilité `arm64`/Docker Desktop.
- [ ] Valider Google Cloud Monitoring, Trace et Logging comme backends GKE, ou documenter l'alternative retenue.
- [x] Décider si les logs applicatifs sont exportés par OTLP dès cette migration ou dans un lot séparé.
- [x] Décider le traitement des métriques Temporal : receiver Prometheus du Collector, receiver dédié ou export natif.
- [x] Décider le traitement des métriques d'infrastructure qui ne disposent pas d'un SDK OTel.
- [x] Définir les SLO d'ingestion, la perte maximale tolérée et le délai maximal d'apparition des signaux.
- [x] Définir les budgets de volume et de coût par signal et par environnement.
- [x] Définir le propriétaire des dashboards, alertes, Collector, schémas d'attributs et règles de rétention.
- [x] Définir le mécanisme d'authentification OTLP local et GKE.
- [x] Définir l'export des dashboards Grafana et la suppression du volume Grafana existant avant la bascule.

### Critères de sortie du lot 0

- [x] La matrice métrique → dashboard → alerte → runbook est complète et revue.
- [ ] SigNoz local et les backends GKE sont approuvés avec coûts, rétention et responsabilités.
- [x] Une baseline reproductible permet de mesurer la parité avant/après.
- [x] Aucun besoin actuel n'est implicitement délégué à « OpenTelemetry » sans backend responsable.

## 6. Lot 1 — contrat de télémétrie et gouvernance des données

### 6.1 Ressources et conventions

- [x] Définir `service.name`, `service.namespace`, `service.version` et `deployment.environment.name` pour chaque service.
- [x] Définir les noms stables des spans racines et enfants : tâche, workflow, agent, LLM, MCP, sandbox, assurance et SCM.
- [x] Définir les métriques RED par service : débit, erreurs et durée.
- [x] Définir les métriques métier conservées : tâches, budgets, tokens, coûts, tours, fan-out, files et verdicts.
- [x] Définir unités, temporality, histogram buckets et règles de renommage des métriques existantes.
- [x] Définir une liste fermée de valeurs pour rôle, opération, résultat, erreur, serveur MCP et outil.
- [x] Réserver `task_id`, `run_id`, `execution_id`, `trace_id` et `span_id` aux traces/logs ou exemplars.
- [x] Ajouter une version de schéma de télémétrie permettant de suivre les changements incompatibles.
- [x] Définir la compatibilité entre conventions OTel génériques, conventions GenAI et attributs `ai.*` internes.

### 6.2 Confidentialité et sécurité

- [x] Maintenir les quatre options de capture de contenu à `false` par défaut.
- [ ] Écrire les règles de redaction du Collector pour URL, headers, paramètres, exceptions et attributs.
- [ ] Refuser les clés d'attribut inconnues ou dangereuses aux frontières d'instrumentation internes.
- [x] Borner le nombre d'attributs, leur longueur, le nombre d'événements et de liens par span.
- [ ] Scanner automatiquement des exports OTLP de test pour secrets, prompts, code, patchs et preuves.
- [x] Définir les droits d'accès distincts entre télémétrie opérationnelle, sécurité et coûts.
- [x] Définir rétention, purge, chiffrement, localisation et journalisation des accès.
- [ ] Réaliser une analyse de menace des endpoints OTLP et du Collector.

### Critères de sortie du lot 1

- [x] Le contrat de télémétrie est versionné et testé.
- [x] Un test de cardinalité refuse les dimensions non bornées.
- [x] Un test négatif prouve qu'aucun contenu sensible n'est exporté par défaut.
- [ ] La revue sécurité et protection des données approuve les signaux et leur rétention.

## 7. Lot 2 — instrumentation OpenTelemetry des applications

### 7.1 Socle Spring Boot

- [ ] Ajouter le starter/bridge OpenTelemetry compatible avec Spring Boot 4.1.1 à l'orchestrateur.
- [ ] Ajouter le même socle aux cinq serveurs MCP.
- [ ] Centraliser versions et dépendances OTel dans le parent Maven ou le BOM approprié.
- [ ] Configurer l'export OTLP par variables d'environnement, désactivable explicitement en test unitaire.
- [ ] Configurer les ressources OTel de chaque service avec des valeurs déterministes.
- [ ] Conserver `SimpleMeterRegistry` uniquement dans les tests unitaires qui en ont besoin.
- [ ] Vérifier qu'un seul SDK et un seul provider effectif sont chargés par application.
- [ ] Définir les timeouts, batchs et files d'export bornés afin de ne pas ralentir l'arrêt ou le chemin métier.

### 7.2 Spans et propagation

- [ ] Relier le span HTTP entrant au traitement asynchrone complet de la tâche.
- [ ] Adapter `ExecutionTracer` pour utiliser le contexte OTel actif et enregistrer statuts et exceptions.
- [ ] Instrumenter les frontières pipeline, workflow, child workflow et activité.
- [ ] Instrumenter les agents, tours, appels LLM, retries et appels d'outils sans enregistrer leur contenu.
- [ ] Propager W3C Trace Context sur HTTP vers LiteLLM et les cinq serveurs MCP.
- [ ] Remplacer la fabrication manuelle de `traceparent` par l'injection du propagateur OTel.
- [ ] Conserver temporairement le champ de compatibilité MCP puis le retirer après validation de tous les clients.
- [ ] Instrumenter soumission, attente, timeout, annulation et résultat des jobs sandbox Compose/GKE.
- [ ] Rendre l'instrumentation Temporal sûre face aux replays, retries, child workflows et `continue-as-new`.
- [ ] Utiliser des `Span Link` lorsqu'une relation n'est pas une parenté stricte.
- [ ] Vérifier que les tâches parallèles produisent des spans frères réellement superposés.

### 7.3 Métriques et logs

- [ ] Exporter par OTLP les compteurs, gauges, timers et distributions Micrometer existants.
- [ ] Vérifier le mapping des noms, unités, histogrammes et tags entre Micrometer et OTel.
- [ ] Ajouter `trace_id` et `span_id` au contexte de logs structuré lorsque le contexte est actif.
- [ ] Conserver les logs sur stdout comme voie de secours indépendante du Collector.
- [ ] Normaliser les erreurs avec un type borné et conserver le détail uniquement dans l'événement de trace autorisé.
- [ ] Représenter un coût inconnu comme indisponible, jamais comme un coût nul.

### Critères de sortie du lot 2

- [ ] Les six applications Spring démarrent avec et sans Collector disponible.
- [ ] Une trace locale relie une requête API aux appels LLM, MCP et sandbox correspondants.
- [ ] Les tests de retry, timeout, annulation et exception produisent les statuts attendus.
- [ ] Les métriques métier historiques sont présentes dans un export OTLP de référence.
- [ ] Aucun test ne détecte de donnée sensible ou de cardinalité non bornée.

## 8. Lot 3 — OpenTelemetry Collector

### 8.1 Configuration commune

- [ ] Ajouter une image `otel/opentelemetry-collector-contrib` épinglée et qualifiée sur `amd64` et `arm64`.
- [ ] Ajouter les receivers OTLP/gRPC et OTLP/HTTP sur des réseaux internes uniquement.
- [ ] Ajouter le receiver nécessaire aux métriques Temporal et autres sources non OTLP retenues au lot 0.
- [ ] Ajouter `memory_limiter`, `batch`, filtrage, transformation, redaction et détection de ressources.
- [ ] Configurer les retry queues, la persistance éventuelle et la dead-letter policy selon le backend.
- [ ] Séparer les pipelines métriques, traces et logs.
- [ ] Exposer santé, readiness et métriques internes du Collector sans port public inutile.
- [ ] Épingler et tester les versions de configuration et composants Collector utilisés.
- [ ] Désactiver les extensions, receivers et exporters inutiles.
- [ ] Définir les limites CPU, mémoire, files, batchs et taille maximale de message.

### 8.2 Résilience et sécurité

- [ ] Activer TLS/mTLS ou une authentification approuvée hors du réseau local isolé.
- [ ] Stocker les credentials d'export hors des fichiers de configuration versionnés.
- [ ] Appliquer `read_only`, utilisateur non-root, capabilities supprimées et `no-new-privileges` en Compose.
- [ ] Appliquer Pod Security, ServiceAccount dédié, Workload Identity et NetworkPolicies en GKE.
- [ ] Tester backend lent, backend indisponible, données invalides, saturation et redémarrage du Collector.
- [ ] Vérifier que la backpressure reste bornée et ne remonte pas jusqu'au chemin métier.
- [ ] Définir des alertes sur refus, pertes, files pleines, mémoire, redémarrages et erreurs d'export.
- [ ] Vérifier que les logs du Collector ne réaffichent pas les payloads rejetés ou les credentials.

### Critères de sortie du lot 3

- [ ] Le Collector reçoit les trois signaux retenus et les route vers les backends attendus.
- [ ] La perte et le retard d'ingestion sont mesurables.
- [ ] Une panne complète du Collector ne fait échouer aucune opération métier.
- [ ] Les configurations passent validation syntaxique, tests de sécurité et test de charge.

## 9. Lot 4 — SigNoz, dashboards et alertes sur macOS

### 9.1 Déploiement local retenu

- [ ] Ajouter SigNoz self-hosted et ses stockages ClickHouse/PostgreSQL au Compose du projet.
- [ ] Générer les fichiers de déploiement depuis une configuration Foundry versionnée, puis versionner le rendu auditable utilisé par le projet.
- [ ] Interdire tout téléchargement ou génération implicite au démarrage normal de la pile.
- [ ] Épingler chaque image SigNoz, Collector et stockage par version puis par digest qualifié.
- [ ] Publier uniquement l'interface SigNoz sur `${SIGNOZ_PORT:-3301}` afin d'éviter le port `8080` déjà utilisé par l'application.
- [ ] Conserver OTLP `4317/4318`, ClickHouse et PostgreSQL sur un réseau Compose interne non publié.
- [ ] Faire exporter le Collector du projet vers l'ingester SigNoz par OTLP.
- [ ] Ne pas monter la socket Docker dans un composant SigNoz ou Collector.
- [ ] Collecter métriques et logs de conteneurs sans socket, ou documenter leur exclusion si aucune source sûre n'est disponible.
- [ ] Ajouter volumes persistants, healthchecks et initialisation idempotente.
- [ ] Créer le compte administrateur initial sans secret en clair dans le dépôt.
- [ ] Documenter un minimum de 4 Go de mémoire Docker pour SigNoz, puis mesurer la recommandation réelle pour la pile complète.
- [ ] Définir une rétention locale courte et bornée, ainsi que les limites de stockage ClickHouse.
- [ ] Configurer le Collector local pour exporter métriques, traces et logs vers SigNoz.
- [ ] Configurer la self-observability du Collector pour qu'elle soit envoyée en OTLP à SigNoz, sans endpoint Prometheus.
- [ ] Prévoir un profil `observability` activé par défaut et un mode `OTEL_SDK_DISABLED=true` explicite pour les tests sans observabilité.

### 9.2 Solution de dashboards basée sur les métriques OTel

Les dashboards sont reconstruits nativement dans SigNoz à partir des métriques OTLP et, lorsque pertinent, des
métriques RED dérivées des spans. Les JSON Grafana ne sont pas exécutés par compatibilité : ils servent de
spécification visuelle et fonctionnelle pendant le portage.

| Dashboard SigNoz cible | Métriques et signaux principaux | Parité attendue |
|---|---|---|
| Vue globale | tâches, taux de succès/erreur, durée, files et santé services | `orchestrator.json` |
| Supervisor | décisions, replans, contradictions, fan-out et profondeur | `supervisor.json` |
| Agents | tours, tokens, coûts, latence, échecs et stop conditions | `agents.json` |
| MCP | appels, erreurs, retries, inflight et durée par serveur/outil | `mcp.json` |
| Sandbox | jobs, verdicts, files, timeouts, maintenance et GKE | `sandbox.json` |
| Temporal | workflows, activités, task queues, retries et latence | `temporal.json` |
| OpenTelemetry Collector | réception, export, refus, pertes, files et mémoire | nouveau dashboard obligatoire |

- [ ] Définir les requêtes SigNoz de chaque panneau dans un inventaire versionné.
- [ ] Provisionner ou importer automatiquement le dashboard « Vue globale ».
- [ ] Provisionner ou importer automatiquement le dashboard « Supervisor ».
- [ ] Provisionner ou importer automatiquement le dashboard « Agents ».
- [ ] Provisionner ou importer automatiquement le dashboard « MCP ».
- [ ] Provisionner ou importer automatiquement le dashboard « Sandbox ».
- [ ] Provisionner ou importer automatiquement le dashboard « Temporal ».
- [ ] Créer le dashboard « OpenTelemetry Collector » à partir de sa télémétrie interne OTLP.
- [ ] Reproduire variables, unités, seuils, fenêtres, légendes et liens des panneaux existants.
- [ ] Utiliser les histogrammes OTLP pour p50, p95 et p99 sans recalcul incompatible.
- [ ] Ajouter la recherche par `ai.task.id`, rôle, opération, résultat, modèle et service MCP.
- [ ] Ajouter un parcours dashboard → trace → logs de la même tâche.
- [ ] Ajouter des liens vers Temporal UI, le détail de tâche et les runbooks.
- [ ] Exporter les définitions SigNoz après chaque modification et contrôler leur dérive en CI.

### 9.3 Alertes SigNoz

- [ ] Migrer `AiFactoryAgentLoopDetected` avec son seuil, sa durée et son runbook.
- [ ] Migrer `AiFactoryAgentBudgetExhausted` avec son seuil, sa durée et son runbook.
- [ ] Migrer `AiFactoryAgentCostSpike` avec son seuil, sa durée et son runbook.
- [ ] Migrer `AiFactoryTaskQueueBacklog` avec son seuil, sa durée et son runbook.
- [ ] Migrer `AiFactorySandboxHeartbeatInvalid` avec son seuil, sa durée et son runbook.
- [ ] Migrer `AiFactorySandboxExecutionFailures` avec son seuil, sa durée et son runbook.
- [ ] Migrer `AiFactorySandboxMaintenanceFailure` avec son seuil, sa durée et son runbook.
- [ ] Migrer `AiFactoryAgentContractError` avec son seuil, sa durée et son runbook.
- [ ] Migrer `AiFactoryEvidenceAltered` avec son seuil, sa durée et son runbook.
- [ ] Configurer les destinations de notification, regroupement, inhibition et déduplication.
- [ ] Ajouter les alertes de santé du Collector, d'ingestion SigNoz et de stockage ClickHouse.
- [ ] Tester chaque règle par injection d'une fixture OTLP déterministe.
- [ ] Mettre à jour `make status`, `make urls`, l'application web et les guides pour pointer vers SigNoz.
- [ ] Tester sauvegarde, restauration, rétention, compactage et suppression contrôlée des données locales.

### Critères de sortie du lot 4

- [ ] `docker compose up` démarre la pile et l'observabilité sur macOS Apple Silicon.
- [ ] Un développeur retrouve une tâche, une erreur et son chemin critique depuis l'interface locale.
- [ ] Les six dashboards et neuf alertes sont provisionnés sans action manuelle.
- [ ] Le dashboard Collector et ses alertes techniques détectent une perte ou un retard d'export.
- [ ] Le redémarrage du backend local conserve les données selon la rétention définie.
- [ ] L'arrêt propre de Compose ne corrompt ni files Collector ni stockage local.

## 10. Lot 5 — cible GKE et Google Cloud Observability

- [ ] Créer les namespaces et ServiceAccounts dédiés à la collecte de télémétrie.
- [ ] Déployer un Collector agent/DaemonSet uniquement si les signaux de nœud ou fichiers le nécessitent.
- [ ] Déployer des Collectors gateway redondés pour OTLP, tail sampling et export Google Cloud.
- [ ] Utiliser Workload Identity pour l'export, sans clé de compte de service montée.
- [ ] Appliquer les rôles IAM minimaux pour métriques, traces et logs.
- [ ] Stocker les secrets résiduels dans Secret Manager et tester leur rotation.
- [ ] Appliquer NetworkPolicies, TLS, Pod Security, quotas, requests/limits, PDB et anti-affinité.
- [ ] Configurer autoscaling et files persistantes selon la volumétrie mesurée.
- [ ] Définir les ressources et labels surveillés compatibles avec Google Cloud.
- [ ] Recréer les six dashboards dans Cloud Monitoring ou l'interface d'entreprise approuvée.
- [ ] Recréer les neuf alertes et leurs notification channels avec déduplication.
- [ ] Configurer les liens Monitoring → Trace → Logging et vers les runbooks.
- [ ] Définir rétention, régions, exclusions de logs et budgets/alertes de coût.
- [ ] Tester rolling update, perte d'une zone, saturation, quota API et indisponibilité du backend.
- [ ] Vérifier les contraintes VPC Service Controls et egress privé applicables.

### Critères de sortie du lot 5

- [ ] La télémétrie d'un workflow GKE complet apparaît dans les trois backends Google Cloud.
- [ ] Les Collectors tolèrent la perte d'une instance et une mise à jour sans interruption observable.
- [ ] Les dashboards, alertes, notifications et runbooks sont accessibles aux rôles prévus.
- [ ] Les quotas, la rétention et le coût projeté sont approuvés par plateforme et exploitation.

## 11. Lot 6 — preuve de parité sans double collecte

### 11.1 Références hors ligne

- [ ] Capturer avant modification les sorties Prometheus nécessaires sous forme de fixtures versionnées et expurgées.
- [ ] Exporter les six dashboards Grafana et leurs captures de référence avant suppression.
- [ ] Exporter les neuf règles, annotations et résultats de scénarios d'alerte avant suppression.
- [ ] Construire un générateur de scénarios produisant des valeurs métier déterministes via OTLP.
- [ ] Rejouer les scénarios exclusivement dans la nouvelle chaîne Collector/SigNoz.
- [ ] Comparer la cible aux fixtures, jamais à une instance Prometheus/Grafana exécutée en parallèle.
- [ ] Horodater et versionner chaque campagne de comparaison.

### 11.2 Comparaisons

- [ ] Comparer présence, type, unité et dimensions de chaque métrique contractuelle.
- [ ] Comparer valeurs agrégées et tolérances sur débit, erreurs, p50, p95 et p99.
- [ ] Comparer les six dashboards SigNoz panneau par panneau aux captures et spécifications exportées.
- [ ] Déclencher les neuf alertes synthétiquement dans SigNoz.
- [ ] Comparer seuil, fenêtre, délai, severity, description, notification et lien de runbook aux fixtures.
- [ ] Vérifier les périodes sans données, resets de compteurs et redémarrages d'instances.
- [ ] Mesurer délai d'ingestion, taux de perte, cardinalité, CPU, mémoire, disque et coût.
- [ ] Vérifier la corrélation d'une alerte vers une trace et les logs de la même tâche.
- [ ] Classer chaque divergence : défaut, changement sémantique accepté ou limite du backend.
- [ ] Faire approuver toute divergence acceptée par le propriétaire du signal.

### Critères de sortie du lot 6

- [ ] Aucune métrique, dashboard ou alerte critique ne manque dans la cible.
- [ ] Aucune divergence inexpliquée ne subsiste pendant la fenêtre convenue.
- [ ] Les SLO d'ingestion, de perte, de requête et de notification sont respectés.
- [ ] Sécurité, exploitation, plateforme et équipes de développement approuvent la nouvelle chaîne.

## 12. Lot 7 — bascule atomique immédiate

- [ ] Regrouper instrumentation, Collector, SigNoz, dashboards, alertes et suppression Prometheus/Grafana dans une branche de bascule unique.
- [ ] Valider cette branche sur une installation Compose isolée qui ne démarre jamais Prometheus/Grafana.
- [ ] Vérifier hors ligne la présence de toutes les métriques contractuelles avant le changement de pile principale.
- [ ] Définir une courte fenêtre de bascule et geler les modifications d'observabilité concurrentes.
- [ ] Exporter les artefacts Grafana et arrêter proprement la pile historique.
- [ ] Ne pas supprimer immédiatement le volume Grafana : le détacher et noter sa date d'expiration.
- [ ] Déployer directement Collector/SigNoz et les applications configurées en OTLP.
- [ ] Exécuter les smoke tests de santé, ingestion, dashboards et alertes.
- [ ] Exécuter un workflow complet et vérifier métriques, trace, logs et verdict.
- [ ] Déclarer SigNoz comme unique point d'entrée opérateur local.
- [ ] Déclencher le rollback de version si un critère bloquant échoue ; ne pas démarrer les deux chaînes ensemble.
- [ ] Enregistrer date, commit, propriétaires, résultats et décision de la bascule.

### Critères de sortie du lot 7

- [ ] Cent pour cent des services actifs émettent via OTLP.
- [ ] Cent pour cent des alertes actives proviennent du nouveau backend.
- [ ] Les opérateurs utilisent la nouvelle interface et réussissent un exercice d'incident.
- [ ] Aucun conteneur Prometheus ou Grafana n'existe dans le projet Compose actif.

## 13. Actions de suppression immédiate obligatoires dans `OTEL-100`

Ces actions ne constituent pas un lot ultérieur : elles sont exécutées dans le même commit et au même
déploiement que l'ajout de Collector/SigNoz. Seule la destruction physique de l'ancien volume détaché est
différée afin de rester récupérable.

- [ ] Sauvegarder ou exporter les dashboards, alertes et données historiques à conserver.
- [ ] Retirer les services `prometheus` et `grafana` de `infrastructure/compose.yaml`.
- [ ] Retirer les ports `PROMETHEUS_PORT` et `GRAFANA_PORT` des fichiers d'environnement et guides.
- [ ] Détacher `grafana-data` de Compose, puis planifier sa suppression après validation explicite de la sauvegarde.
- [ ] Retirer `infrastructure/observability/prometheus.yml` et les règles devenues inactives.
- [ ] Retirer le provisioning et les JSON Grafana après migration vers leur format cible versionné.
- [ ] Retirer `micrometer-registry-prometheus` des six applications dans le même changement.
- [ ] Retirer l'exposition `/actuator/prometheus` des configurations actives.
- [ ] Mettre à jour les tests qui dépendaient de PromQL, du scrape ou du provisioning Grafana.
- [ ] Migrer les scripts de baseline vers OTLP ou l'API du backend cible.
- [ ] Mettre à jour README, Makefile, application web, schémas, état courant et documentation d'architecture.
- [ ] Mettre à jour les runbooks avec les nouvelles requêtes, écrans et procédures de diagnostic.
- [ ] Conserver les mentions historiques uniquement dans les archives et preuves datées.
- [ ] Ajouter un contrôle CI interdisant le retour des services et dépendances Prometheus/Grafana actifs.
- [ ] Vérifier que la configuration ne contient aucun profil `observability-legacy` ni double export.
- [ ] Autoriser le receiver Prometheus du Collector uniquement pour une source tierce ne parlant pas OTLP, notamment Temporal ; ce receiver ne déploie aucun serveur Prometheus.
- [ ] Documenter chaque receiver de compatibilité et le supprimer dès que la source sait émettre OTLP nativement.

### Critères de sortie du lot 8

- [ ] Aucun service actif ne dépend de Prometheus ou Grafana.
- [ ] Aucun port, volume déclaré dans Compose, datasource ou configuration active Prometheus/Grafana ne subsiste.
- [ ] Les applications ne publient plus d'endpoint Prometheus inutile.
- [ ] Le mot `prometheus` ne subsiste dans le runtime que dans un receiver Collector de compatibilité explicitement justifié et testé.
- [ ] La documentation et les interfaces ne présentent que la nouvelle chaîne.
- [ ] Les sauvegardes historiques nécessaires sont lisibles et leur date d'expiration est connue.

## 14. Stratégie de tests finale

### 14.1 Tests automatisés

- [ ] Tests unitaires des conventions de nommage, attributs, unités et cardinalité.
- [ ] Tests unitaires de redaction et de refus des contenus interdits.
- [ ] Tests de propagation W3C HTTP, MCP, asynchrone, Temporal et sandbox.
- [ ] Tests de statuts de span pour succès, rejet métier, erreur technique, timeout et annulation.
- [ ] Tests de mapping Micrometer → OTel pour compteurs, gauges, timers et histogrammes.
- [ ] Tests syntaxiques et de démarrage des configurations Collector.
- [ ] Tests de provisioning des dashboards et alertes locaux/GKE.
- [ ] Tests CI garantissant que les métriques contractuelles sont toujours émises.
- [ ] Tests CI interdisant secrets, contenu GenAI et labels à haute cardinalité.
- [ ] Tests de compatibilité des images sur `arm64` et `amd64`.

### 14.2 Tests d'intégration et end-to-end

- [ ] Démarrer une installation Compose neuve avec volumes vierges.
- [ ] Lancer un workflow nominal et le retrouver par `ai.task.id`.
- [ ] Lancer un workflow bloqué par qualité ou sécurité et vérifier le verdict dans métriques, trace et logs.
- [ ] Forcer erreur LLM, retry MCP, timeout sandbox, annulation et saturation de file.
- [ ] Vérifier la parenté et l'ordre temporel des spans dans chaque scénario.
- [ ] Vérifier que les six dashboards affichent des données cohérentes après chaque scénario.
- [ ] Déclencher et acquitter les neuf alertes avec leurs notifications.
- [ ] Redémarrer application, Collector et backend pendant une charge contrôlée.
- [ ] Couper le Collector et vérifier que le workflow métier continue.
- [ ] Saturer les files et mesurer précisément les pertes et délais.
- [ ] Tester sauvegarde/restauration puis arrêt propre du backend local.
- [ ] Exécuter la même campagne sur un cluster GKE de validation.
- [ ] Exécuter un replay Temporal et vérifier l'absence de spans incohérents ou dupliqués.

## 15. Rollback

- [ ] Conserver un tag ou une version déployable de la dernière configuration Prometheus/Grafana validée.
- [ ] Conserver hors ligne les exports de configuration et la sauvegarde du volume Grafana pendant la période convenue.
- [ ] Documenter le redéploiement complet du commit précédent, applications comprises.
- [ ] Interdire un rollback partiel qui mélangerait export OTLP et ancienne configuration applicative.
- [ ] Arrêter Collector/SigNoz avant de redéployer la version Prometheus/Grafana précédente.
- [ ] Documenter la restauration des notifications historiques sans doublons.
- [ ] Tester le rollback après panne Collector, défaut backend, perte de métrique et alerte manquante.
- [ ] Vérifier que le rollback ne réactive aucune capture de donnée sensible.
- [ ] Définir l'autorité pouvant déclencher le rollback et le délai de décision.
- [ ] Supprimer sauvegardes et tag de rollback uniquement après approbation formelle de la stabilité.

## 16. Documentation, exploitation et formation

- [ ] Créer un runbook « Collector indisponible ou saturé ».
- [ ] Créer un runbook « télémétrie absente, retardée ou rejetée ».
- [ ] Créer un runbook « cardinalité ou coût d'observabilité anormal ».
- [ ] Mettre à jour les runbooks agents, sandbox, MCP, Temporal et saturation.
- [ ] Documenter requêtes usuelles, filtres, recherche de traces et parcours d'incident.
- [ ] Documenter sauvegarde, restauration, rétention, purge et rotation des credentials.
- [ ] Documenter les différences entre interface locale et Google Cloud.
- [ ] Former les développeurs aux conventions OTel et aux règles de cardinalité.
- [ ] Former les opérateurs aux dashboards, alertes, traces et logs corrélés.
- [ ] Organiser un exercice d'incident et enregistrer les écarts de procédure.
- [ ] Mettre à jour la checklist de release et la revue de sécurité.

## 17. Découpage recommandé en tickets et commits

- [x] `OTEL-000` — ADR, inventaire, baseline et matrice de parité.
- [x] `OTEL-010` — contrat de télémétrie, conventions et tests de confidentialité.
- [ ] `OTEL-100` — **bascule runtime atomique** regroupant instrumentation des six applications, propagation W3C, Collector, SigNoz, dashboards, alertes et retrait de Prometheus/Grafana.
- [ ] `OTEL-101` — qualification macOS complète de la nouvelle pile sans aucun conteneur legacy.
- [ ] `OTEL-050` — Collectors GKE, Workload Identity et export Google Cloud.
- [ ] `OTEL-060` — campagne de parité sur fixtures historiques et rapport de validation SigNoz.
- [ ] `OTEL-070` — exercice opérateur, tests de panne et décision de stabilité.
- [ ] `OTEL-080` — suppression différée du volume Grafana détaché après expiration de la sauvegarde.
- [ ] `OTEL-090` — documentation finale, retrait des artefacts de rollback et clôture sécurité.

Les tickets `OTEL-000` et `OTEL-010` ne changent pas le runtime. `OTEL-100` constitue le premier et unique commit
de bascule : il doit être complet, testable et réversible en bloc. Aucun commit intermédiaire déployable ne doit
retirer Prometheus/Grafana sans SigNoz fonctionnel, ni activer les deux chaînes simultanément.

## 18. Définition de terminé

- [ ] La télémétrie de tous les services actifs transite par OTLP et le Collector.
- [ ] Les métriques, traces et logs autorisés sont corrélés de bout en bout.
- [ ] Les six dashboards et neuf alertes ont une parité approuvée.
- [ ] Les parcours local macOS et GKE sont testés en conditions nominales et dégradées.
- [ ] Les SLO d'ingestion, de perte, de latence et de notification sont respectés.
- [ ] Aucun contenu sensible ou attribut à cardinalité non bornée n'est exporté.
- [ ] Prometheus et Grafana ne figurent plus dans la configuration active, les dépendances ou les interfaces.
- [ ] Le rollback complet de version a été testé puis ses artefacts ont été retirés après la fenêtre de stabilité.
- [ ] Les runbooks, la formation, la sécurité et l'exploitation sont validés.
- [ ] La migration est approuvée par les propriétaires application, plateforme, sécurité et exploitation.

## 19. Références de travail

- [ ] Maintenir les liens vers les documentations officielles OpenTelemetry, Spring Boot et Google Cloud dans l'ADR.
- [ ] Enregistrer les versions exactes consultées lors de chaque choix technique.
- [ ] Vérifier à chaque montée de version les conventions sémantiques, composants Collector et propriétés Spring.

Points de départ :

- [Stratégie OpenTelemetry du projet](../roadmap/strategie-opentelemetry.md)
- [Roadmap OpenTelemetry initiale](../roadmap/OPENTELEMETRY.md)
- [Configuration Prometheus actuelle](../../../infrastructure/observability/prometheus.yml)
- [Alertes actuelles](../../../infrastructure/observability/alerts/multiagents.yml)
- [Documentation OpenTelemetry](https://opentelemetry.io/docs/)
- [Configuration du Collector](https://opentelemetry.io/docs/collector/configuration/)
- [Spring Boot — observabilité](https://docs.spring.io/spring-boot/reference/actuator/observability.html)
- [SigNoz self-hosted avec Docker](https://signoz.io/docs/install/docker/)
- [Dashboards et alertes SigNoz basés sur OpenTelemetry](https://signoz.io/docs/userguide/custom-apm-dashboards-alerts/)
- [Google Cloud — OpenTelemetry](https://cloud.google.com/stackdriver/docs/instrumentation/opentelemetry)
