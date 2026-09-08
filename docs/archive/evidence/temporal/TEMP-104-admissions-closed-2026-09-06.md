# TEMP-104 — Fermeture des admissions

> Résultat : `PASS`
>
> Fenêtre ouverte par : gate TEMP-103 approuvé sur `9698fa30aa16a43af5b2d4f56b82f1953eb95080`
>
> Horodatage du verrou : `2026-09-06T06:38:10.491533Z`

## Contrôle déployé

- Flyway V015 crée un verrou global durable dans la base de projection.
- L'application ne possède aucune API d'écriture de ce verrou : seules les commandes opérateur
  `make admissions-close` et `make admissions-open` modifient son état via PostgreSQL.
- `CutoverTicketAdmissionGate` vérifie le verrou hors thread Reactor avant de déléguer au gate Temporal qualifié.
- L'interface lit l'état dans `/api/capabilities`, désactive la soumission et affiche la maintenance.
- L'état fermé survit aux redémarrages de l'orchestrateur et reste indépendant de l'image applicative.

## Preuves d'exécution

| Contrôle | Résultat |
|---|---|
| Gel `temporal-cutover-v1` | `PASS` sur le baseline `9698fa30aa16a43af5b2d4f56b82f1953eb95080` |
| Migration Flyway | V015 `durable admission control`, `success=true` |
| État opérateur | `admissions_open=false`, `reason=temporal_cutover`, `revision=2` |
| API de capacité | `admissionsOpen=false`, `admissionReason=temporal_cutover`, `admissionRevision=2` |
| Nouvelle soumission | `POST /api/tasks` renvoie HTTP `503` |
| Admissions à réconcilier | `0` ligne `PENDING` dans `task_admission_outbox` |
| Exécutions actives | la requête Temporal `ExecutionStatus="Running"` retourne `[]` |
| Moteur local | `DeterministicWorkflowCoordinator` n'est pas un composant Spring ; aucune exécution locale active |
| Interface | bannière `maintenance-banner` présente dans l'image web déployée |
| Tests Java | `560` exécutés, `0` échec, `0` erreur, `1` ignoré |
| Tests complémentaires | syntaxe Bash et JavaScript valides ; tests ciblés `10/10` |

Les statuts non terminaux visibles dans certaines anciennes projections sont des instantanés historiques de tests
dont les exécutions Temporal sont closes. L'autorité Temporal ne contient aucun workflow ouvert ; aucune tâche
locale ne devait donc être attendue ou annulée au moment de la fermeture.

## Artefacts locaux déployés

- orchestrateur : image `85ba020303af` ;
- interface : image `59beb07a9c24`.

Ces identifiants sont locaux au moteur Docker macOS et complètent, sans remplacer, l'identité Git de la fenêtre.
