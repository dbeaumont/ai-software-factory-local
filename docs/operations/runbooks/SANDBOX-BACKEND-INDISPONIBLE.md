# Backend sandbox indisponible

## Déclencheurs

- `sandbox-execution-mcp` ou un runner Compose n'est plus `healthy` ;
- des jobs échouent avec `sandbox runtime unavailable` ;
- `AiFactorySandboxExecutionFailures` ou `AiFactorySandboxMaintenanceFailure` est active ;
- un Job GKE reste actif après sa deadline ou ne peut pas être supprimé.

## Mise en sécurité immédiate

1. Suspendre les nouvelles admissions sandbox ou activer le kill switch du serveur `sandbox-execution-mcp`.
2. Ne pas relancer une opération dont l'état terminal est inconnu avant réconciliation par `execution_id`.
3. Ne jamais réintroduire le runtime Docker ni monter une socket pour contourner l'incident.
4. Préserver `sandbox-job-state`, les logs et les preuves avant toute reconstruction.

## Diagnostic Compose local

```bash
docker compose --env-file .env -f infrastructure/compose.yaml ps
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=200 \
  sandbox-execution-mcp sandbox-runner-readonly sandbox-runner-write \
  sandbox-runner-dependency sandbox-runner-quality
./scripts/check-no-docker-socket.sh
```

Vérifier le healthcheck, le réseau `sandbox-control`, le digest `AI_FACTORY_SANDBOX_IMAGE`, le jeton interne et les
droits du volume `factory-workspace`. Une reconstruction des runners est sûre après arrêt des admissions ; leur
redémarrage détruit tous leurs groupes de processus.

## Diagnostic GKE

```bash
kubectl -n ai-factory-sandbox get jobs,pods
kubectl -n ai-factory-sandbox describe job <job>
kubectl -n ai-factory-sandbox logs job/<job> -c sandbox --tail=200
kubectl auth can-i --as=system:serviceaccount:ai-factory-sandbox:sa-sandbox-controller \
  create jobs.batch -n ai-factory-sandbox
```

Vérifier ensuite le PVC, le `RuntimeClass` gVisor, les quotas, les événements d'admission, les NetworkPolicies et
l'accès du contrôleur à son jeton de ServiceAccount. La réconciliation au démarrage annule les Jobs encore actifs
portant le label contrôlé par l'usine.

## Annulation et reprise

- Utiliser `sandbox.cancel_execution` avec le contexte de tâche d'origine.
- Si le MCP est indisponible, couper d'abord les admissions puis supprimer uniquement le Job identifié par
  `ai-factory.io/execution-id`.
- Redémarrer le MCP pour déclencher la réconciliation ; vérifier que les jobs restaurés passent dans un état terminal.
- Soumettre une nouvelle tentative avec une nouvelle clé d'idempotence seulement après résolution de l'état précédent.

## Rotation des secrets

### Compose local

1. Arrêter les admissions et attendre la fin des jobs actifs.
2. Exécuter `make init` pour valider ou générer `AI_FACTORY_SANDBOX_RUNNER_TOKEN`.
3. Faire pivoter les jetons Artifactory/Sonar dans `.env`/`.vault` avec les scripts de bootstrap.
4. Recréer `sandbox-execution-mcp` et les quatre runners ; ne jamais afficher les valeurs dans les logs.

### GKE

1. Faire pivoter les valeurs dans Secret Manager.
2. Synchroniser le Secret `sandbox-profile-environment` sans ajouter de clé hors allow-list.
3. Redémarrer uniquement le contrôleur si son identité change ; les Jobs utilisent une identité distincte.
4. Révoquer l'ancienne version après vérification d'un job canary.

## Critères de retour au service

- les runners ou Jobs acceptent un canary de validation sans fuite de secret ;
- aucune exécution orpheline n'est active ;
- les files et taux d'échec reviennent sous leurs seuils pendant vingt minutes ;
- le contrôle d'absence de socket et les tests de sécurité passent ;
- l'incident, sa cause et les identifiants d'exécution touchés sont consignés.
