# Backend sandbox GKE

Le manifeste `sandbox-platform.yaml` crée la frontière d'exécution minimale : namespace Pod Security `restricted`,
identités séparées, RBAC du contrôleur, quotas, limites, politiques réseau fermées et admission des images par digest.

Pré-requis fournis par la plateforme avant activation de `AI_FACTORY_SANDBOX_RUNTIME=gke` :

- un cluster GKE avec le `RuntimeClass` `gvisor` et des nœuds sandbox dédiés ;
- un PVC `factory-workspace` compatible avec les sous-répertoires de tâches ;
- un Secret `sandbox-profile-environment` contenant uniquement les clés requises par les profils ;
- un proxy d'egress portant le label `app.kubernetes.io/name=sandbox-egress-proxy` ;
- le namespace des services qualité portant le label `ai-factory.io/services=true` ;
- Workload Identity configurée pour `sa-sandbox-controller` et, si nécessaire, `sa-sandbox-job`.

Appliquer les ressources avec la chaîne de déploiement de la plateforme, puis exécuter les tests de politiques et la
campagne shadow avant toute activation partagée. Le manifeste n'installe volontairement ni cluster, ni stockage,
ni secret : ces ressources relèvent de l'infrastructure cible et ne doivent pas avoir de valeur locale par défaut.

## Gateway OpenTelemetry

Le répertoire `observability/` fournit une gateway Collector redondée pour Google Cloud Monitoring, Trace et
Logging. Elle reçoit OTLP uniquement en mTLS, enrichit les ressources Kubernetes, supprime les attributs sensibles
connus, borne mémoire/files/batchs et s'authentifie auprès de Google Cloud par Workload Identity. Aucun fichier de
clé de compte de service n'est accepté.

Les métriques internes de la gateway sont émises en OTLP vers un receiver HTTP limité au loopback du Pod, puis
passent dans le pipeline métriques Google Cloud. Le Service Kubernetes ne publie ni ce receiver ni un endpoint
Prometheus. Les erreurs et traces lentes sont conservées par tail sampling, complétées par un échantillon de 10 %.

Pré-requis plateforme :

- remplacer `PROJECT_ID` dans l'annotation du ServiceAccount et `REPLACE_WITH_GCP_PROJECT_ID` dans le
  `kustomization.yaml` pendant le rendu de déploiement ;
- créer le compte Google `otel-collector@PROJECT_ID.iam.gserviceaccount.com` avec les seuls rôles
  `roles/monitoring.metricWriter`, `roles/cloudtrace.agent` et `roles/logging.logWriter`, puis autoriser
  `roles/iam.workloadIdentityUser` au ServiceAccount Kubernetes ;
- synchroniser depuis Secret Manager le Secret Kubernetes `otel-gateway-tls` avec `tls.crt`, `tls.key` et `ca.crt` ;
- labelliser uniquement les namespaces émetteurs avec `telemetry.ai-factory.example/enabled=true` ;
- adapter l'egress `199.36.153.8/30` si le cluster n'utilise pas Private Google Access/VPC Service Controls.

Validation et rendu sans mutation du cluster :

```bash
kubectl kustomize infrastructure/gke/observability
kubectl apply --dry-run=server -k infrastructure/gke/observability
```

Le déploiement réel, les liaisons IAM, les certificats, les dashboards Cloud Monitoring, les notification channels
et les tests de zone/quota exigent un projet GCP de validation et une approbation plateforme. Ils ne possèdent pas
de valeur locale implicite.
