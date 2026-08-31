# MCP-015 — SLO initiaux des services MCP

> Statut : proposition initiale à valider par produit et exploitation  
> Politique machine-readable : `resources/mcp/policies/slo-policy-v1.yaml`  
> Fenêtre d'évaluation : 28 jours glissants

## 1. Décisions

| SLO | Cible initiale | Périmètre |
|---|---:|---|
| disponibilité | `>= 99,5 %` | chaque serveur MCP |
| latence p95 `context.list_tree` et `context.read_file` | `<= 1 s` | requêtes éligibles par outil |
| latence p95 `context.search_code` | `<= 2 s` | requêtes éligibles |
| latence p95 symboles et dépendances | `<= 3 s` | requêtes éligibles par outil |
| démarrage p95 d'un job sandbox | `<= 5 s` | de `ACCEPTED` à `RUNNING`, hors refus d'admission |
| démarrage p99 d'un job sandbox | `<= 15 s` | garde-fou secondaire |
| taux d'erreurs système MCP | `<= 0,5 %` | chaque serveur, sur 28 jours |

La cible de disponibilité autorise un budget maximal de `12 096 secondes`, soit `3 h 21 min 36 s`, par serveur et par fenêtre de 28 jours.

Ces objectifs sont volontairement moins stricts que la cible GCP de contrôle plane à 99,9 %. Ils correspondent à la phase locale/canary : deux services MCP en instance unique, stockage local et runner Docker. Ils devront être resserrés après la campagne shadow, puis après déploiement redondé.

## 2. Définition des SLI

### Disponibilité

```text
requêtes éligibles sans erreur système / total des requêtes éligibles
```

Une erreur système comprend les erreurs de transport ou de protocole, les timeouts et les erreurs internes. Les arguments invalides, refus d'autorisation, quotas, violations de policy et verdicts métier `REJECTED` ou `INDETERMINATE` ne consomment pas le budget de disponibilité : le serveur a répondu conformément au contrat.

Un résultat faux-positif ne relève pas d'un budget : une preuve absente ou altérée acceptée comme succès constitue une violation d'invariant et déclenche un arrêt de la bascule.

### Latence des lectures

La latence est mesurée côté orchestrateur, depuis l'appel du client MCP jusqu'à la validation complète de la réponse. Elle inclut transport, traitement serveur, parsing et vérification des limites. Elle exclut les retries afin que chaque tentative reste visible séparément.

Le p95 n'est publié que si la fenêtre contient au moins 100 requêtes éligibles. En dessous, le dashboard affiche « données insuffisantes » et conserve les valeurs brutes sans conclure au respect du SLO.

### Délai de démarrage sandbox

```text
started_at - accepted_at
```

Le SLI porte sur les jobs admis. Un job refusé pour quota ou saturation alimente le taux de refus et le SLI de disponibilité selon son code, mais ne reçoit pas artificiellement une durée de queue nulle. Les replays idempotents d'un job existant ne sont pas comptés comme de nouveaux démarrages.

### Taux d'erreurs MCP

```text
erreurs système MCP / requêtes MCP éligibles
```

Le taux est calculé par serveur. Les dimensions obligatoires sont `server`, `tool`, `outcome`, `error_code` et `version`; aucune donnée de ticket, chemin, prompt, dépôt ou résultat ne doit apparaître dans un label Prometheus.

## 3. Mesure Prometheus cible

Les métriques canoniques attendues sont :

- `ai_factory_mcp_requests_total{server,tool,outcome,error_code,version}` ;
- `ai_factory_mcp_request_duration_seconds_bucket{server,tool,version}` ;
- `ai_factory_sandbox_job_queue_duration_seconds_bucket` ;
- les compteurs existants de jobs soumis et refusés.

Exemples de calculs, à activer lorsque les histogrammes et dimensions canoniques seront disponibles :

```promql
sum by (server) (rate(ai_factory_mcp_requests_total{outcome="success"}[28d]))
/
sum by (server) (rate(ai_factory_mcp_requests_total{outcome=~"success|system_error"}[28d]))
```

```promql
histogram_quantile(
  0.95,
  sum by (le, server, tool) (rate(ai_factory_mcp_request_duration_seconds_bucket[1h]))
)
```

```promql
histogram_quantile(
  0.95,
  sum by (le) (rate(ai_factory_sandbox_job_queue_duration_seconds_bucket[1h]))
)
```

## 4. Instrumentation disponible et écarts

| Besoin | État actuel | Écart à fermer avant supervision du SLO |
|---|---|---|
| disponibilité context MCP | compteurs client appels/erreurs avec label `server` | ajouter `tool`, `outcome`, `error_code`, `version` |
| disponibilité sandbox MCP | compteurs appels/erreurs dédiés | normaliser avec la métrique MCP canonique et le label serveur |
| p95 lectures | timer global de collecte de contexte | timer par appel d'outil et histogramme Prometheus |
| délai de démarrage sandbox | timer `ai_factory_sandbox_job_queue_duration` | publier les buckets d'histogramme et vérifier `ACCEPTED -> RUNNING` |
| saturation | jauges jobs queued/running et rejets par raison | intégrer au dashboard et aux alertes de burn rate |

MCP-222 porte la création des dashboards et runbooks. L'instrumentation canonique doit être ajoutée avant de déclarer les SLO « supervisés » ; d'ici là, les métriques existantes servent de proxies et aucune conformité contractuelle n'est revendiquée.

## 5. Alertes et gouvernance

- Fast burn : alerte lorsque 6 fois le budget est consommé sur les fenêtres 1 h et 6 h.
- Slow burn : alerte lorsque 2 fois le budget est consommé sur les fenêtres 6 h et 3 jours.
- Toute violation d'un invariant (`false success`, mutation SCM sans approbation, job accepté sans état terminal ou récupérable) déclenche immédiatement un gel de la bascule, indépendamment du budget restant.
- Produit et exploitation revoient les cibles après au moins 28 jours de mesures en shadow/canary.
- Tout changement de cible modifie la version de politique et conserve l'historique de la décision.
