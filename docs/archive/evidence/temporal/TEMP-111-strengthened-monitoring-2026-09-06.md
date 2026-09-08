# TEMP-111 — Fenêtre de surveillance renforcée

> Résultat : `PASS`
>
> Date : `2026-09-06`
>
> Durée mesurée : `313 s`
>
> Échantillons : `19`

## Garde fail-closed

La cible `make monitor-temporal-cutover` vérifie périodiquement :

- l'identité exacte de l'image orchestrateur active ;
- la santé de l'orchestrateur, Temporal, Evidence MCP, OpenTelemetry Collector et SigNoz ;
- la readiness applicative et l'ouverture globale des admissions ;
- l'absence d'admission `PENDING` depuis plus d'une minute ;
- l'absence de nouvelle tâche projetée en échec ;
- l'absence d'augmentation du nombre de workflows Temporal en échec.

Toute violation appelle immédiatement `set-admissions.sh close` puis arrête la fenêtre en échec. Le contrôle initial
et final vérifie aussi le namespace, les Search Attributes, le Build ID, les sept pollers, les dashboards, les règles
d'alerte et la rétention SigNoz.

## Résultats

| Seuil | Baseline | Maximum observé | Résultat |
|---|---:|---:|---|
| Admissions `PENDING` depuis plus d'une minute | 0 | 0 | `PASS` |
| Nouvelles tâches `FAILED` depuis le début de la fenêtre | 0 | 0 | `PASS` |
| Workflows Temporal `Failed` historiques | 43 | 43 | `PASS`, aucune augmentation |
| Dérive de l'image active | aucune | aucune | `PASS` |
| Service obligatoire indisponible | aucun | aucun | `PASS` |

Les contrôles finaux Temporal et SigNoz ont réussi. Les admissions restent ouvertes avec `normal_operation`, révision
`5`.

## Disponibilité du rollback

| Élément | Valeur |
|---|---|
| Image active | `sha256:59717e13f82355352f73a8eca8e9c17219f9c8079b94c3ec693f398f08b7d374` |
| Image rollback compatible | `sha256:572b7125670071eb7230b2014d142f4c3efdb28a87974790f52489a8d6c0a7ea` |
| Source rollback | `7fa5919b59ca034b7e87d1f9769eda0c831f1f39` |
| Replay intégré à l'image rollback | `8/8`, zéro échec |
| Sauvegarde vérifiée | `/private/tmp/ai-factory-temporal-cutover-20260906T0645Z` |
| Runbook | `docs/operations/runbooks/ROLLBACK-TEMPORAL.md` |

L'image de rollback conserve Temporal comme seul composant de coordination actif et le verrou durable d'admission.
Elle ne doit être utilisée qu'après fermeture des admissions selon le runbook ; elle ne réactive aucun routage vers
le coordinateur local.
