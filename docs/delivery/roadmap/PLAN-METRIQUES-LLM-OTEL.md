# Plan de mise en place des métriques LLM OpenTelemetry

## Objectif

Rendre visibles dans SigNoz le volume, la latence, les échecs, les tokens et le coût estimé des appels LLM, sans exporter le contenu des conversations ni des identifiants à forte cardinalité.

Le dashboard versionné cible les noms de métriques de ce plan : `ai_factory_llm_requests_total`, `ai_factory_llm_request_duration_seconds`, `ai_factory_llm_tokens_total` et `ai_factory_llm_cost_micros_total`.

## Plan d'exécution

- [x] Définir le contrat de métriques dans le runtime A2A : noms, unités, types et sémantique des valeurs absentes.
- [x] Conserver uniquement les dimensions bornées `provider`, `model`, `outcome`, `direction` et `currency` ; ne jamais ajouter `task_id`, prompt, réponse, clé API ni identifiant de conversation à une métrique.
- [x] Créer `ai_factory_llm_requests_total` comme compteur, incrémenté une fois par tentative de requête et étiqueté avec `outcome=success|error|timeout`.
- [x] Créer `ai_factory_llm_request_duration_seconds` comme histogramme autour de l'appel HTTP au gateway LiteLLM.
- [x] Lire les champs `usage.prompt_tokens` et `usage.completion_tokens` de la réponse, puis incrémenter `ai_factory_llm_tokens_total` avec `direction=input|output`.
- [x] Publier `ai_factory_llm_cost_micros_total` seulement lorsqu'un coût attesté est disponible ; associer `currency` et conserver l'état `AVAILABLE`, `PARTIAL` ou `UNAVAILABLE` conformément à la politique de coût.
- [x] Ne pas transformer l'absence de coût fournisseur en zéro ; documenter la source et la version de tarification si un coût déterministe est calculé.
- [x] Instrumenter les erreurs de transport, refus, filtrages et réponses tronquées afin que leur cause soit visible dans le libellé borné `outcome`.
- [x] Ajouter des tests unitaires de succès, échec, délai dépassé, réponse sans usage et réponse sans coût pour vérifier compteurs, histogramme et absence de données sensibles.
- [x] Configurer explicitement l'export OTLP des métriques du runtime A2A vers le collecteur, avec une période de 15 secondes.
- [ ] Vérifier en exécution que l'export OTLP du runtime A2A atteint le collecteur et que les processeurs de redaction continuent à supprimer prompts, résultats et en-têtes d'authentification.
- [ ] Exécuter une tâche représentative, puis vérifier dans SigNoz les séries par `provider` et `model`, les traces corrélées et l'absence de contenu de conversation.
- [ ] Réviser les seuils d'alerte après une période de référence ; ne pas définir de seuil de coût avant d'avoir une couverture de coût fiable.

## Dashboard SigNoz

- [x] Versionner le dashboard `AI Factory LLM` dans `infrastructure/observability/signoz/dashboards/llm.json`.
- [x] Afficher le débit des requêtes par fournisseur, modèle et résultat.
- [x] Afficher la latence p95, le ratio d'erreurs, les tokens par direction, les tokens par requête, le coût estimé et sa disponibilité.
- [x] Réutiliser les liens opérationnels et les variables de recherche des autres dashboards, sans variable à forte cardinalité dans les requêtes de métriques.
- [x] Vérifier statiquement les panneaux, requêtes et dimensions du dashboard avec `scripts/check-llm-signoz-dashboard.py`.
- [ ] Provisionner ou mettre à jour le dashboard dans l'instance locale avec `make bootstrap-signoz`.
- [ ] Valider les requêtes contre l'API SigNoz avec `./scripts/validate-signoz-queries.sh` après le déploiement des métriques.

## Critères d'acceptation

- [ ] Chaque tentative LLM produit exactement un point de compteur de requêtes, y compris en échec.
- [ ] Les tokens retournés par le fournisseur concordent avec les compteurs exportés pour une exécution de référence.
- [ ] Les coûts inconnus restent inconnus et ne sont pas agrégés comme des coûts nuls.
- [ ] Aucune donnée de prompt, réponse, secret ou identifiant de tâche n'apparaît dans les étiquettes de métriques ni dans le dashboard.
- [ ] Les sept panneaux du dashboard présentent des données après une tâche de démonstration.
