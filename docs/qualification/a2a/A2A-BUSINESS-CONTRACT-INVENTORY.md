# A2A-023 — Inventaire des contrats métier et des preuves

## Sources autoritatives

| Source | Rôle | SHA-256 au gel |
|---|---|---|
| `resources/multiagents/schemas/contract-catalog-v1.json` | Catalogue fermé des 18 schémas métier Draft 2020-12 | `32e0174226f9f29353b26a91cd0981edaa01064b525dc2ef9975acd4b4995137` |
| `resources/multiagents/fixtures/golden-contracts-v1.json` | Un document doré valide par contrat | `1a388f5814ec9ef5b6b862281075a1c8f25076a7e074f17644169e4248d4397a` |
| `resources/agents/catalog-v1.yaml` | Autorité sur rôles, sorties, délégations et outils | `9c37ea85e4a3a3958561044e89080969f9ebb8b961888db43c094dfd0e12ffc6` |
| `resources/agents/<role>.yaml` | Entrées/sorties détaillées et budgets de chaque runtime | à contrôler par la gate de cohérence des cartes |

Chaque payload métier reste validé contre son schéma indépendamment de l'enveloppe A2A. Le transport A2A ne
change ni `schema_version`, ni les liens `task_id`/`attempt_id`/`source_commit`, ni les règles de digest.

## Contrats de contrôle et de coordination

| Contrat v1 | Producteur autorisé | Consommateur principal | Usage A2A cible | Références Evidence attendues |
|---|---|---|---|---|
| `delegation-plan-v1` | `supervisor` | workflow Temporal | artefact principal de décomposition | citations digestées ; aucun contenu de dépôt inline |
| `specialist-task-v1` | workflow Temporal | agents et sous-agents spécialistes | document d'instruction dans l'enveloppe d'entrée | `inputs[]` porte `kind`, URI et SHA-256 |
| `specialist-result-v1` | spécialistes génériques | parent puis `supervisor` | artefact principal de résultat générique | `evidence[]` : type, URI, SHA-256 et statut |
| `agent-run-event-v1` | runtime du rôle | projection/observabilité | transition durable, pas un résultat métier final | aucun contenu ; usage delta et état seulement |
| `contradiction-v1` | contrôle de consolidation | supervisor/reviewer | artefact de conflit ou entrée de résolution | sources liées par digest et URI Evidence bornées |
| `supervisor-decision-v1` | `supervisor` | workflow Temporal | artefact principal de consolidation/replan | résultats d'entrée liés par SHA-256 |
| `human-decision-request-v1` | workflow Temporal | gate humaine | jamais confié à un agent comme autorité de décision | `evidence_uris[]`, objet et scope digestés |

## Contrats par domaine

| Domaine | Contrat v1 | Producteur autorisé | Consommateur principal | Preuve ou contenu lourd |
|---|---|---|---|---|
| Architecture | `architecture-assessment-v1` | `architecture-agent` | code, tests, sécurité, supervisor, reviewer | `evidence[]` URI + SHA-256 |
| Code | `code-task-v1` | `code-agent` sous contrôle Temporal | `developer` | dépendances liées par digest ; périmètre d'écriture inline et borné |
| Code | `patch-proposal-v1` | `developer` | `code-agent`, intégration | diff obligatoirement externe : URI, SHA-256, `text/x-diff`, taille ≤ 1 MiB |
| Code | `patch-repair-task-v1` | contrôle Temporal/code | `patch-repair` | patch et erreur désignés par SHA-256, jamais recopiés |
| Code | `patch-repair-proposal-v1` | `patch-repair` | code/intégration | diff externe avec les mêmes quatre attributs obligatoires |
| Intégration | `integration-proposal-v1` | `code-agent` | workflow Temporal | identités et scope digests ; aucun patch inline |
| Intégration | `integration-result-v1` | workflow d'intégration | tests, sécurité, supervisor, reviewer | patch intégré par digest ; `evidence[]` URI + SHA-256 |
| Tests | `test-strategy-v1` | `test-design` | `test-agent`, `test-evidence` | `evidence[]` URI + SHA-256 |
| Tests | `test-assessment-v1` | `test-agent`/`test-evidence` | supervisor, reviewer | chaque exécution porte ID, statut, URI et SHA-256 ; preuves URI + SHA-256 |
| Sécurité | `security-assessment-v1` | `security-agent`, `threat-model`, `security-findings` | supervisor, reviewer | au moins une décision de politique URI ; preuves typées URI + SHA-256 |
| Revue | `independent-review-v1` | `independent-reviewer` | workflow puis gate humaine | manifeste final ID/URI/SHA-256, preuves obligatoires et URI par contrôle |

## Matrice des quatorze rôles

| Rôle | Contrats d'entrée déclarés | Contrat(s) de sortie déclaré(s) |
|---|---|---|
| `supervisor` | résultats spécialiste, architecture, intégration, tests, sécurité | délégation + décision supervisor |
| `architecture-agent` | tâche spécialiste, résultats de ses sous-agents | assessment architecture |
| `impact-analysis` | tâche spécialiste | résultat spécialiste |
| `dependencies-contracts` | tâche spécialiste | résultat spécialiste |
| `code-agent` | tâche spécialiste, architecture, propositions de patch | proposition d'intégration |
| `developer` | tâche code | proposition de patch |
| `patch-repair` | tâche de réparation | proposition de réparation |
| `test-agent` | tâche spécialiste, stratégie, résultat intégré | assessment tests |
| `test-design` | tâche spécialiste, architecture | stratégie de tests |
| `test-evidence` | stratégie, résultat intégré | assessment tests |
| `security-agent` | tâche/résultat spécialiste, résultat intégré | assessment sécurité |
| `threat-model` | tâche spécialiste, architecture | assessment sécurité |
| `security-findings` | tâche spécialiste, résultat de vulnérabilités MCP | assessment sécurité |
| `independent-reviewer` | architecture, intégration, tests, sécurité, contradiction | revue indépendante |

Les noms complets ci-dessus renvoient aux fichiers `*-v1.schema.json`. Une future Agent Card publiera un skill
par couple entrée/sortie réellement supporté ; elle ne déduira pas de capacité supplémentaire du seul nom du rôle.

## Règles d'artefact A2A

1. Le document final structuré est validé contre le schéma métier puis stocké dans Evidence MCP.
2. L'artefact A2A principal annonce le nom/version du contrat et référence le document par URI interne, SHA-256,
   type de média et taille ; il ne contient pas un patch, un journal, un SBOM ou un rapport complet.
3. Les références Evidence imbriquées dans le document sont contrôlées dans le même tenant, la même tâche et la
   même tentative ; leur digest est relu avant consommation.
4. Les identifiants et digests nécessaires au routage peuvent être recopiés dans l'extension de corrélation ; le
   contenu métier reste dans l'artefact.
5. `specialist-result-v1.usage` reste la source métier des tours, tokens, coût, outils et durée. Ces valeurs ne sont
   jamais remplacées par des labels de métriques à forte cardinalité.

## Écarts détectés à résoudre avant génération des Agent Cards

- Le catalogue central déclare `patch-repair.outputContract: patch-proposal-v1`, alors que le manifeste du rôle
  déclare `patch-repair-proposal-v1`. Le schéma spécialisé est l'intention la plus précise ; la cohérence devra être
  corrigée avant A2A-050/A2A-058, sans l'altérer silencieusement dans une carte.
- `security-findings` consomme `vulnerability-result-v1`, contrat MCP présent dans
  `resources/mcp/schemas`, mais absent du catalogue multi-agent. Il doit rester une référence de résultat d'outil
  MCP et ne pas être présenté comme une interface agent-à-agent.
- Le rôle de contrôle `workflow` annonce `workflow-state-v1`, absent du catalogue multi-agent. C'est un état de
  contrôle Temporal, pas un skill A2A ; aucune Agent Card ne doit être générée pour ce rôle.
- Les rôles historiques `planner`, `tester` et `reviewer` ne font pas partie des quatorze runtimes A2A. Leurs
  responsabilités sont respectivement reprises par les rôles catalogue et doivent disparaître lors de la coupure.

Ces écarts sont désormais explicites : ils ne bloquent pas l'inventaire, mais sont des entrées obligatoires de la
gate de cohérence et des mappings bidirectionnels des tickets A2A-042 à A2A-044.
