# ADR-MAH-007 — Transition du rôle Planner

- Statut : accepté pour le prototype de migration
- Date : 2026-09-02
- Portée : Planner historique, Supervisor et Architecture Agent

## Contexte

Le Planner actuel produit à la fois une analyse du dépôt, une qualification de risque, une liste d'impacts et un
plan d'implémentation. Dans la cible hiérarchique, ces responsabilités sont réparties entre le Supervisor, le
périmètre Architecture et le coordinateur Code. Renommer directement Planner en Supervisor préserverait un
contrat trop large et risquerait de modifier le comportement des historiques déjà persistés.

## Décision

1. Le rôle `planner` est exclu des nouvelles admissions et conservé uniquement pour le replay V1.
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

## État de la transition

1. `supervisor.md` et `architecture-agent.md` sont les rôles actifs de la nouvelle architecture.
2. Leurs contrats et responsabilités sont qualifiés indépendamment du Planner historique.
3. `planner.md` et son contrat restent isolés avec les ressources V1 tant que leur replay est requis.
4. Retirer Planner dès que la V1 est drainée et sa période de rétention satisfaite.

## Conséquences

- une période de coexistence des prompts est assumée ;
- les métriques doivent distinguer Planner, Supervisor et Architecture Agent ;
- la compatibilité API du champ `plan` est conservée via une projection du plan consolidé ;
- le catalogue marque Planner comme alias de compatibilité, pas comme parent hiérarchique.

## Alternatives écartées

- **Renommer Planner en Supervisor** : responsabilités et contrats resteraient confondus.
- **Transformer Planner en Architecture Agent immédiatement** : les historiques V1 perdraient leur planificateur.
- **Utiliser un seul agent pour planification et architecture** : réduit l'intérêt de la spécialisation et rend
  l'arbitrage moins explicite.
