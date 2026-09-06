# A2A-182 — Sauvegarde des états de cutover

- Date UTC : `2026-09-06T22:57:44Z`
- Commit sauvegardé : `949811bafa262ea76f9216d942842480bd81593e`
- Build ID Temporal : `0.1.0`
- Répertoire local protégé : `.local/cutover-backups/a2a-20260906-949811b`
- Taille : `22M`
- SHA-256 du manifeste : `d5eb49705733c2af2de6f9a6c5993c3cda842b1fe10f3e760135d670a426d40e`
- Décision : `PASS`

## Périmètre sauvegardé

- persistence et visibilité Temporal par les API PostgreSQL supportées, sans lecture des tables internes par
  l'application ;
- 26 tables de projections orchestrateur, dont admissions et corrélations A2A ;
- 6 tables de tâches, messages et notifications A2A ;
- 1 806 fichiers Evidence et 11 fichiers du ledger SCM idempotent ;
- 111 tables Gitea, 442 fichiers Gitea et 4 840 fichiers de workspaces ;
- configuration Compose, contrats/cartes A2A, PKI, secrets locaux et preuves de qualification ;
- image IDs de l'orchestrateur et des quatorze services d'agents, plus le Build ID Temporal.

Le snapshot arrête tous les écrivains au même point. Le redémarrage automatique n'est déclaré réussi qu'après
le retour sain des services.

## Vérification de restauration

`make restore-temporal-cutover` a restauré la sauvegarde sous le préfixe isolé
`ai-factory-cutover-restore-a2a-949811b`. Tous les digests du manifeste ont été vérifiés, les nombres de tables et
de fichiers restaurés correspondent à la source, et la gate d'admission restaurée vaut
`false|temporal_cutover|6`.

Les volumes actifs n'ont pas été modifiés. Les volumes isolés sont conservés localement comme copie de reprise
immédiatement inspectable.
