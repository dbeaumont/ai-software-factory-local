# TEMP-102 — Qualification de la release Temporal

- Date de fin : `2026-09-06T04:26:21Z`
- Commit gelé : `9698fa30aa16a43af5b2d4f56b82f1953eb95080`
- Image orchestrateur qualifiée :
  `sha256:ef7b416ef628b3629e21d597b62be85aedf480594f530d814635baf4442d604e`
- Commande : `make qualify-temporal-cutover`
- Résultat : `16/16` étapes réussies en `1 386 s`
- Invariant d'artefact : l'identifiant de l'image déployée est resté identique du début à la fin.

## Résultats

| Étape | Résultat et preuve principale |
|---|---|
| Gel | Manifeste `temporal-cutover-v1` vérifié sur le commit exact |
| Baseline | Corpus `deterministic-pipeline-v1.1.0-mcp` vérifié |
| Tests unitaires et architecture | Orchestrateur : 556 tests, 0 échec, 1 test explicitement ignoré ; tous les modules MCP passent sur `clean test` |
| Replay | Tous les historiques versionnés sont compatibles avec l'image qualifiée |
| Readiness Compose | Namespace, Temporal UI, application et 7 pollers actifs |
| Limites | Bornes globales et par task queue validées |
| Backpressure | 6 admissions, capacité 1, admission en 3 289 ms, backlog observé |
| Partition réseau | Readiness et admission en 503, aucune persistance parasite, récupération `UP` |
| Redémarrages orchestrateur | Même Run ID en `PLANNING`, `GENERATING_PATCH`, `APPLYING_PATCH`, `TESTING`, `QUALITY_SCANNING`, `SECURITY_SCANNING`, `REVIEWING` et `WAITING_APPROVAL` |
| Reprise heartbeat | Tâche `dbb98ced`, run `01a074ed-31b4-75bc-8f85-89236428fc87`, une seule soumission sandbox |
| Redémarrages stockage | Même workflow, même run et 99 événements après redémarrage Temporal/PostgreSQL |
| Pannes de dépendances | MCP, LiteLLM, Gitea, SonarQube, Artifactory, backend OTLP et Collector récupérés |
| Sauvegarde/restauration | 40 tables Temporal, 3 de visibilité, 23 de projection, 1 649 preuves et 7 états SCM restaurés sans modifier les volumes actifs |
| Livraison complète | Tâche `054a0420`, run `01a074f2-ce4f-7bfd-8d91-b6cd0823ba96`, PR Gitea `aiadmin/customer-api#5`, unicité vérifiée |
| Rotation/rollback worker | Tâche `2b6312c4`, run `01a074f3-9a6f-7c50-9c4c-e1c38edab8eb`, attente humaine 37 s, version nominale restaurée |
| Cycle Compose | Projection et historique conservés après `down --remove-orphans` / `up`, 93 événements, volumes retenus |

## Incidents détectés et corrigés avant la qualification finale

- La readiness inclut explicitement l'indicateur du moteur Temporal.
- Les arrêts de worker restent retryables sans être reclassés en panne de dépendance.
- Seules les activités configurées avec un timeout de heartbeat émettent des heartbeats.
- La restauration recrée le rôle PostgreSQL global requis par les ACL de projection.
- La remise en service après sauvegarde attend les healthchecks de tous les writers.
- SCM Delivery vérifie côté serveur le dépôt et les branches d'une PR existante, même si Gitea ignore ses filtres de requête.

La release est qualifiée hors trafic pour la bascule franche. Les validations humaines de coupure relèvent de
TEMP-103 et ne sont pas déduites automatiquement de cette preuve technique.
