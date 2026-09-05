# ADR-TEMP-001 — Temporal comme moteur unique d'orchestration

- Statut : accepté
- Date : 2026-09-05
- Portée : admission et exécution des tickets de l'AI Software Factory

## Contexte

Le dépôt contient un pipeline local coordonné par `DeterministicWorkflowCoordinator` ainsi que des workflows
Temporal testés, mais non raccordés au parcours public. Les modes d'exécution multi-agent décrivent la stratégie
métier d'une tâche ; ils ne doivent pas choisir son moteur technique.

## Décision

1. Temporal devient l'unique moteur d'orchestration des tickets après la fenêtre de bascule.
2. Le moteur n'est ni sélectionnable par un ticket, ni modifiable par une sortie de modèle.
3. `executionMode` reste une dimension métier indépendante : il décrit la stratégie d'agents exécutée à
   l'intérieur du workflow Temporal.
4. L'orchestrateur ne conserve aucun routage `LOCAL`/`TEMPORAL`, aucun shadow et aucun canary de moteur.
5. La configuration, l'API, les métriques et l'interface distinguent explicitement moteur d'orchestration et mode
   métier tant que les anciens enregistrements doivent encore être lus.
6. `DeterministicWorkflowCoordinator` est retiré dans la release de coupure, après extraction de ses étapes en
   activités réutilisables et qualification hors trafic.

Cette décision remplace les dispositions transitoires de `ADR-MAH-001`, `ADR-MAH-002` et `ADR-MAH-005` qui
maintenaient un coordinateur local, une activation progressive ou un moteur dépendant du mode hiérarchique.

## Conséquences

- Temporal est une dépendance obligatoire de la readiness et de l'admission.
- Une indisponibilité Temporal ferme les admissions au lieu de déclencher un fallback local.
- Le rollback restaure une version compatible des workers Temporal ; il ne change pas de moteur.
- Les tests du pipeline local restent une baseline avant coupure, mais ne constituent pas un chemin de production
  après la migration.

## Autorités de données

Chaque donnée possède une autorité unique. Les copies sont des projections ou des caches et ne peuvent pas être
utilisées seules pour décider d'une reprise ou d'un effet.

| Donnée | Autorité | Rôle des autres stockages |
|---|---|---|
| Chronologie, timers, retries et signaux | Historique Temporal | PostgreSQL en conserve une projection interrogeable. |
| État affiché des tâches et délégations | PostgreSQL applicatif | Reconstruction depuis Temporal et les références Evidence. |
| Artefacts, rapports et manifestes | Evidence MCP | Temporal ne conserve qu'URI, digest, taille, type et verdict. |
| Source et livraison | Gitea | Le workspace est une copie temporaire liée à un commit attesté. |
| Fichiers de travail | Workspace de tâche | Aucun statut durable n'est déduit de leur seule présence. |
| Politiques et contrats | Dépôt versionné | Leur version et digest sont attachés à l'exécution. |

La base PostgreSQL interne de Temporal ne fait pas partie du modèle de données applicatif. Evidence MCP ne
reconstruit pas une chronologie et PostgreSQL applicatif ne commande jamais directement un effet.

### Frontière d'accès Temporal

- le client et les workers utilisent exclusivement le SDK/API Temporal ;
- les projections lisent les historiques avec `WorkflowClient` ;
- aucun compte, URL JDBC, table ou migration du stockage interne Temporal n'est fourni à l'application ;
- les sauvegardes Temporal sont opérées comme une unité d'infrastructure, hors du code applicatif ;
- un test d'architecture bloque les références aux hôtes et schémas internes connus.

## Workflow racine et versionnement

Le workflow public de production est introduit sous le type immuable `SoftwareFactoryExecutionWorkflowV1`.
`SoftwareFactoryWorkflow` reste un contrat de préparation multi-agent tant que ses responsabilités n'ont pas été
intégrées au nouveau workflow racine ; il n'est pas utilisé comme point d'admission de production.

Les règles suivantes s'appliquent :

1. le nom de type `SoftwareFactoryExecutionWorkflowV1` n'est jamais réaffecté à une sémantique incompatible ;
2. les évolutions compatibles utilisent Worker Versioning et des Build IDs explicites ;
3. une branche de code dont le résultat de commandes déjà planifiées change utilise `Workflow.getVersion` ;
4. une rupture de contrat d'entrée ou de résultat introduit un type `V2` et conserve les workers `V1` jusqu'au
   drainage complet ;
5. chaque release rejoue les historiques versionnés avant publication de l'image worker.

Ce choix évite de donner une autorité de production rétroactive au workflow expérimental déjà présent et permet
de conserver ses historiques de test comme fixtures indépendantes.

## Vérification

- aucun contrat public ne permet de choisir le moteur ;
- chaque nouvelle tâche post-bascule possède un Workflow ID Temporal ;
- les modes métier restent portés par des données validées par l'hôte ;
- aucun bean local alternatif n'est disponible dans la release finale.
