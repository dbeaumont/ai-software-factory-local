# MCP-014 — Cas négatifs de référence

> Statut : catalogue créé et contrôles disponibles exécutés avec succès  
> Version du catalogue : `resources/mcp/baselines/negative-cases-v1.json`  
> Livraison SCM pendant la campagne : interdite

## 1. Objectif

Cette baseline fixe six scénarios négatifs stables pour vérifier que l'usine échoue de manière fermée. Elle distingue :

- l'état technique d'un outil MCP, par exemple `SUCCEEDED` lorsque le profil a fini son exécution ;
- son verdict métier, par exemple `REJECTED` lorsque le patch, les tests, la qualité ou la sécurité ne passent pas ;
- l'état final du workflow, qui doit alors être `FAILED` sans continuer vers les étapes suivantes ;
- les effets externes interdits, en particulier commit, push et création de Pull Request.

Les scénarios utilisent des preuves synthétiques déterministes au niveau de la frontière MCP. Ils ne nécessitent ni indisponibilité réelle de SonarQube, ni introduction d'une vulnérabilité dans le dépôt, ni mutation de Gitea.

## 2. Matrice de référence

| ID | Injection contrôlée | Verdict attendu | Effets interdits | Couverture au 31 août 2026 |
|---|---|---|---|---|
| `MCP014-N01` | `sandbox.validate_patch` termine avec `REJECTED`, exit `1` | workflow `FAILED` avant application | patch appliqué, tests, SCM | automatisée |
| `MCP014-N02` | `sandbox.run_tests` termine avec `REJECTED`, exit `1` | workflow `FAILED` avant qualité | qualité, sécurité, SCM | automatisée |
| `MCP014-N03` | `sandbox.run_quality` termine avec `REJECTED`, exit `2` | workflow `FAILED`, jamais de succès sans preuve Sonar | sécurité, revue, SCM | automatisée |
| `MCP014-N04` | `sandbox.run_security` termine avec `REJECTED`, exit `1` | workflow `FAILED` avant revue | revue, approbation, SCM | automatisée |
| `MCP014-N05` | approbation humaine valide absente | maintien à `WAITING_APPROVAL` | commit, push, PR | partiellement automatisée |
| `MCP014-N06` | timeout ambigu après une possible création distante de PR | reprise idempotente vers l'unique PR existante | branche, commit ou PR en double | oracle défini, contrôle absent |

Le détail machine-readable comprend, pour chaque cas, le trigger, l'oracle, les effets interdits, le niveau d'automatisation et les tests associés.

## 3. Oracles fail-closed

### Patch invalide

Le serveur peut avoir exécuté correctement `git apply --check` tout en retournant un verdict `REJECTED`. Le client MCP doit lever une erreur contenant la preuve bornée et ne doit pas appeler `sandbox.apply_patch`.

### Tests échoués

Un exit code non nul produit `REJECTED`. Le workflow ne doit pas transformer l'exécution technique `SUCCEEDED` en validation métier et ne doit pas atteindre SonarQube.

### SonarQube absent

Une configuration requise absente, un service indisponible ou une analyse explicitement `Skipped` bloque la tâche. Une preuve qualité absente ne vaut jamais `PASSED`.

### Vulnérabilité bloquante

Le profil Trivy est configuré avec `--severity HIGH,CRITICAL --exit-code 1`. Une détection rend le verdict sandbox `REJECTED` ; le Reviewer et la demande d'approbation ne doivent pas être appelés.

### Approbation absente

Seul l'état `WAITING_APPROVAL` autorise l'appel d'approbation. Sans approbation valide, la tâche reste en attente et aucun effet SCM n'est permis. Les contrôles d'altération, d'expiration et de liaison cryptographique de la preuve restent suivis par MCP-116 à MCP-120.

### Retry de création de Pull Request

L'oracle cible est « exactement une branche, un commit de livraison et une Pull Request ». Après un timeout ambigu, le retry doit rechercher l'effet distant à partir d'une clé d'idempotence et retourner la même URL au lieu de recréer les objets.

Le prototype ne sait pas encore reprendre une livraison échouée et `GiteaService` ne recherche pas une PR préexistante. Ce cas est donc marqué `GAP_BLOCKING`; son implémentation relève de MCP-115 et sa preuve d'intégration de MCP-120. Le cas est néanmoins figé dès maintenant afin d'éviter qu'un futur retry naïf soit accepté comme conforme.

## 4. Couverture automatisée

| Contrôle | Test |
|---|---|
| intégrité et exhaustivité du catalogue | `McpNegativeReferenceCatalogTest#declaresTheSixMcp014FailClosedScenarios` |
| patch invalide | `McpSandboxServiceTest#rejectsInvalidPatchEvidence` |
| tests échoués | `McpSandboxServiceTest#rejectsFailedTestEvidence` |
| Sonar absent | `McpSandboxServiceTest#rejectsMissingSonarEvidence` et `TaskServiceTest#requiresAQualityGateInsteadOfTreatingSkippedAnalysisAsSuccess` |
| vulnérabilité bloquante | `McpSandboxServiceTest#rejectsBlockingVulnerabilityEvidence` |
| approbation avant le gate | `TaskServiceTest#rejectsApprovalBeforeTheHumanApprovalGate` |
| retry de PR | non automatisé, suivi par MCP-115/MCP-120 |

Commande de reproduction depuis `apps/orchestrator` :

```shell
mvn test
```

Résultat observé : 45 tests exécutés, 0 échec, 0 erreur et 0 test ignoré.

## 5. Critères de conservation de la baseline

- Les identifiants `MCP014-N01` à `MCP014-N06` sont stables.
- Toute évolution de contrat conserve un oracle explicite et la liste des effets interdits.
- Un scénario ne passe à `AUTOMATED` que si son assertion porte sur le verdict et l'absence d'effet externe.
- Une erreur, une preuve partielle ou une dépendance absente ne peut jamais être assimilée à un succès.
- Les tests de livraison réels utilisent un dépôt jetable et une clé d'idempotence ; ils ne sont pas exécutés tant que MCP-115 n'est pas implémenté.
