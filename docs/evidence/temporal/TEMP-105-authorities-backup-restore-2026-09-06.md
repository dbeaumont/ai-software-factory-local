# TEMP-105 — Sauvegarde et restauration des autorités

> Résultat : `PASS`
>
> Source : `7fa5919b59ca034b7e87d1f9769eda0c831f1f39`
>
> Création UTC : `2026-09-06T06:48:16Z`
>
> Sauvegarde locale privée : `/private/tmp/ai-factory-temporal-cutover-20260906T0645Z` (`18M`)

## Périmètre sauvegardé

| Autorité | Artefact | Contrôle de restauration |
|---|---|---|
| Temporal | `temporal.dump`, `temporal-visibility.dump` | `40 + 3` tables |
| Projection orchestrateur | `orchestrator.dump` | `24` tables ; verrou `false|temporal_cutover|2` |
| Evidence MCP | `evidence-state.tgz` | `1 714` fichiers |
| Idempotence SCM | `scm-delivery-state.tgz` | `9` fichiers |
| Gitea | `gitea.dump`, `gitea-data.tgz` | `111` tables et `392` fichiers |
| Workspaces utiles | `factory-workspace.tgz` | `4 643` fichiers |
| Configuration locale | `configuration.tgz` | `118` entrées, dont `.env` et `infrastructure/compose.yaml` |

La configuration contient des secrets locaux : le script applique `umask 077`, la sauvegarde reste hors Git et ne
doit pas être publiée. Tous les fichiers et le manifeste de la sauvegarde cœur sont couverts par
`manifest.sha256`.

## Restauration isolée

La commande suivante a restauré l'ensemble dans des conteneurs, volumes et un répertoire temporaire sans réseau :

```text
make restore-temporal-cutover \
  BACKUP_DIR=/private/tmp/ai-factory-temporal-cutover-20260906T0645Z \
  RESTORE_PREFIX=ai-factory-cutover-restore-temp105-20260906t0647z
```

Résultats :

- tous les SHA-256 sont valides ;
- toutes les quantités restaurées correspondent exactement à la source ;
- le verrou d'admission restauré reste fermé sur la révision `2` ;
- aucun volume actif n'a été monté en écriture ni modifié ;
- tous les services écrivains actifs ont redémarré sains ;
- les sept volumes de vérification jetables ont été supprimés après validation ;
- la sauvegarde privée source est conservée pour toute la fenêtre de coupure.

L'attente PostgreSQL de restauration exige désormais trois connexions SQL successives. Elle évite de confondre le
serveur temporaire d'initialisation de l'image officielle avec le postmaster stable.
