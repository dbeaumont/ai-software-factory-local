# MCP-013 — Baseline `customer-api` du 31 août 2026

> Statut : succès jusqu'au point de contrôle humain `WAITING_APPROVAL`
>
> Tâche de référence : `AF-0001` / `ca56a181`
>
> Livraison : aucune approbation, aucun commit, push ou Pull Request

## 1. Scénario de référence

| Élément | Valeur |
|---|---|
| dépôt | `http://gitea:3000/aiadmin/customer-api.git` |
| branche | `main` |
| exigence | `Add GET /customers/{id}. Return HTTP 404 when the customer does not exist. Add automated tests.` |
| mode | `CLOUD`, autorisé explicitement pour ce dépôt de démonstration |
| modèle logique | `factory-code-cloud` |
| début UTC | `2026-08-31T18:33:05.385728632Z` |
| fin de la baseline UTC | `2026-08-31T18:34:24.769452752Z` |
| durée totale | `79.384 s` |
| verdict | `WAITING_APPROVAL` — pipeline technique réussi, décision humaine attendue |

La limite fonctionnelle de cette baseline est volontaire : elle démontre le pipeline jusqu'à la demande d'approbation, sans exercer l'approbation ni la création de Pull Request.

## 2. Architecture et préflight observés

| Capacité | État pendant la campagne |
|---|---|
| orchestrateur | version reconstruite depuis les sources courantes, active |
| contexte dépôt | mode `DIRECT` ; serveur disponible mais feature flag MCP désactivé |
| exécution sandbox | mode `MCP_ACTIVE` via `sandbox-execution-mcp` |
| modèle cloud | activé et disponible |
| Gitea | dépôt clonable, source SHA résolu |
| SonarQube | analyse exécutée, Quality Gate `PASSED` |
| Trivy | analyse exécutée, aucune vulnérabilité détectée |

Capacités retournées avant la soumission : `cloudEnabled=true`, `cloudAvailable=true`, `mcpEnabled=false`, `repositoryContextMcpAvailable=false`, `sandboxMcpEnabled=true` et `sandboxMcpAvailable=true`.

## 3. Chronologie

La durée d'une ligne correspond au temps écoulé jusqu'à la transition suivante.

| Étape | Horodatage UTC | Durée | Résultat |
|---|---|---:|---|
| `QUEUED` | `18:33:05.385728632` | `0.004 s` | tâche acceptée |
| `CLONING` | `18:33:05.389654673` | `0.078 s` | dépôt cloné et SHA résolu |
| `PLANNING` | `18:33:05.467775132` | `11.717 s` | plan produit |
| `GENERATING_PATCH` | `18:33:17.184515929` | `7.134 s` | patch produit et validé |
| `APPLYING_PATCH` | `18:33:24.318578376` | `0.282 s` | patch appliqué via MCP |
| `TESTING` | `18:33:24.600702085` | `17.118 s` | tests Maven réussis |
| `QUALITY_SCANNING` | `18:33:41.719170468` | `19.893 s` | Quality Gate Sonar passé |
| `SECURITY_SCANNING` | `18:34:01.611994463` | `17.618 s` | analyse Trivy passée et SBOM généré |
| `REVIEWING` | `18:34:19.229827972` | `5.540 s` | décision automatique `ACCEPT` |
| `WAITING_APPROVAL` | `18:34:24.769452752` | — | arrêt volontaire avant livraison |

## 4. Résultats par étape

| Étape | Statut | Preuve principale |
|---|---|---|
| clone | réussi | source `3ddff5310c53a19614101aa1b4888827807ed9d3` |
| planification | réussie | plan exploitable, artefact conservé par la tâche |
| génération | réussie | patch de deux fichiers pour l'endpoint et ses tests |
| application | réussie | validation et application isolées via `sandbox-execution-mcp` |
| tests | réussis | Maven : 3 tests, 0 échec, 0 erreur, 0 ignoré |
| qualité | réussie | Sonar Quality Gate `PASSED` |
| sécurité | réussie | Trivy : 0 vulnérabilité ; SBOM CycloneDX `.ai-factory/sbom.cdx.json` |
| revue | acceptée | décision `ACCEPT`, aucun finding bloquant |
| approbation | non exécutée | tâche arrêtée à `WAITING_APPROVAL` |
| Pull Request | non exécutée | `pullRequestUrl=null` |

Le patch ajoute `GET /customers/{id}`, retourne HTTP 404 pour un identifiant inconnu et ajoute les tests automatisés correspondants. La revue recommande néanmoins une validation humaine explicite du choix d'exposer l'endpoint sans authentification.

## 5. Digests et tailles des preuves

Les tailles sont calculées sur la représentation UTF-8 exacte stockée par l'orchestrateur.

| Objet | Taille | SHA-256 |
|---|---:|---|
| source Git | — | `3ddff5310c53a19614101aa1b4888827807ed9d3` |
| prompt Planner | — | `a73c46364decf9a395802b512dc71f85cc5b44ed641598859b11374e172b2f60` |
| prompt Developer | — | `03b01181a629afc156c230de66299630331ccd91a5c6ec90f29ac5049a912528` |
| prompt Tester | — | `aa779053ff59952f1e2c3277c494ac3b8743f088ecf042a32a8c42b2f663b433` |
| prompt Reviewer | — | `685f1ae1f32e87d086ae0eabad8f5a1c8d8d8d7163400227e923ee77be2c91bd` |
| plan | `3 794 octets` | `628dabf5a3c3d528d4726dd7934bc1744b9064e7c4568aa2ba739e7877b48d34` |
| patch | `2 513 octets` | `d6dd38ce910eee5458574da2b17412be2200d106441fb6c111826bd1359071a3` |
| tests | `8 842 octets` | `161deea9f46cbf64af513d5689771a538b51ea335f29bbfe54d1ef6640d4aa21` |
| qualité | `7 577 octets` | `2bb32c2a457c07d9b4150e46773d1b7eb686589c211b82b52431d597fe626946` |
| sécurité/SBOM | `7 404 octets` | `6a90bfbdd07da4dae4af77bf5c9da14c064db9af1d25590c208a446ca1be4227` |
| revue | `1 873 octets` | `002c747bdf7f20d89ec0062c579419a06f4f0d10a16912409ca7c05fa2f064e8` |

## 6. Tentatives préparatoires et corrections

La capture a aussi révélé des écarts entre les sources courantes et certaines images locales. Ils ont été corrigés avant la tâche de référence :

| Tâche | Point d'arrêt | Diagnostic ou correction |
|---|---|---|
| `44c3059f` | `PLANNING` | mode local impossible : modèle Ollama `qwen2.5-coder:7b` absent |
| `71c8d34c` | validation du patch | ancienne image orchestrateur utilisant directement la socket Docker, non montée |
| `a708d19d` | appel sandbox MCP | injection corrigée pour le bean Spring AI `mcpSyncClients` de type `List<McpSyncClient>` |
| `1635dacb` | réponse sandbox | ancienne image sandbox sans `heartbeat_at`, reconstruite depuis les sources courantes |
| probe MCP | exécution isolée | exécution `753e7022bd7e45f5a92c8055321db6f6` terminée `SUCCEEDED/PASSED` |
| `ca56a181` | `WAITING_APPROVAL` | baseline de référence complète |

Deux tests de non-régression ont été ajoutés côté orchestrateur : binding explicite de `McpFactoryProperties` et câblage de la liste de clients MCP Spring AI. La suite orchestrateur passe avec 40 tests ; la suite du serveur sandbox passe avec 26 tests, dont un test d'intégration Docker ignoré lorsqu'aucun daemon n'est exposé.

## 7. Verdict et limites connues

MCP-013 est validée : chaque étape technique attendue possède un statut, une durée et des empreintes vérifiables, et le verdict final contrôlé est `WAITING_APPROVAL`.

Les limites suivantes restent à traiter dans les tâches ultérieures :

- le contexte dépôt fonctionne encore en mode direct ; seule l'exécution sandbox est `MCP_ACTIVE` dans cette campagne ;
- le modèle Ollama local reste absent et son readiness n'est pas contrôlé par le preflight ;
- aucune mesure de couverture JaCoCo n'a été importée dans Sonar ;
- le clone peu profond ne fournit pas les informations de blame à Sonar ;
- l'avis humain sur l'authentification du nouvel endpoint reste requis ;
- l'approbation, le push et la création de PR sont hors du périmètre de cette baseline.
