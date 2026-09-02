# ADR-MAH-007 — Transition du rôle Planner

- Statut : accepté pour le prototype de migration
- Date : 2026-09-02
- Portée : Planner historique, Supervisor et Architecture Agent

## Contexte

Le Planner actuel produit à la fois une analyse du dépôt, une qualification de risque, une liste d'impacts et un
plan d'implémentation. Dans la cible hiérarchique, ces responsabilités sont réparties entre le Supervisor, le
périmètre Architecture et le coordinateur Code. Renommer directement Planner en Supervisor préserverait un
contrat trop large et risquerait de modifier le comportement du pipeline de référence.

## Décision

1. Le rôle `planner` reste inchangé comme rôle de compatibilité du mode `PIPELINE`.
2. Un nouveau rôle `supervisor` porte la décomposition, le routage, la consolidation et le replan borné.
3. Un nouveau rôle `architecture-agent` porte l'analyse d'impact, les contraintes et la proposition de scopes.
4. Le Supervisor ne produit pas le patch et ne remplace pas les gates déterministes.
5. Architecture Agent ne décide pas du routage final et ne déclenche aucune action à effet.
6. Les trois rôles possèdent des prompts, contrats, métriques et empreintes distincts.
7. Aucune sortie historique de Planner n'est automatiquement considérée comme un DAG hiérarchique.

## Correspondance des responsabilités

| Responsabilité Planner actuelle | Rôle cible |
|---|---|
| Vérifier que la demande est exploitable | Supervisor avec validation hôte |
| Qualifier les impacts techniques | Architecture Agent |
| Identifier fichiers et couches | Architecture Agent puis Code Agent |
| Définir les tests attendus | Test Design |
| Identifier les risques de sécurité | Threat Model |
| Ordonner le travail | Supervisor via un DAG validé |
| Définir les décisions humaines | Supervisor consolide les demandes des spécialistes |

## Stratégie de transition

1. Conserver `planner.md` et son contrat tant que `PIPELINE` existe.
2. Introduire `supervisor.md` et `architecture-agent.md` en mode shadow.
3. Comparer leurs sorties aux sections correspondantes de Planner sans influencer la baseline.
4. Activer les nouveaux rôles uniquement après validation des contrats et campagne A/B.
5. Retirer Planner seulement lorsque le mode `PIPELINE` historique est officiellement supprimé.

## Conséquences

- une période de coexistence des prompts est assumée ;
- les métriques doivent distinguer Planner, Supervisor et Architecture Agent ;
- la compatibilité API du champ `plan` est conservée via une projection du plan consolidé ;
- le catalogue marque Planner comme alias de compatibilité, pas comme parent hiérarchique.

## Alternatives écartées

- **Renommer Planner en Supervisor** : responsabilités et contrats resteraient confondus.
- **Transformer Planner en Architecture Agent immédiatement** : le mode pipeline perdrait son planificateur.
- **Utiliser un seul agent pour planification et architecture** : réduit l'intérêt de la spécialisation et rend
  l'arbitrage moins explicite.
