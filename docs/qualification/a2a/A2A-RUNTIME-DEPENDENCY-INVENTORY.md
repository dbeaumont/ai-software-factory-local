# A2A-024 — Inventaire des dépendances runtime par rôle

## Frontière commune cible

Chaque instance `a2a-agent-runtime` charge un seul rôle. Elle reçoit des requêtes A2A, exécute un
`AgentTaskWorkflowV1`, appelle le fournisseur LLM et uniquement les outils MCP autorisés, stocke les sorties dans
Evidence, puis notifie le callback fixe de l'orchestrateur. Elle ne contacte jamais un autre agent et ne possède ni
credential SCM, ni accès au Docker daemon, ni datasource de projection de l'orchestrateur, ni workspace partagé.

Les quatorze rôles ont les dépendances communes suivantes :

- **LLM** : endpoint interne LiteLLM, modèle/configuration versionnés, credential court et propre au rôle ; budget,
  deadline et identité d'exécution fournis par la tâche, sans accès direct au fournisseur public.
- **A2A** : listener HTTPS JSON-RPC 1.0 privé, registre local de déduplication et callback HTTPS fixe vers
  l'orchestrateur ; aucun client A2A de pair.
- **Temporal** : client/worker sur une task queue liée au rôle et au Build ID ; horloge de workflow Temporal pour
  toute décision déterministe.
- **Persistance A2A** : repository de tâches, transitions, ACL, artefacts et notifications ; pas d'accès aux tables
  internes de Temporal ni à la projection UI.
- **OpenTelemetry** : export OTLP vers le collector interne, sans contenu de prompt/résultat par défaut.
- **Filesystem** : ressources embarquées du rôle en lecture seule, certificats/secrets montés en fichiers et
  espace temporaire borné ; aucun montage du dépôt ou de la socket Docker.

## Matrice LLM et MCP

Tous les rôles utilisent le LLM. `C` désigne Repository Context MCP, `Es` `evidence.get_summary`, `Er`
`evidence.read`. L'absence d'une colonne vaut refus réseau et refus d'outil, pas seulement absence dans le prompt.

| Rôle | C | Es | Er | Outils Repository Context exacts |
|---|:---:|:---:|:---:|---|
| `supervisor` | oui | oui | non | list tree, search code, repository rules, dependencies |
| `architecture-agent` | oui | non | non | list tree, search code, read file, repository rules, dependencies, symbols |
| `impact-analysis` | oui | non | non | search code, read file, repository rules, symbols |
| `dependencies-contracts` | oui | non | non | list tree, read file, dependencies |
| `code-agent` | oui | non | non | list tree, search code, repository rules |
| `developer` | oui | non | non | list tree, search code, read file, repository rules, dependencies, symbols |
| `patch-repair` | oui | non | non | read file, symbols |
| `test-agent` | oui | oui | non | search code, read file, dependencies, symbols |
| `test-design` | oui | non | non | search code, read file, dependencies, symbols |
| `test-evidence` | non | oui | non | — |
| `security-agent` | oui | oui | non | search code, read file, dependencies, symbols |
| `threat-model` | oui | non | non | search code, read file, dependencies, symbols |
| `security-findings` | non | oui | non | — |
| `independent-reviewer` | oui | oui | oui | search code, read file, dependencies, symbols |

Les noms d'outils canoniques sont ceux de `resources/agents/catalog-v1.yaml`, préfixés par `context.` ou
`evidence.`. Aucun rôle d'agent n'a actuellement droit aux outils `sandbox.*`, `assurance.*` ou `scm.*` ; ces
effets restent des responsabilités du contrôle-plane. Evidence MCP doit toutefois accepter `evidence.store` pour
le runtime lors de la publication du résultat : cette permission technique sera liée au contrat de sortie et non
rendue sélectionnable par le modèle.

## Accès stockage, fichiers et horloge

| Capacité | Runtime d'agent | Workflow/activité de contrôle | Interdit |
|---|---|---|---|
| Base de tâches A2A | repository à portée tenant/rôle ; transactions de déduplication et projection | table de corrélation A2A distincte | datasource `ai_factory`/projection UI depuis le cœur d'agent |
| Temporal | task queue du rôle, namespace et identité dédiés | queues workflow/context/sandbox/assurance/evidence/scm | lecture directe des tables Temporal |
| Dépôt source | aucune vue filesystem | source et sandbox via activités/MCP dédiés | montage `/workspace/tasks`, worktree Git ou socket Docker dans l'agent |
| Ressources statiques | prompt, schémas et définition du rôle en lecture seule | catalogues de contrôle | activation dynamique d'un second rôle |
| Fichiers secrets | certificat, clé privée et secrets client montés en lecture seule | secrets de contrôle séparés | secret dans variable sérialisée, message A2A, trace ou historique Temporal |
| Horloge | `Clock` injectée dans les adaptateurs ; deadline de tâche autoritative | `Workflow.currentTimeMillis`/timers dans le workflow | `Instant.now()` pour une décision rejouable |
| Temporaire | répertoire propre, quota et suppression au shutdown | workspaces gérés par sandbox | volume persistant partagé entre rôles |

## Secrets minimaux par instance

| Secret ou confiance | Besoin | Portée imposée |
|---|---|---|
| clé/certificat mTLS serveur A2A | identité entrante du rôle | SAN du service de ce rôle uniquement |
| clé/certificat mTLS client | Temporal, MCP, callback selon la plateforme | identité du rôle, jamais celle de l'orchestrateur |
| secret OAuth2 client | jetons courts A2A/MCP | audience et scopes du rôle/skill |
| credential LiteLLM | appel du modèle | modèle et quota du rôle |
| trust bundles/JWKS | validation clients, cartes et tokens | lecture seule, rotation avec chevauchement |
| éventuel header OTLP | export télémétrie | write-only vers le collector |

Les credentials Gitea/SCM, approbateur, Artifactory, Sonar administrateur, base orchestrateur, sandbox runner et
secrets d'autres rôles sont explicitement exclus de l'environnement d'un runtime d'agent.

## Destinations réseau allow-listées

| Flux | Direction | Rôles concernés | Condition |
|---|---|---|---|
| orchestrateur → endpoint A2A du rôle | entrant | tous | mTLS + OAuth2, version 1.0, skill autorisé |
| rôle → callback orchestrateur | sortant | tous | URL fixe, notification signée/authentifiée |
| rôle ↔ Temporal frontend | sortant | tous | namespace, identité et task queue du rôle |
| rôle → LiteLLM | sortant | tous | TLS, modèle et quota du rôle |
| rôle → Repository Context MCP | sortant | sauf `test-evidence`, `security-findings` | seulement les outils de la matrice |
| rôle → Evidence MCP | sortant | tous pour publication ; lecture selon matrice | URI/tenant/tâche liés, contenu et taille bornés |
| rôle → OTLP collector | sortant | tous | OTLP uniquement, redaction active |
| rôle → stockage de tâches A2A | sortant | serveur/projection seulement | compte SQL propre, schéma A2A uniquement |

Tout autre egress est refusé : autres agents, Internet, Gitea, Sonar, Artifactory, Kubernetes API, metadata cloud,
loopback d'un autre conteneur et endpoints MCP non autorisés.

## Dépendances actuelles à extraire

- Le monolithe Spring charge aujourd'hui `AgentRuntime`, tous les rôles, les clients MCP, le client LLM, la
  datasource de projection et les capacités SCM dans un même graphe. La séparation par processus n'est donc pas
  encore prouvée.
- `LITELLM_MASTER_KEY` est partagé entre l'orchestrateur et la gateway locale. Il convient au bootstrap local mais
  ne satisfait pas la cible de credentials par rôle.
- `PipelineStepService` et les activités d'intégration accèdent à des `Path` de workspace. Ces accès doivent rester
  côté contrôle/sandbox et ne doivent pas suivre `AgentRuntime` dans le module extrait.
- Plusieurs services utilisent `Instant.now()` hors workflow. Ils sont acceptables dans les adaptateurs et journaux
  d'activité, mais doivent recevoir une horloge testable quand l'instant influence un contrat ou une expiration.
- La configuration actuelle `AI_FACTORY_MCP_REPOSITORY_CONTEXT_ACTIVE_ROLES` vise encore les rôles historiques du
  pipeline. Elle devra être remplacée par la matrice complète du catalogue, appliquée côté serveur MCP.

Cet inventaire est la liste d'entrée de l'extraction `agent-core`, de l'image runtime et des NetworkPolicies. Une
dépendance non listée doit être refusée au démarrage plutôt qu'ajoutée implicitement pendant le déploiement.
