# Catalogue des agents multi-agent hiérarchiques v1

Le catalogue normatif lisible par machine est
[`resources/agents/catalog-v1.yaml`](../../resources/agents/catalog-v1.yaml). Le rôle est fixé par le workflow et
n'est jamais accepté depuis une sortie du modèle.

## Niveaux d'autonomie

| Niveau | Capacité maximale |
|---|---|
| `A0` | Composant déterministe, sans décision de modèle. |
| `A1` | Analyse et demandes d'outils de lecture allow-listés. |
| `A2` | Proposition de délégations bornées, validées et exécutées par l'hôte. |

Aucun rôle du catalogue v1 ne possède une autonomie supérieure à `A2`.

## Hiérarchie

```text
workflow (Platform)
├── supervisor (Platform)
│   ├── architecture-agent (Architecture)
│   │   ├── impact-analysis
│   │   └── dependencies-contracts
│   ├── code-agent (Engineering)
│   │   ├── developer
│   │   └── patch-repair
│   ├── test-agent (Quality Engineering)
│   │   ├── test-design
│   │   └── test-evidence
│   └── security-agent (Application Security)
│       ├── threat-model
│       └── security-findings
└── independent-reviewer (Product and Risk)
```

L'Independent Reviewer appartient au runtime d'agents, mais il est enfant du workflow et non du Supervisor.

## Règles de propriété

- Platform possède le coordinateur, le Supervisor, le runtime et les limites communes.
- Architecture possède les critères d'impact, dépendances et compatibilité.
- Engineering possède la production des patches et leur maintenabilité.
- Quality Engineering possède la stratégie de tests et l'analyse des résultats.
- Application Security possède le threat model et la qualification des findings.
- Product and Risk possède la politique de revue indépendante et les décisions humaines associées.

## Règles d'autorité

- seul `workflow` est `effectful: true` ;
- un agent ne peut déléguer qu'aux enfants déclarés par `mayDelegateTo` ;
- une délégation ne modifie ni le rôle, ni les outils, ni les plafonds du catalogue ;
- le Supervisor propose un DAG mais l'hôte le valide avant exécution ;
- le Reviewer indépendant ne peut ni déléguer, ni replanifier, ni produire un patch ;
- Planner et Reviewer historiques restent des alias de compatibilité du mode `PIPELINE`.

## Activation

La présence d'un rôle au catalogue ne l'active pas. Chaque rôle doit disposer de son prompt, contrat, tests de
permissions, évaluation de qualité et autorisation de mode avant d'être ajouté à une configuration active.
