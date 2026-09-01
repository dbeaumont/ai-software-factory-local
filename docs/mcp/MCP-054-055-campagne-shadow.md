# MCP-054/MCP-055 — Campagne shadow du contexte dépôt

## Objectif

Le Planner continue de recevoir le contexte direct. En parallèle, `repository-context-mcp` reconstruit un contexte borné et cité. La campagne mesure les deux chemins sans permettre au résultat MCP de modifier le prompt ni la décision du pipeline.

## Activation

Configurer l'orchestrateur avec :

```dotenv
AI_FACTORY_MCP_CLIENT_ENABLED=true
AI_FACTORY_MCP_ENABLED=true
AI_FACTORY_MCP_REPOSITORY_CONTEXT_MODE=MCP_SHADOW
```

Pour la campagne de parité sandbox, utiliser séparément `AI_FACTORY_MCP_SANDBOX_MODE=MCP_SHADOW`. `apply_patch` n'est pas rejoué en shadow car il modifie le workspace partagé ; son contrôle de parité repose sur `validate_patch` et sur les digests/diff stats des preuves.

## Échantillon minimal

Exécuter au moins 20 tâches couvrant Maven, Gradle et Node, petits et grands dépôts, exigences simples et multi-fichiers, règles dépôt présentes/absentes, et au moins les cas négatifs de référence. Une tâche échouée reste dans l'échantillon et doit être qualifiée.

Après la campagne :

```bash
make mcp-shadow-report
```

Le rapport horodaté est créé dans `docs/mcp/baselines/`. Il contient les mesures agrégées et un extrait limité aux métriques MCP shadow, sans contenu de dépôt ni secret.

## Critères de promotion

- aucune lecture hors workspace ou inter-tâches ;
- zéro citation attribuée au mauvais commit et validité des citations à 100 % ;
- couverture des fichiers utiles d'au moins 90 % ;
- diminution mesurable du contexte brut ;
- aucune dégradation des plans sur la grille d'évaluation produit ;
- comparaisons sandbox réussies pour validation, tests, qualité et sécurité ;
- toute différence de preuve est expliquée ;
- approbation Produit et RSSI avant `MCP_ACTIVE`.

Le script ne coche aucune gate automatiquement : les compteurs prouvent l'exécution, mais l'impact sur la qualité du plan et l'acceptation des divergences restent des décisions de revue.

## Premier jalon réel — 2026-08-31

Le smoke test `7139a89a` a exécuté avec succès le chemin direct et le chemin MCP Context sur le même commit `3ddff5310c53a19614101aa1b4888827807ed9d3`. Le contexte direct est resté l'autorité fournie au Planner.

Deux défauts de déploiement détectés par le premier essai ont été corrigés avant ce succès :

- l'image runtime du serveur ne contenait pas `git`, indispensable à la vérification du commit monté ;
- les résultats des outils étaient sérialisés en camelCase alors que le contrat publié impose snake_case.

Le rapport [`MCP-shadow-20260831-211514.md`](baselines/MCP-shadow-20260831-211514.md) constate une couverture de fichiers de `1,0`, une validité syntaxique des citations de `1,0`, 2 790 caractères directs et 3 964 caractères MCP. Ce premier résultat valide le câblage, mais pas la promotion : il ne couvre qu'une tâche et le contexte MCP est actuellement 42 % plus volumineux. L'évaluation du plan n'a pas pu être menée, LiteLLM ayant ensuite répondu HTTP 500.

Les métriques sont en mémoire et repartent de zéro au redémarrage de l'orchestrateur. Chaque rapport intermédiaire doit donc être conservé, et la campagne de 20 tâches doit être exécutée sans redémarrage ou avec une agrégation Prometheus persistante.

## Avancement après stabilisation de la chaîne LLM

Le rapport [`MCP-shadow-20260831-213418.md`](baselines/MCP-shadow-20260831-213418.md) agrège quatre passages Context réussis et aucun échec. La couverture et la validité syntaxique des citations restent à `1,0`. Le cumul atteint 11 160 caractères directs contre 15 856 caractères MCP : le surcoût de provenance et l'inclusion de Markdown ne permettent pas encore d'établir une réduction sur ce petit dépôt.

Le chemin d'inférence local a été retiré le 1er septembre 2026. LiteLLM utilise désormais exclusivement le fournisseur cloud configuré. Les mesures de qualité et de latence antérieures ne sont donc plus comparables directement : la campagne Planner doit être reprise sur la baseline cloud-only. En shadow, le Planner continue de recevoir le contexte direct ; un écart de plan ne doit pas être attribué à MCP avant la promotion en `MCP_ACTIVE`.
