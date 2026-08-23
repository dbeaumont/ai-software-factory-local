# Sécurité du POC

Ce dépôt est un prototype local. Il matérialise plusieurs barrières de la cible mais **ne doit pas être exposé tel quel en production**.

- Le conteneur `orchestrator` monte `/var/run/docker.sock`. Cela équivaut pratiquement à un privilège root sur l'hôte Docker. Acceptable uniquement pour un POC local dédié.
- L’application du patch utilise `--network none`. Les builds/scans rejoignent le réseau Docker local `ai-factory-local` afin de résoudre les dépendances et joindre les services du POC ; en cible, remplacer cet accès par une egress allow-list et des proxies d’artefacts. Les sandboxes utilisent `--cap-drop ALL`, `no-new-privileges`, limites CPU/RAM/PIDs et sont détruites après usage.
- Le commit/push/PR est placé derrière une validation humaine explicite (`POST /api/tasks/{id}/approve`).
- Le token Gitea doit être courtement privilégié et stocké dans `.env` uniquement pour le POC. En cible : workload identity / secret manager.
- Les sorties LLM ne contournent jamais les tests/scans déterministes.
- Le mode `dryRun=true` est le mode par défaut pour tester la chaîne sans modifier le dépôt.

## Passage à une cible industrielle

Remplacer le Docker socket par un **Sandbox API** ou des **Kubernetes Jobs** avec identité de workload, NetworkPolicy, egress allow-list, volumes éphémères et quotas. Ajouter un AI Gateway, un MCP Gateway privé, SSO/RBAC, policy-as-code et une supply chain signée.
