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

## Campagne de qualification du schéma strict

La campagne autorisée `20260901-152635` a rejoué les 20 scénarios avec le schéma strict déployé :

- 20/20 reconstructions Context shadow réussies, couverture 92 % et citations 100 % ;
- 19/20 plans présents et conformes au contrat strict, contre 15/20 avant correction ;
- 1/20 réponse (`CTX-020`, npm multi-fichier) encore rejetée pour absence du champ `status` ;
- latence MCP Context moyenne de 57,4 ms ;
- volumes inchangés : 61 588 caractères MCP contre 86 958 directs.

Le taux de contrat Planner invalide passe de 25 % à 5 %, mais l'objectif fail-closed reste zéro. Le schéma transmis au proxy ne dispense donc pas de la validation applicative existante.

## Reprise bornée du contrat Planner

L'orchestrateur valide désormais le contrat immédiatement après le premier appel Planner. Si, et seulement si, la réponse est illisible, omet un champ obligatoire ou contient une valeur `status`/`risk_level` hors énumération, il effectue exactement un second appel avec le même prompt et le même schéma strict. Une décision métier valide comme `NEEDS_CLARIFICATION`, `OUT_OF_SCOPE` ou `BLOCKED` n'est jamais rejouée. Si le second résultat reste invalide, la validation fail-closed existante arrête le pipeline.

Le rejeu est journalisé et compté par `ai_factory_planner_contract_retries`. Les tests unitaires prouvent l'absence de rejeu d'un contrat valide et la borne de deux appels au total ; la suite orchestrateur complète passe avec 75 tests sans échec.

La recommandation demeure **NO-GO** pour MCP-056 jusqu'à une nouvelle campagne cloud explicitement autorisée. Cette campagne devra obtenir 20 contrats valides sur 20 et comptabiliser les rejeux ; son autorisation devra couvrir le fait qu'un scénario dont la première réponse est invalide peut envoyer une seconde fois le même contenu Planner à OpenAI.

## Résultat de qualification de la reprise bornée

La campagne autorisée `20260901-154935` a exécuté les 20 scénarios avec la reprise déployée :

- 20/20 reconstructions Context shadow réussies, couverture 92 % et citations 100 % ;
- 19/20 contrats Planner valides ;
- un seul retry déclenché, conformément à la métrique `ai_factory_planner_contract_retries=1` ;
- `CTX-011` (Gradle, multi-fichier) reste invalide après deux réponses successives sans champ `status` ;
- cinq décisions `NEEDS_CLARIFICATION` valides n'ont pas été rejouées ;
- latence MCP Context moyenne de 48,2 ms et volumes inchangés, soit une réduction de contexte de 29,2 %.

Le mécanisme est donc correctement borné et sélectif, mais n'atteint pas le seuil de qualité requis. L'échec était `CTX-020` dans la campagne précédente et concerne désormais `CTX-011` : il n'est pas reproductiblement lié à un scénario. Avant toute nouvelle consommation cloud, la prochaine remédiation doit capturer et classifier de façon non sensible le `finish_reason` du fournisseur et distinguer explicitement troncature, refus et violation de schéma. Le plafond Planner de 1 200 tokens devra être réévalué sur la base de cette télémétrie, sans l'augmenter à l'aveugle.

Décision technique maintenue : **NO-GO pour MCP-056**.

## Remédiation de classification

Le client traite maintenant explicitement les métadonnées Chat Completions conformément à la [documentation OpenAI sur les sorties structurées](https://developers.openai.com/api/docs/guides/structured-outputs) :

- `finish_reason=stop` est le seul état accepté avec un contenu textuel ;
- `finish_reason=length` est classé comme troncature retryable ;
- `content_filter`, `refusal`, contenu absent et raison inconnue bloquent sans retry ;
- le journal conserve uniquement la raison de fin et `completion_tokens`, jamais le prompt ni la réponse ;
- la première tentative Planner reste bornée à 1 200 tokens ; l'unique seconde tentative utilise 2 400 tokens pour traiter une éventuelle troncature sans augmenter le coût nominal.

La reprise reste déclenchée pour une violation du contrat applicatif même si le fournisseur annonce `stop`. Les 80 tests orchestrateur passent, dont les cas `length`, refus, filtre de contenu, contrat invalide et décision métier valide. Une campagne cloud reste nécessaire avant de reconsidérer le gate.
