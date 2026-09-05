# Matrice de compatibilité Temporal

## Combinaison qualifiée

| Composant | Version | Statut | Justification |
|---|---:|---|---|
| Temporal Server | `1.31.2` | Qualifiée | Version épinglée par digest dans Compose ; handshake local `SERVING` et `ServerVersion 1.31.2`. |
| Temporal Java SDK | `1.38.0` | Qualifiée | Version Maven explicite ; compilation et tests des workflows avec le serveur de test embarqué. |
| Temporal CLI/admin-tools | `1.31.2` | Qualifiée | Version alignée sur le serveur ; utilisée pour le health check et l'administration du namespace. |
| Temporal UI | `2.53.0` | Qualifiée pour Server `1.31.2` | Dépasse le minimum `2.38.0` requis par Worker Versioning. |
| Worker Versioning | Server `1.31.2` + SDK Java `1.38.0` | Qualifiée | Dépasse les minimums officiels Server `1.29.1` et Java `1.29` ; API de déploiement testée à la compilation. |

La qualification porte sur les fonctions utilisées par l'orchestrateur : workflows et child workflows, activités,
signaux, requêtes, Continue-As-New, Workflow Pinning et Worker Deployment Version. Les fonctions plus récentes non
utilisées ne sont pas couvertes par cette matrice.

## Politique de compatibilité

- Les quatre versions du tableau sont des paramètres de release : leur mise à jour exige les tests de ce document.
- Le workflow racine et ses child workflows utilisent `PINNED`. Une version déjà démarrée reste donc attachée à son
  Worker Deployment Version jusqu'à sa fin ou à une transition Continue-As-New explicitement compatible.
- Une mise à jour ne doit jamais supprimer un worker tant que Temporal signale des exécutions épinglées sur sa
  version.
- Un changement incompatible du code workflow exige un nouveau type de workflow ou une transition déterministe
  versionnée. Une simple mise à jour de dépendance ne vaut pas preuve de compatibilité des historiques.

## Vérifications reproductibles

Depuis la racine du dépôt :

```bash
make config
mvn -q -s /private/tmp/ai-factory-empty-maven-settings.xml \
  -Dmaven.repo.local=/tmp/ai-factory-sandbox-m2 \
  -f apps/orchestrator/pom.xml \
  -Dtest=TemporalSdkVersionTest,TemporalComposeTest test
docker compose --env-file .env -f infrastructure/compose.yaml \
  run --rm --no-deps --entrypoint temporal temporal-namespace \
  operator cluster health --address temporal:7233
docker compose --env-file .env -f infrastructure/compose.yaml \
  run --rm --no-deps --entrypoint temporal temporal-namespace \
  operator cluster system --address temporal:7233
```

Résultat de référence du 5 septembre 2026 : santé `SERVING`, serveur annoncé `1.31.2`, schedules, upsert memo et
eager workflow start supportés.

## Sources de référence

- [Worker Versioning — versions minimales et comportements](https://docs.temporal.io/production-deployment/worker-deployments/worker-versioning)
- [Temporal Java SDK 1.38.0](https://github.com/temporalio/sdk-java/releases/tag/v1.38.0)

