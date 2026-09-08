# Runbook — kill switch et reprise après incident

> Le mécanisme de promotion par canary métier est retiré. Ce fichier conserve son nom pour ne pas casser les liens
> opératoires existants ; la procédure courante est un confinement fail-closed.

## Actionner le kill switch

L'orchestrateur relit avant chaque appel le fichier indiqué par `AI_FACTORY_MCP_KILL_SWITCH_FILE`. Il n'expose
aucune API d'écriture. Un fichier présent mais illisible ou sans `revision` coupe tous les appels.

```properties
revision=incident-<identifiant-unique>
global.disabled=false
servers.disabled=
tools.disabled=
roles.disabled=security-agent
```

Choisir le niveau le plus étroit qui contient sûrement l'incident : rôle, outil, serveur, puis global. Chaque
modification porte une nouvelle `revision`.

Après modification :

1. vérifier dans les métriques et journaux que les appels sont refusés avec la bonne révision ;
2. fermer les admissions si l'intégrité globale n'est pas garantie ;
3. inventorier les workflows et effets en vol ;
4. préserver fichier de contrôle, configuration, logs, historiques, projections et preuves ;
5. appliquer [ROLLBACK-MULTI-AGENTS.md](ROLLBACK-MULTI-AGENTS.md).

## Reprise après incident

La reprise nécessite la cause racine, l'ensemble des tâches affectées, les effets réconciliés, l'intégrité des
preuves, un correctif immuable, les tests de régression et un replay Temporal réussi.

1. Déployer le correctif dans un environnement isolé et rejouer le scénario d'incident.
2. Vérifier contrats, permissions, digests, idempotence et absence de secret.
3. Obtenir les approbations Exploitation, Sécurité et Produit requises par l'impact.
4. Restaurer ou déployer le Build ID qualifié.
5. Retirer progressivement les coupe-circuits ciblés sans rouvrir les admissions.
6. Rouvrir les admissions après réussite de la readiness et observer deux cycles de réconciliation.

## Clôture

La clôture archive l'incident, les versions et révisions, la chronologie, les tâches et effets concernés, les
métriques avant/après, les preuves de correction, de replay et de rollback, ainsi que les approbations.
