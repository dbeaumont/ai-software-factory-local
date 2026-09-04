# MCP-092 — Adaptateur cible GKE Jobs / Agent Sandbox

Le port `SandboxRuntime` sépare désormais les outils MCP de leur backend d'exécution :

- `DockerSandboxRuntime` reste le backend local, activé par `AI_FACTORY_SANDBOX_RUNTIME=docker` ;
- `GkeSandboxRuntime` est activable avec `AI_FACTORY_SANDBOX_RUNTIME=gke` lorsqu'une implémentation de `GkeJobController` est fournie par l'infrastructure cible.

L'adaptateur GKE ne reçoit jamais de commande de l'appelant. Il traduit l'opération MCP vers un profil déjà enregistré et transmet au contrôleur uniquement : identité d'exécution, profil, image immuable et digest, répertoire de tâche, classe de NetworkPolicy, limites CPU/mémoire/PIDs, timeout, politique de montage et noms des secrets serveur nécessaires.

Les correspondances réseau sont stables :

| Profil logique | NetworkPolicy cible |
|---|---|
| aucun réseau | `sandbox-deny-all` |
| qualité Sonar | `sandbox-quality-egress` |
| dépendances/scanners | `sandbox-dependency-egress` |

Le futur contrôleur implémentera la création/surveillance/annulation des Jobs ou sessions Agent Sandbox, l'injection Workload Identity/Secret Manager, la récupération bornée des logs et la réconciliation des orphelins. Le serveur MCP, ses sept outils, les identifiants de profils, les règles de verdict et les handles ne changent pas.

Un test vérifie la traduction du profil sécurité vers un manifeste contrôleur borné. La sélection GKE sans bean `GkeJobController` ne crée aucun runtime et fait donc échouer le démarrage, plutôt que de retomber sur Docker.
