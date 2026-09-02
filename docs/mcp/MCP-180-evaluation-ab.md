# MCP-180 — Évaluation A/B des outils choisis par les agents

## État

L'évaluateur local et le raccordement réel Planner/Reviewer sont prêts et testés. Le mode d'évaluation est séparé de l'activation production, limité aux profils locaux et désactivé par défaut.

Une campagne valide doit contenir au moins vingt cas appariés `BASELINE` / `CANDIDATE` et mesurer, pour chaque variante :

- réussite au premier patch et nombre de réparations ;
- succès des tests et acceptation humaine ;
- tokens, durée et coût ;
- toute régression de sécurité.

Le verdict échoue fermé si les cas ne sont pas appariés, si le corpus est incomplet, si une régression de sécurité existe, ou si les seuils qualité/ressources sont dépassés.

## Campagne autorisée

David Beaumont a autorisé le 2 septembre 2026 l'envoi à OpenAI des scénarios `CTX-001` à `CTX-020` et des seuls extraits nécessaires des trois dépôts de démonstration. La campagne utilise `scripts/mcp-agent-ab-campaign.sh`, n'enregistre ni prompt ni réponse brute et ne crée aucune PR.
