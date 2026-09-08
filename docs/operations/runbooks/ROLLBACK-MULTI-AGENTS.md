# Runbook — confinement et rollback multi-agent

## Objectif

Fermer les nouvelles admissions, empêcher tout effet non réconcilié et restaurer un build Temporal compatible.
Il n'existe aucun fallback vers un ancien parcours métier. La politique autoritative est
[`rollback-policy-v1.yaml`](../../../resources/multiagents/policies/rollback-policy-v1.yaml).

## Déclenchement immédiat

Déclencher le confinement pour tout effet non autorisé ou dupliqué, escalade de permission ou de scope, fuite de
secret, accès inter-tâches, preuve ou approbation invalide, contrat invalide accepté ou révocation de
qualification. Les seuils de SLO, coût, saturation et non-progression Temporal définis dans la politique sont
également bloquants.

Une issue inconnue est considérée comme potentiellement exécutée. La réconcilier par sa clé d'idempotence auprès
du système propriétaire ; ne jamais répéter l'effet pour le vérifier.

## Confinement immédiat

1. Ouvrir l'incident et relever l'heure, le commit, les digests d'images, le Build ID et les tâches en vol.
2. Fermer les admissions avec `make admissions-close`.
3. Activer `global.disabled=true` dans le fichier du kill switch si des appels MCP doivent être bloqués, ou cibler
   le rôle, l'outil ou le serveur affecté.
4. Arrêter les nouvelles délégations et geler les effets externes non confirmés.
5. Préserver historiques Temporal, projections PostgreSQL, références Evidence, workspaces et journaux.
6. Inventorier les workflows ouverts, leurs Build IDs, phases, activités et effets à issue inconnue.

## Tentatives en vol

| État observé | Action |
|---|---|
| aucun effet demandé | annuler ou suspendre |
| décision humaine attendue | suspendre ; invalider si le digest change |
| effet demandé, résultat inconnu | interroger le système cible avec la clé d'idempotence |
| effet confirmé | enregistrer, ne jamais répéter |
| historique incompatible | conserver ou restaurer le worker épinglé à son Build ID |

Une reprise fonctionnelle crée un nouvel `attempt_id` lié au même ticket. Elle ne réutilise pas implicitement une
décision ou approbation antérieure.

## Diagnostic

```bash
docker compose --env-file .env -f infrastructure/compose.yaml --profile a2a-full ps -a
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=200 orchestrator temporal
curl -fsS "http://localhost:${ORCHESTRATOR_PORT:-8088}/actuator/health"
make admissions-status
```

Déterminer le premier événement fautif, les tâches et effets concernés, puis vérifier le journal chaîné,
l'historique Temporal, les digests, manifestes, approbations et clés d'idempotence.

## Rollback de build

1. Identifier le dernier digest d'image et Build ID qualifiés pour tous les historiques concernés.
2. Vérifier le replay avec `make temporal-replay` avant restauration.
3. Redéployer l'image immuable précédente sans supprimer les volumes.
4. Conserver simultanément les Build IDs nécessaires au drainage.
5. Vérifier les pollers, la readiness, les projections et les dépendances MCP/A2A.
6. Garder les admissions fermées si aucun build compatible ne peut reprendre tous les historiques ouverts.

## Reprise

La reprise exige une cause racine, un périmètre d'impact, tous les effets réconciliés, une correction versionnée,
des tests de régression et un exercice de restauration réussi. Exploitation approuve toujours ; Sécurité et
Produit approuvent selon l'impact.

1. Déployer le build corrigé avec un nouveau Build ID.
2. Rejouer les historiques versionnés et vérifier les workflows épinglés.
3. Vérifier l'absence de duplication A2A, Evidence et SCM.
4. Retirer les coupe-circuits ciblés, en conservant les admissions fermées.
5. Rouvrir avec `make admissions-open` seulement après réussite de la barrière de readiness.
6. Observer au moins deux cycles complets de réconciliation.

## Clôture

Archiver la chronologie, les Build IDs, les tâches et effets concernés, les résultats de réconciliation, les
preuves de correction, de replay et de restauration, ainsi que les approbations. Un état inconnu, une preuve
invérifiable ou un effet non réconcilié interdit la clôture.
