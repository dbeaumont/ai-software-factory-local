# TEMP-086 — sauvegarde et restauration de l'état Temporal

## Périmètre et cohérence

`scripts/backup-temporal-state.sh <répertoire-vide>` arrête d'abord les seuls écrivains (`orchestrator`, `temporal`,
`evidence-mcp` et `scm-delivery-mcp`). Il sauvegarde ensuite le registre d'idempotence SCM, les bases `temporal` et
`temporal_visibility`, la projection orchestrateur et le volume Evidence, puis génère un manifeste SHA-256, le commit
source et l'horodatage UTC. Un trap redémarre les écrivains même en cas d'échec.

La sauvegarde contient des preuves potentiellement sensibles : elle doit être chiffrée, à accès restreint et ne doit
jamais être ajoutée à Git.

## Ordre de restauration

L'ordre obligatoire est :

1. le registre SCM, afin qu'un effet déjà observé reste idempotent avant toute reprise ;
2. Evidence MCP, afin que toutes les références de l'historique soient résolubles ;
3. les bases Temporal, qui constituent l'autorité d'exécution ;
4. la base orchestrateur, projection reconstruisible de cette autorité.

`scripts/restore-temporal-state-isolated.sh <sauvegarde> <préfixe>` applique cet ordre, valide le manifeste avant
toute création, refuse un volume préexistant, restaure les deux PostgreSQL dans des conteneurs sans réseau et vérifie
que les nombres de tables et fichiers restaurés correspondent exactement au snapshot source (y compris pour une
projection ou un magasin Evidence encore vide). Il ne possède aucun chemin permettant d'écraser les volumes actifs.

## Qualification locale

Exécuter `./scripts/test-temporal-backup-restore.sh`. Le test conserve la sauvegarde sous `/private/tmp` pour audit,
restaure dans quatre volumes à préfixe unique, vérifie ceux-ci puis les supprime. Après restauration en situation
d'incident, l'orchestrateur ne doit être redémarré qu'après réconciliation des effets SCM à issue inconnue ; leurs
clés d'idempotence restent celles restaurées dans la projection et aucun rejeu automatique n'est autorisé.

La dernière campagne du 6 septembre 2026 est **PASS**. Le manifeste et ses onze entrées ont été vérifiés, puis la
restauration isolée a reproduit exactement les 2 fichiers d'état SCM, les 40 tables Temporal, les 3 tables de
visibilité et l'état local encore vide de la projection et d'Evidence. La sauvegarde de qualification est conservée
dans `/private/tmp/ai-factory-temporal-backup-test-nYIL9j` ; les quatre volumes de restauration ont été supprimés.
