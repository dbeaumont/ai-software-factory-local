# Preuve OTEL-050 — gateway GKE

- Date : 2026-09-05
- Portée : rendu et validation hors cluster
- Résultat : manifests prêts à recevoir les valeurs et credentials de la plateforme.

## Contrôles réussis

- `kubectl kustomize infrastructure/gke/observability` rend toutes les ressources.
- `kubectl kustomize` valide le rendu local ; `kubectl apply --dry-run` reste volontairement non validé faute de
  serveur d'API Kubernetes configuré dans cet environnement.
- La configuration embarquée passe `otelcol-contrib 0.160.0 validate` avec le digest versionné.
- Deux gateways StatefulSet, rolling update, PDB, répartition par zone et HPA 2–6 sont déclarés.
- Chaque replica possède une PVC `standard-rwo` de 5 Gio pour la file `file_storage`. La taille mémoire 4 096 et
  le débit de tail sampling 400 traces/s couvrent la mesure locale de
  10 400 traces drainées en 32 s avec marge, sans présenter cette extrapolation comme un SLO GKE.
- Les endpoints OTLP exigent un certificat client signé par la CA montée depuis `otel-gateway-tls`.
- Pod Security `restricted`, ServiceAccount dédié, RBAC de lecture, NetworkPolicy, ressources bornées, filesystem
  en lecture seule, seccomp et suppression des capabilities sont déclarés.
- L'exporteur `googlecloud` reçoit métriques, traces et logs et utilise `GOOGLE_CLOUD_PROJECT`; aucune clé de compte
  de service n'est versionnée ou montée.
- Les métriques internes du Collector sont réinjectées en OTLP sur un receiver loopback non exposé, puis exportées
  vers Cloud Monitoring. Aucun endpoint Prometheus n'est créé.
- Le tail sampling conserve erreurs et traces lentes, puis 10 % du trafic restant.

## Activation restant à la plateforme

Le dépôt ne possède ni projet GCP, ni cluster, ni certificats, ni notification channels. Le déploiement réel doit
remplacer les placeholders, créer la liaison Workload Identity et le Secret TLS depuis Secret Manager, appliquer
les rôles writer minimaux, puis exécuter le dry-run serveur et la campagne zone/quota. Ces opérations ne sont pas
simulées et leurs cases restent ouvertes dans le plan.
