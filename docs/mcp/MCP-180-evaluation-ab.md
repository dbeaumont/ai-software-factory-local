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

## Compatibilité du canal d'outils

La première exécution candidate a été interrompue après détection d'une erreur systémique HTTP 400, avant toute interprétation de résultat. L'API OpenAI n'accepte pas le point des noms MCP (`context.read_file`) dans le champ `name` d'un outil. L'orchestrateur utilise donc sur le fil un alias déterministe conforme, par exemple `mcp_0_context_read_file`, puis le résout vers le nom MCP canonique avant la matrice de permissions et l'invocation. Un alias inconnu est refusé ; les noms canoniques, schémas, identifiants d'appel et corrélations de résultats restent contrôlés côté hôte.
