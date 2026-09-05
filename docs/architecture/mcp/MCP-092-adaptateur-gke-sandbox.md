# MCP-092 — Adaptateur cible GKE Jobs / Agent Sandbox

Le port `SandboxRuntime` sépare désormais les outils MCP de leur backend d'exécution :

- `ComposeSandboxRuntime` est le backend local, activé par `AI_FACTORY_SANDBOX_RUNTIME=compose`, et délègue à des runners statiques sans socket ;
- `GkeSandboxRuntime` est activable avec `AI_FACTORY_SANDBOX_RUNTIME=gke` et s'appuie sur `KubernetesGkeJobController`.

L'adaptateur GKE ne reçoit jamais de commande de l'appelant. Il traduit l'opération MCP vers un profil déjà enregistré et transmet au contrôleur uniquement : identité d'exécution, profil, image immuable et digest, répertoire de tâche, classe de NetworkPolicy, limites CPU/mémoire/PIDs, timeout, politique de montage et noms des secrets serveur nécessaires.

Les correspondances réseau sont stables :

| Profil logique | NetworkPolicy cible |
|---|---|
| aucun réseau | `sandbox-deny-all` |
| qualité Sonar | `sandbox-quality-egress` |
| dépendances/scanners | `sandbox-dependency-egress` |

Le contrôleur implémente la création/surveillance/annulation des Jobs, la récupération bornée des logs et la réconciliation des orphelins. Le déploiement cible fournit Workload Identity, Secret Manager, le stockage et les nœuds gVisor. Le serveur MCP, ses sept outils, les identifiants de profils, les règles de verdict et les handles ne changent pas.

Un test vérifie la traduction du profil sécurité vers un manifeste contrôleur borné. Une configuration incomplète fait échouer le démarrage, sans fallback vers Compose ou Docker.
