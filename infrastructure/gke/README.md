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
