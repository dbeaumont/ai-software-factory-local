# ADR-A2A-005 — Conserver les endpoints A2A privés au cluster

- Statut : accepté
- Date : 6 septembre 2026
- Portée : runtimes d'agents A2A sur GKE

## Décision

Tous les endpoints A2A sont exposés uniquement par des Services Kubernetes `ClusterIP` dans
`ai-factory-agents`. Seuls les pods de l'orchestrateur identifiés par la NetworkPolicy peuvent joindre le port
8090. Aucun Ingress, Gateway API, NodePort, LoadBalancer ou `externalIPs` n'est autorisé pour ces services.

Une ValidatingAdmissionPolicy refuse les types et adresses externes ; une seconde refuse toute ressource Ingress,
Gateway, HTTPRoute ou GRPCRoute dans le namespace. Ces contrôles sont fail-closed et audités.

## Évolution éventuelle

Une exposition externe constitue une nouvelle frontière de confiance. Elle exige avant implémentation :

1. une ADR dédiée précisant consommateurs, terminaison TLS, authentification, WAF, quotas et responsabilité ;
2. une analyse de menaces dédiée couvrant Internet, DDoS, rejeu, SSRF, usurpation et fuite cross-tenant ;
3. une gate RSSI/plateforme explicite liée aux manifests, images et tests exacts ;
4. la suppression ou le remplacement délibéré des politiques d'admission de cette décision.

Une simple surcharge Kustomize ou modification de type de Service n'est donc pas une voie de déploiement valide.
