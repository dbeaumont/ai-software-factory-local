# TEMP-109 — Smoke test de coupure

> Résultat : `PASS`
>
> Date : `2026-09-06`
>
> Ticket : `badf822f` / `AF-0125`
>
> Workflow : `ai-factory/badf822f/pipeline-1`
>
> Run : `01a0759b-2e6d-7158-b34f-c72fa4da01a1`

## Admission contrôlée

Le mode `TEMPORAL_CUTOVER_SMOKE=true` du test de livraison :

1. exige que le verrou global soit fermé ;
2. ouvre les admissions uniquement pour le `POST` synthétique ;
3. referme immédiatement le verrou, y compris par trap en cas d'erreur ;
4. exécute ensuite le workflow complet pendant que l'interface reste en maintenance.

Le verrou est revenu à `admissions_open=false`, motif `temporal_cutover`, révision `4`.

## Parcours validé

| Contrôle | Résultat |
|---|---|
| État Temporal | `COMPLETED` |
| Projection applicative | `PR_CREATED`, position `1 150` |
| Tests | `PASSED` |
| Qualité SonarQube | `PASSED` |
| Sécurité | `PASSED` |
| SBOM | `COMPLETE` |
| Revue indépendante | `ACCEPT` |
| Manifeste | `6f759df2ec1a8f7d6971da9253b36494b4d2b39fa5b4828f7919bde126998624` |
| Effet SCM | PR Gitea `#7`, créée exactement une fois |
| Idempotence | une seconde approbation du même manifeste ne crée aucune PR supplémentaire |
| Evidence MCP | `8` artefacts `COMPLETE`, métadonnées du patch relues par l'API |

## Nettoyage borné

Les objets nécessaires à l'audit sont conservés : historique Temporal, projection PostgreSQL, ledger SCM,
manifeste et preuves Evidence. Seuls les objets explicitement jetables du smoke ont été nettoyés :

- PR Gitea `#7` fermée, non fusionnée ;
- branche `ai-factory/badf822f-pipeline-1` supprimée, vérifiée par HTTP `404` ;
- workspace `/workspace/tasks/badf822f` supprimé et vérifié absent ;
- fichiers de réponse temporaires locaux supprimés par trap.

Le premier appel de nettoyage a révélé un digest BusyBox tronqué dans le nouveau mode opérateur. Docker a refusé
l'opération avant tout montage ; le digest a été corrigé depuis Compose et le nettoyage exact a ensuite réussi. Le
parcours fonctionnel, l'idempotence SCM et la fermeture de la PR étaient déjà terminés avant cette correction.
