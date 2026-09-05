# Runbook — projection orchestrateur incohérente

## Confinement et diagnostic

Suspendre les écritures opérateur sur la tâche, mais préserver la base : une projection incohérente est une preuve à
diagnostiquer, pas une autorité à corriger manuellement. Comparer `workflow_id`, `run_id`, task/attempt/source commit,
digests et références Evidence entre PostgreSQL, Temporal UI et Evidence MCP.

Un écart de digest, de lignée ou de portée inter-tâches est un incident Sécurité. Un historique expiré renvoie
`ProjectionHistoryUnavailableException` : ne pas reconstruire depuis Evidence seul et ne pas fabriquer une chronologie.

## Reconstruction contrôlée

1. Lancer d'abord l'API opérateur de reconstruction avec `dryRun=true`, une page de 100 tâches au maximum et un acteur
   nominatif.
2. Examiner chaque échec individuel ; aucun succès voisin ne transforme un échec en donnée partielle.
3. Si le preview est intègre, relancer avec `dryRun=false`. Le remplacement est atomique par snapshot.
4. Refaire la lecture UI et comparer le run ID, le statut, les délégations et tous les digests.

Ne jamais modifier directement les tables pour forcer la convergence. En cas de perte complète, restaurer Evidence
et Temporal avant la projection selon [TEMP-086](../../qualification/temporal/TEMP-086-BACKUP-RESTORE.md).

La clôture exige zéro alerte de digest, lag sous le seuil, audit des commandes présent et navigation valide vers
Temporal UI, SigNoz, Evidence et Gitea.
