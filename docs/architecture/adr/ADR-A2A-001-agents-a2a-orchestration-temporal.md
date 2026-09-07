# ADR-A2A-001 — A2A comme plan de données sous orchestration Temporal

- Statut : accepté et implémenté
- Date : 2026-09-06
- Portée : communication et exécution des agents de l'AI Software Factory
- Protocole cible : A2A 1.0

## Contexte

Les rôles `supervisor`, agents de domaine, sous-agents et `independent-reviewer` sont actuellement chargés dans la
JVM de l'orchestrateur. Leurs échanges sont des appels Java et des contrats JSON internes. Temporal orchestre déjà
le parcours des tickets, les délégations, les attentes humaines, les retries et les annulations, tandis que MCP
donne accès aux outils spécialisés.

Cette organisation rend les agents peu adressables depuis un autre runtime, couple leur cycle de vie à celui de
l'orchestrateur et ne fournit pas de frontière Agent2Agent interopérable. L'introduction d'A2A ne doit toutefois
pas créer un second ordonnanceur distribué ni permettre à une sortie de modèle de contourner les politiques de
délégation et les gates Temporal.

## Décision

1. A2A 1.0 devient l'unique protocole d'invocation des rôles `kind: agent` et `kind: sub-agent` après la coupure.
2. Temporal reste l'unique autorité de planification, séquencement, parallélisme, retry, timer, annulation,
   compensation et reprise.
3. L'orchestrateur et ses activités de contrôle jouent le rôle de client A2A. Chaque rôle d'agent expose une
   Agent Card et un serveur A2A indépendamment adressable.
4. Un agent peut produire une intention de délégation conforme à son contrat, mais ne contacte pas lui-même le
   rôle demandé. Le workflow Temporal valide le catalogue, `mayDelegateTo`, les budgets, le risque et le DAG avant
   d'émettre une nouvelle requête A2A.
5. Chaque tâche A2A est exécutée durablement par un `AgentTaskWorkflowV1` Temporal lié au rôle. Le workflow racine
   garde l'autorité du parcours métier ; le workflow d'agent garde l'autorité du cycle de vie de cette invocation.
6. Le serveur A2A maintient une projection durable permettant `GetTask`, `ListTasks`, l'idempotence et la reprise,
   sans lire directement la base interne de Temporal.
7. A2A transporte instructions, états et références d'artefacts. Les résultats volumineux restent dans Evidence
   MCP et ne sont présents dans A2A et Temporal que sous forme d'URI interne, digest, taille, type et verdict.
8. MCP reste l'unique protocole d'accès aux outils `context`, `sandbox`, `assurance`, `evidence` et `scm`. Une
   interface A2A ne transforme pas un outil MCP en agent et ne donne aucun accès implicite supplémentaire.
9. La bascule de production est franche : aucun chemin direct, shadow, canary ou fallback vers les agents en
   mémoire ne subsiste après la release de coupure.
10. Une indisponibilité A2A, une carte invalide ou une incompatibilité de version ferme l'admission ou suspend le
    workflow de manière explicite. Elle ne sélectionne jamais un autre moteur de communication.

## Topologie cible

```text
API / UI
   |
   v
SoftwareFactoryExecutionWorkflowV1 (Temporal, contrôle métier)
   |
   +--> activités A2A de résolution, envoi, réconciliation et annulation
           |
           v
       serveur A2A du rôle
           |
           +--> projection de tâche A2A
           +--> AgentTaskWorkflowV1 (Temporal, exécution du rôle)
                   |
                   +--> AgentRuntime du rôle
                   +--> outils MCP autorisés
                   +--> Evidence MCP
           |
           +--> notification authentifiée --> signal du workflow racine
```

## Frontière de décision

| Décision ou effet | Propriétaire | A2A peut-il le décider ? |
|---|---|---|
| Choisir le prochain agent | Workflow Temporal après validation | Non ; l'agent propose uniquement une intention. |
| Ordonner ou paralléliser le DAG | Workflow Temporal | Non. |
| Rejouer ou retenter une invocation | Workflow Temporal | Non ; le serveur déduplique la requête reçue. |
| Produire l'analyse du rôle | Agent adressé | Oui, dans son contrat et son budget. |
| Demander un outil | Agent adressé, sous contrôle de la matrice MCP | Oui, dans son allow-list. |
| Appliquer un patch ou créer une PR | Workflow et MCP propriétaire de l'effet | Non. |
| Demander une clarification | Agent via un état A2A structuré | Oui ; Temporal décide de la suite. |
| Annuler une tâche d'agent | Workflow Temporal via `CancelTask` | Non pour l'initiative ; oui pour l'exécution de l'annulation. |

## Autorités de données

| Donnée | Autorité | Projection ou référence |
|---|---|---|
| Chronologie du ticket, DAG, timers et gates | workflow racine Temporal | PostgreSQL applicatif |
| Cycle de vie d'une invocation | `AgentTaskWorkflowV1` Temporal | tâche A2A persistée |
| Identité et capacités d'un rôle | Agent Card signée dérivée du catalogue | cache vérifié du client |
| Hiérarchie et permissions | `resources/agents/catalog-v1.yaml` | Agent Cards et règles générées |
| Association des identifiants | registre de corrélation PostgreSQL | attributs Temporal et traces |
| Messages et état protocolaires | stockage de tâches A2A | historique borné |
| Résultat métier d'un rôle | artefact A2A validé | référence compacte Temporal |
| Contenu des preuves | Evidence MCP | URI et digest A2A/Temporal |
| Effets SCM | Gitea via SCM MCP | preuve et projection applicative |

Une divergence entre projection A2A et historique Temporal est un incident à réconcilier. Elle n'autorise ni
l'invention d'une transition, ni une seconde exécution aveugle.

## Identités et corrélation

- `taskId`, `attemptId`, `repositoryId` et `sourceCommit` restent les identités métier.
- `workflowId` et `workflowRunId` localisent le workflow racine.
- `delegationId` et `parentDelegationId` décrivent le DAG validé.
- `messageId` A2A est déterministe pour rendre l'envoi idempotent côté projet.
- `Task.id` et `contextId` A2A sont générés par le serveur et traités comme identifiants opaques.
- L'association complète est persistée avant que le workflow commence son attente.
- Les identifiants à forte cardinalité sont autorisés dans les traces et journaux structurés, pas comme labels de
  métriques.

## Version et transport

La cible initiale est A2A 1.0 avec `A2A-Version: 1.0`. Le binding préféré de la première release est JSON-RPC 2.0
sur HTTPS, déclaré dans chaque Agent Card. Le client refuse tout downgrade implicite vers A2A 0.3.

Le SDK Java officiel est encapsulé derrière des ports internes et sa version finale est épinglée après
qualification JDK 25, Spring Boot 4.1, Reactor/Netty, sécurité, licence et TCK. Un changement de SDK ne doit pas
modifier les contrats métier.

Les envois sont asynchrones : le client demande un retour immédiat, attend un signal Temporal issu d'une
notification authentifiée et déclenche périodiquement `GetTask` pour réconcilier une notification perdue. Aucun
flux réseau de longue durée n'est conservé dans une activité Temporal.

## Sécurité

- chaque rôle et l'orchestrateur possèdent une identité de service distincte ;
- TLS est obligatoire et mTLS protège les échanges internes ;
- OAuth2 client credentials fournit des jetons courts, audiences et scopes par opération/skill ;
- les Agent Cards sont signées, vérifiées et résolues uniquement depuis un registre allow-listé ;
- l'autorisation précède toute recherche de tâche afin de ne pas révéler une ressource inaccessible ;
- les callbacks, URLs de carte et références Evidence sont strictement allow-listés ;
- aucun secret n'entre dans un message, artefact, historique A2A, input Temporal, trace ou journal.

## Compatibilité avec les modes métier

Le protocole de communication ne sélectionne pas le mode métier. `PIPELINE` et `HIERARCHICAL_ACTIVE`, lorsqu'ils
sont autorisés par les politiques existantes, restent des stratégies exécutées à l'intérieur de Temporal. Toute
invocation d'un rôle d'agent utilise A2A quel que soit le mode ; aucun mode ne réactive les appels Java directs.

## Conséquences

### Positives

- les agents deviennent adressables, découvrables, isolables et interopérables ;
- leur cycle de vie peut évoluer indépendamment du contrôle-plane ;
- les contrats et frontières de sécurité deviennent explicites sur le réseau ;
- Temporal conserve une orchestration déterministe et observable ;
- les tests de conformité A2A complètent les tests métier existants.

### Coûts et contraintes

- le déploiement comprend davantage de processus, identités, certificats et task queues ;
- chaque appel acquiert une latence réseau et nécessite idempotence et réconciliation ;
- une projection durable A2A et un récepteur de notifications sont requis ;
- les Agent Cards, contrats, catalogue et règles d'autorisation doivent rester cohérents ;
- Docker Compose doit proposer une topologie complète raisonnablement dimensionnée pour macOS.

## Alternatives rejetées

### Appels directs conservés derrière une façade nommée A2A

Rejeté : aucune interopérabilité, isolation ou preuve de conformité réseau ne serait obtenue.

### Délégations A2A directes entre tous les agents

Rejeté : elles permettraient de contourner Temporal, les budgets, `mayDelegateTo`, les gates et l'annulation en
cascade, et créeraient plusieurs autorités de planification.

### Remplacement de Temporal par A2A

Rejeté : A2A définit les interactions et tâches d'agents, pas les garanties de workflow durable, replay, timers,
signaux et compensation nécessaires au parcours de livraison.

### Utilisation de MCP pour les échanges entre agents

Rejeté : MCP expose des outils et du contexte à un modèle ; il ne remplace pas le cycle de vie et la découverte
d'un agent A2A.

## Rollback

Le rollback restaure une release antérieure des serveurs A2A et des workers Temporal compatible avec les tâches
existantes. Il ne réactive jamais les appels Java directs. Si aucune version compatible n'est disponible, les
admissions restent fermées pendant le drainage ou la réconciliation.

## Vérification

- tous les rôles `agent` et `sub-agent` disposent d'une Agent Card conforme et signée ;
- le TCK A2A passe pour toutes les capacités annoncées ;
- les règles d'architecture interdisent les appels directs depuis le contrôle-plane ;
- un retry, rejeu, timeout ambigu ou redémarrage ne crée pas de seconde exécution logique ;
- la perte d'une notification est corrigée par `GetTask` ;
- l'annulation Temporal se propage à chaque tâche A2A active ;
- les tests prouvent qu'une intention de délégation ne peut pas déclencher elle-même un agent pair ;
- la bascule et le rollback fonctionnent sans changer d'orchestrateur.

## Références

- [A2A Protocol 1.0](https://a2a-protocol.org/latest/specification/)
- [A2A 1.0 — nouveautés et incompatibilités](https://a2a-protocol.org/latest/whats-new-v1/)
- [A2A et MCP](https://a2a-protocol.org/latest/topics/a2a-and-mcp/)
- [SDK Java officiel A2A](https://github.com/a2aproject/a2a-java)
- [ADR-TEMP-001 — Temporal comme moteur unique](ADR-TEMP-001-bascule-franche-temporal.md)

## Clôture de l'implémentation

La bascule franche a été qualifiée puis approuvée le 2026-09-07 sur le candidat source
`356eeebf5eab8c8d0c71884247a1789376a76d43`. La gate finale est
`docs/qualification/a2a/GATE-A2A-180-CUTOVER.md`, l'approbation humaine est archivée dans
`docs/evidence/a2a/A2A-189-CUTOVER-APPROVAL.md` et l'ensemble des preuves est scellé par
`docs/evidence/a2a/MANIFEST.sha256`.

Le dernier commit d'archivage précédant cette clôture est
`86903bf5441882f0f0a2fa013bf6e837c35d2c72`. Les commits postérieurs au candidat qualifié ne modifient que les
contrôles de gate, les preuves et la clôture documentaire. La décision demeure : Temporal est l'unique
orchestrateur, A2A 1.0 l'unique frontière d'invocation d'agent et MCP l'unique frontière d'outil ; aucun fallback
vers les appels Java directs n'est réintroduit.
