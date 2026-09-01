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

Le corpus versionné `resources/mcp/baselines/context-shadow-campaign-v1.json` fournit 20 scénarios répartis entre Maven, Gradle et npm, avec exigences simples, validations, changements multi-fichiers, règles dépôt et cas négatifs. Son contrat est `resources/mcp/schemas/context-shadow-campaign-v1.schema.json`.

Le bootstrap Gitea publie les trois fixtures `customer-api`, `inventory-gradle` et `checkout-node`. La campagne est simulée par défaut :

```bash
make mcp-shadow-campaign
```

L'exécution réelle est volontairement protégée car elle consomme du modèle cloud. Elle est séquentielle, n'approuve aucune tâche et attend un état terminal avant le scénario suivant :

```bash
make mcp-shadow-campaign CAMPAIGN_ARGS=--execute AI_FACTORY_RUN_CLOUD_CAMPAIGN=true
```

Le lanceur vérifie d'abord la disponibilité cloud et MCP. Il conserve uniquement les identifiants, statuts, catégories et tailles de plans dans un JSONL ; il n'enregistre ni contenu de plan, ni prompt, ni secret. Un timeout de supervision arrête la campagne sans prétendre avoir annulé la tâche distante.

Les scénarios Gradle et Node contribuent à la mesure Planner/context dès l'étape `PLANNING`. Leur exécution complète peut rester en échec qualifié tant que `MCP-076` n'a pas fourni un wrapper Gradle publié, Node dans l'image sandbox et les miroirs/egress correspondants. Ils ne peuvent pas servir à valider la parité sandbox avant cette remédiation.

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

## Préparation de la campagne multi-technologies — 2026-09-01

Le corpus de 20 tâches, les fixtures Maven/Gradle/npm, leur bootstrap Gitea et le lanceur à activation explicite sont versionnés. Cette préparation lève le manque d'échantillon, mais `MCP-054` et `MCP-055` restent ouverts jusqu'à l'exécution cloud complète, la qualification des échecs et la revue manuelle de la qualité des plans.

## Déploiement local du mode shadow — 2026-09-01

La pile locale utilise désormais `AI_FACTORY_MCP_ENABLED=true` et `AI_FACTORY_MCP_REPOSITORY_CONTEXT_MODE=MCP_SHADOW`. L'orchestrateur négocie avec succès le protocole `2025-11-25`, le serveur `repository-context-mcp` `0.3.0` et son allow-list de cinq outils. `/api/capabilities` confirme simultanément la disponibilité cloud, du contexte MCP et du sandbox MCP.

Le premier redémarrage sous Spring Boot 4.1.1 a exposé l'absence de `WebClient.Builder` : Boot 4 sépare l'auto-configuration client dans `spring-boot-starter-webclient`. Le starter est maintenant déclaré explicitement dans l'orchestrateur et couvert par `WebClientBoot4ConfigurationTest`.

Le dry-run du manifeste valide les 20 cas et leur répartition Maven, Gradle, npm et catégories fonctionnelles. Aucune tâche cloud n'a été soumise : `AI_FACTORY_RUN_CLOUD_CAMPAIGN=false` reste la barrière explicite avant consommation de capacité externe.

## Campagne cloud complète — 2026-09-01

Une première exécution horodatée `20260901-143253` a révélé un défaut de préparation : les dépôts Gitea `inventory-gradle` et `checkout-node` n'avaient pas de branche `main` publiquement clonable. Cette tentative est conservée comme preuve de diagnostic, mais n'est pas utilisée pour la décision de gate.

Après bootstrap explicite des trois fixtures, vérification de leur clonabilité depuis l'orchestrateur et remise à zéro des métriques en mémoire, la campagne autoritative `20260901-144626` a exécuté les 20 scénarios :

- 20 reconstructions Context shadow réussies et aucun échec du serveur Context MCP ;
- couverture moyenne des fichiers de 92 %, au-dessus du seuil de 90 % ;
- validité des citations de 100 % ;
- 61 588 caractères et environ 15 400 tokens MCP estimés, contre 86 958 caractères et 21 748 tokens directs, soit une réduction d'environ 29,2 % ;
- 15 réponses Planner exploitables et 5 réponses rejetées car le champ obligatoire `status` était absent ;
- 20 tâches terminées en échec à une étape du pipeline : refus `NEEDS_CLARIFICATION`, réponse Planner mal formée, patch invalide, test en échec ou indisponibilité des dépendances de scan sécurité.

Ces échecs aval n'invalident pas MCP-054 : le mode shadow continue de servir exclusivement le contexte direct au Planner et les 20 comparaisons Context ont été collectées. Ils interdisent en revanche de conclure à une non-régression globale. MCP-055 reste ouvert jusqu'à la revue des plans, la qualification des cinq réponses mal formées et la décision explicite Produit/RSSI. Les comparaisons sandbox relèvent de MCP-087 et ne sont pas artificiellement créditées à cette campagne.
