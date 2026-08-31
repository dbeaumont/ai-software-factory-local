# ADR-MCP-001 — Frontières, transport et SDK MCP

- Statut : accepté pour le premier incrément
- Date : 2026-08-31
- Portée : prototype local, avec compatibilité vers la cible GCP

## Contexte

L'orchestrateur Spring Boot 3.5 appelle actuellement le système de fichiers, Docker et Gitea directement. Il assemble aussi un contexte de dépôt monolithique avant chaque appel LLM. Ces responsabilités couplent le workflow à ses intégrations et donnent au processus d'orchestration des privilèges trop larges.

La version courante de Spring AI 2.x exige Spring Boot 4.x, tandis que la ligne Spring AI 1.1.x reste la ligne compatible avec Spring Boot 3.5.x. Une migration de l'orchestrateur vers Spring Boot 4 n'est pas justifiée par le seul ajout de MCP.

## Décision

1. L'orchestrateur devient progressivement un hôte/client MCP derrière des ports applicatifs propres à l'usine.
2. Le premier serveur est `repository-context-mcp`, en lecture seule.
3. Les serveurs utilisent HTTP MCP stateless. Les opérations longues futures retournent un handle explicite au lieu de dépendre d'une session de transport.
4. Le prototype épingle Spring AI `1.1.8`, compatible avec Spring Boot `3.5.x`. L'usage du SDK reste confiné aux adaptateurs MCP.
5. La révision de protocole réellement négociée par cette ligne SDK est acceptée pour le prototype. Le passage à la ligne Java SDK 2.x et à une révision ultérieure fera l'objet d'un test de compatibilité, sans changer les ports métier.
6. Le transport local reste sur le réseau Docker Compose privé. Aucun port MCP n'est publié sur l'hôte par défaut.
7. Les endpoints MCP HTTP sont considérés comme non authentifiés tant qu'une couche explicite n'a pas été ajoutée. Ils ne doivent donc pas être exposés hors du réseau local de développement.
8. Les agents ne sélectionnent pas eux-mêmes les outils pendant les premiers lots. L'orchestrateur effectue les appels de manière déterministe.

## Frontières initiales

- `repository-context-mcp` : lecture bornée du workspace d'une tâche ; aucun réseau sortant et aucune mutation.
- `sandbox-execution-mcp` : futur contrôleur d'opérations d'exécution allow-listées.
- `scm-delivery-mcp` : future mutation Gitea après approbation vérifiée.
- `assurance-mcp` et `evidence-mcp` : lots ultérieurs de normalisation des verdicts et preuves.

## Conséquences

- L'ajout de MCP ne retire pas immédiatement `docker.sock` ; ce retrait appartient au lot sandbox.
- Deux chemins `DIRECT` et `MCP_SHADOW` coexistent pendant la migration du contexte.
- Les contrats JSON, limites, codes d'erreur et permissions sont versionnés indépendamment du SDK.
- Une montée de version du protocole nécessite tests de négociation, conformité et non-régression.

## Alternatives écartées

- **Migrer immédiatement tout le dépôt vers Spring Boot 4** : changement trop large pour valider MCP.
- **Utiliser `stdio` en production locale** : lie le cycle de vie du serveur au client et prépare mal Cloud Run/GKE.
- **Exposer un serveur filesystem générique** : surface trop large, absence de liaison forte entre tâche, commit et workspace.
- **Donner directement les outils au LLM** : prématuré avant politiques, budgets, traces et évaluations de sécurité.

