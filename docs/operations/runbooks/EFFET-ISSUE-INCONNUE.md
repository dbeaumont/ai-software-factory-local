# Runbook — activité à effet dont l'issue est inconnue

## Règle absolue

Une perte d'accusé de réception ne prouve pas l'échec. Suspendre l'exécution concernée et ne jamais relancer une
création de branche, commit, push, PR ou autre effet pour « voir si cela passe ». Préserver l'historique Temporal,
le manifeste approuvé et la clé d'idempotence.

## Réconciliation

1. Relever task ID, attempt ID, workflow/run ID, activity ID, type d'effet, clé d'idempotence et digest de manifeste.
2. Interroger le système cible en lecture seule avec cette clé et les identifiants stables. Pour Gitea, contrôler
   branche, commit et PR sans créer ni modifier d'objet.
3. Classer l'issue `CONFIRMED`, `ABSENT` ou `STILL_UNKNOWN` et journaliser les éléments probants par digest/URI.
4. `CONFIRMED` : enregistrer le résultat existant sans réexécuter l'effet. `ABSENT` : autoriser un retry Temporal avec
   la même clé uniquement si le contrat idempotent le permet. `STILL_UNKNOWN` : maintenir le gel et escalader.
5. Invalider toute approbation dont le manifeste, la tentative ou le digest ne correspond plus exactement.

La clôture exige un état cible déterminé, aucune ressource dupliquée, preuve de réconciliation et cohérence entre
Temporal, projection, Evidence et Gitea. Sécurité participe si l'effet dépasse le scope ou si l'audit est incomplet.
