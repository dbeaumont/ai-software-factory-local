# MCP-180 — Rapport de campagne A/B du 2 septembre 2026

## Verdict

**REJECTED** — Le mode candidat Planner/Reviewer avec choix d'outils ne doit pas être activé en production.

La campagne contient 20 cas appariés `CTX-001` à `CTX-020` par variante. Aucun appel candidat n'a produit de régression sécurité après correction de la classification. Le candidat régresse toutefois sur le succès des tests et la consommation de tokens au-delà des seuils du lot 0.

## Résultats

| Métrique | Baseline | Candidat | Écart | Seuil | Décision |
|---|---:|---:|---:|---:|---|
| Réussite au premier patch | 75 % | 75 % | 0 point | régression max. 2 points | conforme |
| Réparations moyennes | 0,35 | 0,40 | +0,05 | hausse max. 0,10 | conforme |
| Succès des tests | 10 % | 0 % | -10 points | régression max. 2 points | **échec** |
| Acceptation humaine | 0 % | 0 % | 0 point | régression max. 2 points | neutre, échantillon non approuvé |
| Tokens moyens | 6 718,25 | 8 612,15 | +28,19 % | hausse max. 15 % | **échec** |
| Durée moyenne | 27 152,05 ms | 23 853,25 ms | -12,15 % | hausse max. 15 % | conforme |
| Coût moyen exposé | 0 µ$ | 0 µ$ | non calculable | hausse max. 15 % | télémétrie fournisseur absente |
| Échecs sécurité | 0 | 0 | 0 | aucun | conforme |

La baseline a atteint une fois `WAITING_APPROVAL`; le candidat n'a atteint ce statut sur aucun cas. Aucune tâche n'a reçu d'approbation humaine et aucune PR n'a été créée par la campagne.

## Intégrité et corrections de mesure

- La première tentative candidate a été arrêtée sur HTTP 400 : les points des noms MCP ne sont pas acceptés dans les noms d'outils OpenAI. Un alias de transport réversible et contrôlé par l'hôte a été ajouté.
- La seconde tentative courte a été arrêtée après constat de la contradiction « no access to tools » dans les prompts Planner/Reviewer. Les prompts distinguent désormais absence d'accès direct et outils de lecture déclarés par l'hôte.
- La classification initiale cherchait le mot `vulnerab` dans toute erreur. La sortie npm `found 0 vulnerabilities` créait six faux positifs. Les six observations ont été normalisées à `false`; le collecteur ne retient désormais que les rejets explicites de l'étape sécurité ou une alerte Trivy HIGH/CRITICAL.
- L'ancien champ `human_accepted` reflétait en réalité la décision du Reviewer IA. L'unique valeur positive de la baseline a été normalisée à `false`; le modèle d'état sépare désormais `review_accepted` et `human_accepted`.

## Artefacts

- Baseline : `docs/evidence/mcp/baselines/MCP-agent-ab-20260902-01-baseline.jsonl` — SHA-256 `47ea3ebd6220f18fc5570f936da89631765b0b27457ea847bfde600e743d3237`.
- Candidat : `docs/evidence/mcp/baselines/MCP-agent-ab-20260902-01-candidate.jsonl` — SHA-256 `51f78092f66b7526a1cc0a54d3633dd88c80b8fe846725ba31e8e0da8bb6576d`.

## Suite requise

Conserver `AI_FACTORY_AGENT_TOOL_ROLES` vide et la qualification `INCOMPLETE`/non activée. Une nouvelle campagne ne pourra qualifier Planner/Reviewer qu'après réduction des tokens, amélioration du passage des tests, disponibilité d'une télémétrie de coût exploitable et définition d'un protocole d'acceptation humaine représentatif.
