# ADR-MAH-004 — Propriété de l'état et des preuves

- Statut : accepté pour le prototype de migration
- Date : 2026-09-02
- Portée : Temporal, projection métier, workspaces et Evidence MCP

## Contexte

Une architecture hiérarchique manipule plusieurs catégories de données : état de coordination, vues destinées à
l'API, fichiers de travail, sorties d'agents et preuves techniques. Les dupliquer sans règle de propriété rendrait
la reprise ambiguë et pourrait injecter des contenus sensibles dans l'historique du moteur de workflow.

## Décision

Chaque catégorie possède une source d'autorité unique.

| Catégorie | Source d'autorité | Contenu autorisé |
|---|---|---|
| Coordination | Historique Temporal | États, identifiants, décisions, timers, signaux et références d'artefacts |
| Lecture API/UI | PostgreSQL métier | Projection reconstruisible, statuts, DAG, coûts et métadonnées non sensibles |
| Artefacts et preuves | Evidence MCP | Contenu immuable, digest, classification, rétention et audit de lecture |
| Travail temporaire | Workspace ou worktree | Clone, patch en cours et résultats intermédiaires non autoritatifs |
| Code livré | SCM | Commit et draft PR créés après approbation |
| Politique | Fichiers/policy store versionnés | Permissions, limites, gates et règles de routage |

## Règles de stockage

1. L'historique Temporal ne contient pas le code, les prompts, les logs complets, les patches ou rapports bruts.
2. Les payloads Temporal portent des identifiants, petites décisions structurées, URI internes et digests.
3. PostgreSQL métier est une projection de lecture ; il ne décide pas de la reprise d'un workflow.
4. Evidence MCP vérifie le digest avant stockage et interdit la mutation d'un artefact existant.
5. Un workspace peut être recréé depuis le commit source et les artefacts autorisés ; sa présence n'est jamais une
   condition suffisante de reprise.
6. Une décision finale référence les versions exactes du DAG, des contrats, prompts, modèles, politiques et preuves.
7. Les contenus non fiables restent classifiés comme tels jusqu'à validation par un contrôle déterministe.

## Identifiants de corrélation

- `task_id` : identité métier stable ;
- `workflow_id` et `workflow_run_id` : identité et exécution Temporal ;
- `attempt_id` : tentative globale liée au commit source ;
- `delegation_id` : nœud stable du DAG ;
- `agent_run_id` : invocation concrète d'un agent ;
- `execution_id` : job technique sandbox ;
- `manifest_id` : ensemble final de preuves soumis à approbation.

## Cohérence et reprise

- les écritures de projection utilisent des événements idempotents identifiés par leur événement source ;
- les références d'artefacts sont acceptées uniquement après vérification URI, tâche, tentative et digest ;
- une projection perdue est reconstruite depuis Temporal et les métadonnées Evidence MCP ;
- un artefact perdu ou altéré bloque la décision et ne peut pas être recréé silencieusement comme preuve historique ;
- l'approbation humaine est liée au `manifest_id` et devient invalide si le patch ou une preuve change.

## Conséquences

- la projection PostgreSQL peut évoluer sans migrer l'historique complet des workflows ;
- les workflows doivent éviter les payloads volumineux ;
- la restauration comporte deux procédures coordonnées : moteur de workflow et stockage de preuves ;
- l'UI utilise la projection mais affiche les statuts de vérification des preuves.

## Alternatives écartées

- **Tout stocker dans Temporal** : historique volumineux, risque de données sensibles et rétention mal adaptée.
- **Tout stocker dans PostgreSQL métier** : mélange de coordination, projection et artefacts, avec reprise fragile.
- **Utiliser le workspace comme mémoire partagée** : mutations concurrentes, faible traçabilité et perte possible.
