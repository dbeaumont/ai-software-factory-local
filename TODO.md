# Backlog & Pistes d'évolution

## Fait

- [x] Point d'entrée HTTP avec reverse proxy Nginx (`reverse-proxy` relayant `/` vers `factory-web` et `/api/` vers `orchestrator`)
- [x] Intégration SonarQube et miroir Artifactory pour la qualité et la résolution d'artefacts
- [x] Scans de sécurité déterministes (SBOM Syft CycloneDX + Trivy vulnérabilités & secrets)
- [x] Boucle d'auto-réparation de diff (`PatchRepair`) lors de l'échec de `git apply --check`
- [x] Observabilité complète avec métriques Micrometer, Prometheus v3.5 et tableau de bord Grafana v12.1

## À étudier / Prochaines étapes

- [ ] Support d'OpenSpec / OpenAPI pour la définition formelle des contrats de tickets
- [ ] Intégration Keycloak / OpenID Connect pour l'authentification et le RBAC des utilisateurs
- [ ] Déclenchement de l'orchestration via GitHub Actions / GitLab CI au lieu du socket Docker
- [ ] Diagrammes d'architecture enrichis centrés sur les boucles de rétroaction agentiques
- [ ] Persistance des tâches et journaux d'audit dans une base de données de contrôle dédiée

