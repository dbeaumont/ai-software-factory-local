# Runbook — agent défaillant

## Objectif

Traiter boucle, épuisement de budget, dérive de coût et erreur de contrat sans élargir les permissions ni accepter
une sortie invalide. Ce runbook couvre les alertes `AiFactoryAgentLoopDetected`,
`AiFactoryAgentBudgetExhausted`, `AiFactoryAgentCostSpike` et `AiFactoryAgentContractError`.

## Confinement immédiat

1. Identifier `task_id`, `attempt_id`, rôle, délégation, modèle, prompt et mode depuis les métadonnées ; ne pas
   copier le contenu des prompts ou résultats dans l'incident.
2. Suspendre le rôle concerné par le kill switch si l'erreur se répète ou si un effet non autorisé est suspecté.
3. Ne jamais augmenter budget, tours, outils ou portée pendant l'incident.
4. Conserver la tentative en échec et ses preuves ; une nouvelle exécution reçoit un nouvel `attempt_id`.

## Diagnostic

```promql
sum by (role, stop_condition, reason) (increase(ai_agent_failures[15m]))
sum by (role) (increase(ai_agent_cost_micros[15m]))
sum by (role, outcome) (rate({__name__="ai_agent_duration.count"}[15m]))
```

Consulter la tâche sans exposer son contenu sensible :

```bash
curl -fsS "http://localhost:${ORCHESTRATOR_PORT:-8080}/api/tasks/<task-id>"
docker compose -f infrastructure/compose.yaml logs --tail=200 orchestrator
```

Classer la cause : `repeated_call`/`max_turns`, budget hôte, contrat final, indisponibilité d'outil, modèle/prompt,
ou contexte insuffisant. Vérifier le fingerprint du prompt, la version du modèle, les contrats et les preuves
citées par identifiant.

## Rétablissement

Une relance ciblée n'est autorisée que si l'API confirme que la délégation est récupérable :

```bash
curl -fsS -X POST -H 'Content-Type: application/json' \
  -d '{"reason":"cause corrigée et preuves vérifiées","actor":"operations"}' \
  "http://localhost:${ORCHESTRATOR_PORT:-8080}/api/tasks/<task-id>/delegations/<delegation-id>/retry"
```

Sinon corriger prompt, contrat, routage ou dépendance dans une nouvelle version et exécuter les tests de
régression. Utiliser le fallback pipeline pour une tâche éligible, jamais pour contourner un gate :

```bash
curl -fsS -X POST -H 'Content-Type: application/json' \
  -d '{"reason":"agent hiérarchique isolé après incident","actor":"operations"}' \
  "http://localhost:${ORCHESTRATOR_PORT:-8080}/api/tasks/<task-id>/fallback"
```

## Vérification et clôture

- contrat valide et succès déterministe sur le cas de régression ;
- coût et nombre de tours sous les budgets existants ;
- aucun outil ou scope supplémentaire accordé ;
- manifestes et digests de la nouvelle tentative cohérents ;
- alerte éteinte sur deux fenêtres et cause racine documentée.

## Escalade

Escalader à l'équipe IA pour une dérive modèle/prompt, à Architecture pour une insuffisance de contrat ou de
routage, et à Sécurité pour toute tentative d'escalade, injection ou contenu sensible. Basculer selon le
[runbook de rollback](ROLLBACK-MULTI-AGENTS.md) si plusieurs rôles ou tâches sont affectés.
