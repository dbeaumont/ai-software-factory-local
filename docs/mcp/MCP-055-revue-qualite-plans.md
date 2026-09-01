# MCP-055 — Revue de la qualité des plans

## Périmètre et méthode

La revue porte sur la campagne autoritative `20260901-144626`. Les exigences et réponses Planner ont été consultées transitoirement depuis l'API locale ; leur contenu intégral n'est pas recopié dans les artefacts. Seuls les statuts, volumes et constats agrégés sont conservés.

Le mode `MCP_SHADOW` a servi le contexte direct au Planner. Le contexte MCP a été reconstruit et mesuré en parallèle, mais n'a influencé ni le prompt servi ni le plan. La campagne établit donc une baseline de qualité du Planner et la qualité factuelle du contexte candidat ; elle ne prouve pas encore l'effet causal d'un passage en `MCP_ACTIVE`.

## Résultats Context

| Mesure | Résultat | Seuil | Verdict |
|---|---:|---:|---|
| Reconstructions shadow réussies | 20/20 | 20/20 | conforme |
| Couverture moyenne des fichiers | 92 % | au moins 90 % | conforme |
| Citations syntaxiquement valides et liées au commit | 100 % | 100 % | conforme |
| Caractères MCP | 61 588 | inférieur au direct | conforme |
| Caractères directs | 86 958 | référence | référence |
| Réduction estimée caractères/tokens | 29,2 % | réduction mesurable | conforme |
| Latence MCP Context moyenne | 47,8 ms | mesure informative | mesuré |

La latence directe n'était pas instrumentée séparément pendant cette campagne. La valeur MCP provient de `ai_factory_mcp_client_duration_seconds_sum / count`, soit `0,956336542 / 20`.

## Résultats Planner

| Classe | Nombre | Observation |
|---|---:|---|
| `IMPLEMENTABLE` valide | 11 | Chaque réponse retenue identifie des fichiers impactés et des tests. |
| `NEEDS_CLARIFICATION` valide | 4 | Trois refus sont justifiés par une décision produit ou d'API réellement manquante ; le cas StockLevel paraît excessivement prudent au regard de l'exigence. |
| Contrat Planner invalide | 5 | Le JSON ne contient pas le champ obligatoire `status` ; ces cas couvrent Maven, Gradle et npm. |

Les 20 pipelines ont atteint un état terminal `FAILED`. La qualification agrégée est la suivante :

- 5 contrats Planner invalides ;
- 4 arrêts `NEEDS_CLARIFICATION` ;
- 4 patchs absents ou non applicables ;
- 5 suites de tests en échec ;
- 1 preuve d'exécution partielle ;
- 1 scan sécurité incapable d'accéder à sa donnée de version.

Les défauts Planner ont été produits avec le contexte direct et ne constituent donc pas une régression MCP. Les défauts de patch, tests et scan relèvent de la chaîne aval et des lots sandbox.

## Recommandation de gate

Décision technique recommandée : **NO-GO pour MCP-056 à ce stade**.

Le contexte MCP satisfait les critères de couverture, provenance, réduction et disponibilité de MCP-055. La promotion reste bloquée par deux éléments indépendants :

1. le taux de contrats Planner invalides est de 25 %, ce qui ne constitue pas une baseline fonctionnelle acceptable ;
2. aucun plan n'a encore été généré à partir du contexte MCP, donc la non-régression sémantique en `MCP_ACTIVE` n'est pas directement démontrée.

Avant promotion, il faut stabiliser le contrat de sortie Planner, obtenir une baseline valide, puis faire approuver explicitement le canary `MCP_ACTIVE` du seul Planner par le Product Owner AI Software Factory et le Représentant RSSI. Developer et PatchRepair restent hors périmètre tant que ce canary n'est pas accepté.

## Remédiation préparée

Le client Chat Completions impose désormais au seul Planner un `response_format` de type `json_schema` strict. Le schéma rend obligatoires les treize champs du contrat, borne `status` et `risk_level` par leurs énumérations, et refuse les propriétés supplémentaires à tous les niveaux objet. Les agents qui produisent un diff ou du texte ne reçoivent pas ce format.

La suite orchestrateur valide la construction du corps de requête, l'application exclusive au Planner et la cohérence `required/properties`. Cette remédiation n'est pas considérée comme qualifiée en production tant qu'une nouvelle campagne cloud explicitement autorisée n'a pas confirmé zéro contrat Planner invalide.
