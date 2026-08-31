# Utilisation de la socket docker

Oui, la socket Docker est toujours utilisée, mais **plus par l’orchestrateur**.

Flux actuel :

```text
orchestrateur
  → HTTP MCP
  → sandbox-execution-mcp
  → commandes docker run/ps/rm
  → /var/run/docker.sock
```

- Le montage est dans [infrastructure/compose.yaml](/Users/david/Dev/ai-software-factory-local/infrastructure/compose.yaml:146).
- Son utilisation est dans [DockerSandboxRuntime.java](/Users/david/Dev/ai-software-factory-local/apps/mcp/sandbox-execution-server/src/main/java/com/example/aifactory/sandbox/service/DockerSandboxRuntime.java:39).
- Un test garantit que seul `sandbox-execution-mcp` possède la socket et que l’orchestrateur ne la monte pas : [ComposeMcpSecurityTest.java](/Users/david/Dev/ai-software-factory-local/apps/orchestrator/src/test/java/com/example/aifactory/config/ComposeMcpSecurityTest.java:28).

C’est une solution transitoire réservée au POC local. La cible consiste à remplacer ce contrôleur Docker par des Jobs GKE/gVisor, puis à supprimer complètement la socket avec MCP-092.
