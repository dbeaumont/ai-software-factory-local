# MCP-180 — Évaluation A/B des outils choisis par les agents

## État

L'évaluateur local est prêt et testé, mais la campagne fournisseur n'est pas déclarée exécutée. Aucun résultat n'est synthétisé pour fermer artificiellement le gate.

Une campagne valide doit contenir au moins vingt cas appariés `BASELINE` / `CANDIDATE` et mesurer, pour chaque variante :

- réussite au premier patch et nombre de réparations ;
- succès des tests et acceptation humaine ;
- tokens, durée et coût ;
- toute régression de sécurité.

Le verdict échoue fermé si les cas ne sont pas appariés, si le corpus est incomplet, si une régression de sécurité existe, ou si les seuils qualité/ressources sont dépassés.

## Condition de lancement live

Le lancement cloud nécessite une autorisation qui identifie précisément le corpus transmis. L'autorisation générale du plan ne remplace pas cette validation de contenu. Jusqu'à cette campagne, aucun rôle de tool calling n'est activé en production.
