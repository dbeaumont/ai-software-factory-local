# Clôture de la migration Temporal

> Résultat : `PASS`
>
> Date : `2026-09-06`
>
> Périmètre : environnement local macOS avec Docker Compose

## État final

- Temporal est l'unique moteur de workflow de production ;
- `TemporalWorkflowCoordinator` est la seule implémentation de production de `WorkflowCoordinator` ;
- PostgreSQL fournit la projection durable utilisée par l'API et l'interface ;
- Evidence MCP conserve les contenus immuables et leurs digests ;
- les admissions sont ouvertes globalement avec `normal_operation`, révision `5` ;
- l'image orchestrateur active est
  `sha256:59717e13f82355352f73a8eca8e9c17219f9c8079b94c3ec693f398f08b7d374` ;
- les sept task queues ont un poller et Temporal UI reste exposée uniquement sur `127.0.0.1:8233` ;
- SigNoz contient 784 métriques, 7 dashboards gérés et 15 règles d'alerte ;
- l'hypercare de 313 secondes a terminé 19 échantillons sans violation.

## Sonde après réouverture

Une sonde distincte du smoke de maintenance a été admise après TEMP-110 :

| Champ | Valeur |
|---|---|
| Task ID | `faa6b5a7` |
| Attempt ID | `pipeline-1` |
| Workflow ID | `ai-factory/faa6b5a7/pipeline-1` |
| Run ID | `01a075aa-5c72-7925-841e-e4b5f802d650` |
| Projection terminale | `CANCELLED` |
| État Temporal terminal | `COMPLETED` |
| Effet SCM | aucun |
| Workspace jetable | supprimé et vérifié absent |

La réponse de `POST /api/tasks` contenait immédiatement l'Attempt ID et le Run ID. L'annulation a été transmise au
même workflow Temporal, puis projetée sans exécution locale. Cette unique nouvelle admission observée après
réouverture possède donc une identité Temporal complète.

## Traçabilité et documentation

Tous les tickets TEMP ont un commit dédié portant leur identifiant. TEMP-102 archive la qualification complète,
TEMP-103/TEMP-103R les décisions, TEMP-104 à TEMP-111 les preuves de la fenêtre et de l'ouverture. Les documents
d'état courant ont été réalignés sur Temporal et `PostgresTaskMemory`; les références au coordinateur local ne
subsistent que dans l'état initial, les décisions historiques, les archives et les preuves de suppression.

La section rollback du plan reste volontairement décochée : c'est une checklist conditionnelle à appliquer en cas
d'incident, pas une suite d'actions à provoquer sur une bascule saine. Son exécution a été répétée hors trafic lors
de TEMP-102 et ses prérequis sont disponibles dans TEMP-111.
