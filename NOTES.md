# Retrait de la socket Docker

La socket Docker n'est plus utilisée par les composants applicatifs.

Flux actuel :

```text
orchestrateur
  → HTTP MCP
  → sandbox-execution-mcp
  → API interne authentifiée
  → runner Compose statique par classe de profil
```

- La topologie locale est définie dans `infrastructure/compose.yaml`.
- `ComposeSandboxRuntime` distribue uniquement des identifiants de profils enregistrés.
- `ComposeMcpSecurityTest` impose zéro montage de socket, zéro port hôte sur les runners et le profil de sécurité attendu.
- `scripts/check-no-docker-socket.sh` empêche la réintroduction de l'ancien runtime dans les fichiers actifs.

Les runners persistants restent réservés au développement local. Les environnements partagés utilisent
`GkeSandboxRuntime` et `KubernetesGkeJobController` avec Jobs GKE/gVisor.


A voir :
- voir pour avoir un contexte d'architecture sous forme de PDF, DOCX, etc
- Voir pour reprise des projets existants
  . Proposition de refonte dans les résultats
- Mise en place de OpenTelemetry à la place de Prométheus + grafana?
- Mise en place d'acrans de supervision :
  . fonctionnelle :   ajouter des opérations de maintenance (logs, stop, retry, continue, ...)
  . technique : performances, finops
