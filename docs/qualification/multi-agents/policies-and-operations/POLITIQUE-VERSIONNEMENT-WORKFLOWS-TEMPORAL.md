# Politique de versionnement des workflows Temporal

- Statut : cible v1
- Date : 2026-09-02
- Portée : workflows et workers de l'usine logicielle multi-agent
- Propriétaires : Architecture et Exploitation

## Décision

La production utilise **Worker Versioning** avec des déploiements de workers immuables de type rainbow. Les
workflows racines et les Child Workflows sont `PINNED` à la Worker Deployment Version qui les a démarrés. Les
tâches longues changent de version uniquement à une frontière explicite `Continue-As-New`.

Tant que l'automatisation Worker Versioning n'est pas disponible dans un environnement, tout changement du
code d'orchestration qui modifie l'historique doit utiliser `Workflow.getVersion`. Un déploiement rolling sans
Worker Versioning ni patch déterministe est interdit.

Cette décision suit les recommandations Temporal actuelles : Worker Versioning est le mode privilégié pour les
déploiements de code Workflow, et le couple `PINNED` plus mise à niveau sur `Continue-As-New` est adapté aux
workflows longs. Les versions retenues pour le prototype satisfont les minima documentés (Server 1.31.2,
Java SDK 1.38.0 et UI 2.53.0).

## Identité d'un déploiement

| Élément | Convention | Exemple |
|---|---|---|
| Worker Deployment | service et environnement stables | `software-factory-prod` |
| Build ID | digest immuable de l'image et commit Git | `sha256-12ab…-git-a1b2c3d` |
| Workflow Type | nom stable tant que le contrat reste compatible | `SoftwareFactoryWorkflow` |
| Change ID de patch | ADR ou tâche, nom sémantique, jamais réutilisé | `MAH-123-parallel-review-v1` |

Une même Worker Deployment Version doit exécuter exactement le même artefact sur toutes ses task queues. Un
Build ID n'est jamais réaffecté à une autre image.

## Classification des changements

| Changement | Traitement obligatoire |
|---|---|
| Activity seulement, signature et sémantique compatibles | nouveau worker ; aucune branche Workflow requise |
| Ajout compatible à un payload | champ optionnel avec valeur par défaut ; test de sérialisation ancien/nouveau |
| Ordre, nombre ou type de commandes Workflow modifié | version pinning ou `Workflow.getVersion` |
| Signal ou Query ajouté | conserver les anciens handlers ; documenter le comportement avant/après activation |
| Signal, Activity ou champ renommé/supprimé | migration en deux déploiements ; conserver l'ancien contrat jusqu'au drainage |
| Rupture majeure du contrat ou de la sémantique | nouveau Workflow Type suffixé (`...V2`) et coexistence explicite |

## Règles de patching

1. Le Change ID est une constante stable liée à une tâche ou ADR et n'est jamais recyclé.
2. La première version utilise `Workflow.getVersion(changeId, Workflow.DEFAULT_VERSION, 1)` et conserve les
   deux branches tant qu'un historique antérieur peut être rejoué.
3. Chaque branche est couverte par un test de replay avec un historique représentatif et expurgé.
4. Après drainage des exécutions anciennes **et expiration de leur rétention**, la branche historique peut être
   retirée ; l'appel reste temporairement sous la forme `Workflow.getVersion(changeId, 1, 1)`.
5. Le retrait final du marqueur exige une preuve qu'aucun historique rejouable ne dépend du Change ID.
6. L'option d'indexation automatique `TemporalChangeVersion` est activée lors de l'enregistrement des workflows
   afin de permettre l'inventaire des versions actives.

## Procédure de déploiement

1. Construire une image immuable et produire son Build ID.
2. Exécuter tests unitaires, tests Temporal, test de déterminisme et replay d'historiques de référence.
3. Démarrer la nouvelle Worker Deployment Version sans arrêter les anciennes.
4. Vérifier qu'elle poll toutes les task queues déclarées et que ses métriques sont saines.
5. Affecter d'abord 5 % des nouveaux workflows à la version ramping, puis 25 %, 50 % et 100 %. Chaque palier
   exige une fenêtre d'observation sans erreur de replay, backlog anormal ni hausse des échecs d'Activity.
6. Promouvoir la version comme Current. Les exécutions `PINNED` existantes restent sur leur version initiale.
7. Conserver chaque ancienne version jusqu'à drainage de ses workflows, de ses Activities et de ses tâches
   compatibles avec une reprise tardive.
8. Archiver la preuve de promotion, le Build ID, les métriques et l'inventaire des versions encore actives.

## Rollback et correction urgente

- Avant 100 %, ramener immédiatement le trafic ramping à 0 % et garder l'ancienne Current.
- Après promotion, restaurer l'ancienne version comme Current pour les nouvelles exécutions ; les workflows
  déjà `PINNED` sur la version fautive continuent sur celle-ci.
- Pour ces exécutions, déployer un Build ID corrigé compatible et utiliser les mécanismes de redirection de
  workflows épinglés ; ne jamais remplacer l'image sous un Build ID existant.
- Une incompatibilité de replay interdit la promotion et déclenche l'arrêt du ramping, la conservation des
  historiques en cause et un correctif versionné.
- L'annulation ou la terminaison en masse de workflows n'est jamais un mécanisme normal de rollback.

## Retrait d'une version

Une Worker Deployment Version ne peut être arrêtée que si les quatre conditions suivantes sont prouvées :

- aucune exécution ouverte ne lui est épinglée ;
- aucune Activity ou Workflow Task ne reste en attente sur ses task queues ;
- les historiques nécessaires aux tests de replay ont été archivés et expurgés ;
- la durée de retour arrière convenue avec l'exploitation est écoulée.

Le retrait des anciennes branches `getVersion` suit en plus la rétention du namespace. Une alerte doit empêcher
la suppression du dernier worker capable de rejouer une version encore active.

## Contrôles CI et preuves attendues

- test d'architecture MAH-073 ;
- tests des deux branches de chaque Change ID ;
- replay automatisé d'un corpus d'historiques sur l'image candidate ;
- test de compatibilité des payloads et Signals ;
- vérification de l'unicité des Build IDs et Change IDs ;
- manifeste de déploiement reliant commit, image, Build ID, SDK, Server et schémas ;
- rapport de drainage avant retrait.

## Références

- [Versioning — Temporal Java SDK](https://docs.temporal.io/develop/java/workflows/versioning)
- [Worker Versioning — Temporal](https://docs.temporal.io/production-deployment/worker-deployments/worker-versioning)

