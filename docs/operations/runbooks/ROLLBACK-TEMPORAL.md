# Runbook — rollback après bascule franche Temporal

## Principe

Il n'existe plus de coordinateur local supporté. Un rollback ne modifie jamais l'autorité d'exécution : il remet en
service un build worker compatible avec les historiques existants ou restaure le triplet Evidence/Temporal/projection
après perte avérée. Toute nouvelle admission reste gelée pendant l'opération.

## Procédure

1. Identifier commit, build ID, types/queues et workflows affectés ; préserver logs, historiques et preuves.
2. Geler les admissions et les effets externes dont l'issue n'est pas confirmée.
3. Vérifier le replay des historiques représentatifs avec le dernier build qualifié.
4. Redéployer ce build avec son build ID d'origine et la même politique de queues/versionnement.
5. Pour une corruption/perte d'état seulement, vérifier le manifeste de sauvegarde puis suivre
   [TEMP-086](../../qualification/temporal/TEMP-086-BACKUP-RESTORE.md). Ne jamais restaurer la seule projection.
6. Réconcilier chaque effet inconnu par sa clé d'idempotence avant de laisser Temporal reprendre ses retries.
7. Contrôler pollers, backlog, non-déterminisme, projection et références Evidence avant réouverture.

Une restauration ne doit pas rejouer automatiquement de livraison SCM. Si la dernière sauvegarde précède un effet
confirmé dans Gitea, enregistrer d'abord cette réalité dans l'incident et traiter l'exécution comme issue inconnue.

## Sortie

La sortie exige vingt minutes stables, replay compatible, projections cohérentes, zéro effet non réconcilié et
approbation Exploitation. Un nouveau correctif reçoit un nouveau build ID et suit à nouveau la qualification complète.
