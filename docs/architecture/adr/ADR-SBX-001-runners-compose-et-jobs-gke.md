# ADR-SBX-001 — Runners Compose statiques et Jobs GKE

- Statut : accepté
- Date : 2026-09-05
- Portée : exécution sandbox locale et environnements partagés

## Contexte

Le serveur `sandbox-execution-mcp` pilotait auparavant la CLI Docker grâce au montage de
`/var/run/docker.sock`. Ce montage donnait indirectement au service le contrôle du daemon et de l'hôte. Il devait
être retiré tout en conservant une boucle de développement reproductible sur macOS et une isolation adaptée aux
environnements partagés sur GKE.

Les contrats MCP publics, les profils d'exécution, les verdicts et les preuves doivent rester identiques quel que
soit le backend. Une requête ne doit jamais pouvoir fournir une commande, un manifeste, un montage ou une option
privilégiée.

## Décision

1. `SandboxRuntime` reste le port interne unique du serveur MCP.
2. Le runtime `compose` appelle quatre runners statiques, connus au démarrage de Docker Compose. Il est limité aux
   environnements `local`, `dev` et `test`.
3. Le runtime `gke` crée un Job Kubernetes éphémère par exécution, avec une image par digest, une identité dédiée,
   des ressources bornées, un TTL et la classe d'exécution gVisor.
4. Le profil est résolu côté serveur. Les deux backends ne reçoivent que son identifiant immuable et les paramètres
   bornés du contrat.
5. Aucun fallback vers Docker ou entre les deux backends n'est autorisé. Une configuration absente ou invalide fait
   échouer explicitement le démarrage ou l'exécution.
6. Le rollback consiste à redéployer une version précédente du runner ou du contrôleur sans réintroduire la socket.

## Matrice de parité

| Opération MCP | Profil immuable | Workspace | Réseau Compose | Politique GKE | Secrets autorisés | Limite |
|---|---|---|---|---|---|---|
| `validate_patch` | `patch-check-v1` | lecture seule | runner sans egress | `sandbox-deny-all` | aucun | 3 min |
| `apply_patch` | `patch-apply-v1` | lecture-écriture, verrou par tâche | runner sans egress | `sandbox-deny-all` | aucun | 3 min |
| `run_tests` | `test-maven-v1`, `test-gradle-v1` ou `test-node-v1` | lecture-écriture bornée aux sorties de build | runner dépendances | `sandbox-dependency-egress` | registre et proxy allow-listés | 15 min |
| `run_quality` | `quality-sonar-v1` | lecture-écriture bornée aux preuves | runner qualité | `sandbox-quality-egress` | Sonar, registre et proxy allow-listés | 15 min |
| `run_security` | `security-syft-trivy-v2` | lecture-écriture bornée aux preuves | runner dépendances | `sandbox-dependency-egress` | proxy allow-listé | 10 min |

Les invariants communs sont : image immuable, utilisateur non-root, aucune commande libre, limites CPU/mémoire/PIDs,
logs bornés avec indicateur de troncature, timeout, annulation du groupe de processus ou suppression du Job,
identifiant idempotent et état terminal normalisé.

## Différences acceptées

| Sujet | Compose local | GKE partagé | Justification |
|---|---|---|---|
| Cycle de vie | runner persistant, processus éphémère | Pod et Job éphémères | Compose privilégie la rapidité de la boucle locale |
| Isolation | conteneur durci du daemon local | gVisor, namespace, RBAC et NetworkPolicy | le code non fiable est réservé au backend GKE |
| Cache | volume ou espace runner local | `emptyDir` par Job et dépôts distants | aucun cache hôte n'est partagé avec un Job |
| Workspace | volume Compose nommé | PVC contrôlé, sous-répertoire validé | le contrat de lecture/écriture reste identique |
| Secrets | variables limitées du service runner | références de Secret injectées par le contrôleur | aucune valeur de secret ne transite dans l'appel MCP |
| Réconciliation | processus actifs interrogés puis annulés au redémarrage | Jobs actifs listés par label puis supprimés | l'objet à nettoyer dépend du backend |

Ces différences ne peuvent pas modifier le verdict fonctionnel, le code de sortie normalisé, le bornage des logs ou
les preuves attendues.

## Objectifs de service

Les seuils suivants sont des critères de qualification, pas une promesse de disponibilité du poste développeur :

| Mesure | Compose local | GKE partagé |
|---|---:|---:|
| admission et démarrage p95 | 5 s | 30 s |
| délai d'annulation p95 | 5 s | 30 s |
| dépassement toléré après timeout | 10 s | 30 s |
| rétention de l'état MCP | 24 h par défaut | 24 h par défaut |
| suppression de la ressource d'exécution | immédiate après annulation | TTL inférieur ou égal à 300 s après fin |

La campagne de qualification doit mesurer ces seuils avant de déclarer GKE prêt. Un seuil non respecté bloque la
bascule mais n'autorise jamais un fallback vers la socket Docker.

## Conséquences

- Le développement macOS reste fondé sur `docker compose up --build`, sans contrôle programmatique du daemon.
- Quatre runners locaux consomment davantage de mémoire qu'un lancement à la demande.
- La cible GKE exige un namespace, un PVC, des NetworkPolicies, Workload Identity et les secrets de plateforme.
- La parité complète et les SLO GKE ne peuvent être validés que dans un cluster de qualification.
- Les profils et leur traduction doivent rester testés comme un contrat commun aux deux backends.

## Alternatives écartées

- **Proxy de socket Docker** : conserve une autorité importante sur le daemon et une dépendance à son API.
- **Docker-in-Docker privilégié** : déplace le risque sans fournir l'isolation attendue.
- **Création dynamique de conteneurs Compose** : nécessite à nouveau une API de daemon ou une commande Docker.
- **Kubernetes local obligatoire** : alourdit inutilement la boucle de développement macOS.
- **Fallback automatique entre backends** : masque les erreurs d'infrastructure et change le niveau d'isolation.
