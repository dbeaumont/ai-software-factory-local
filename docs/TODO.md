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
- [ ] Suppression de l'accès direct à la socket docker
- [ ] Passer en Java 25 et SpringBoot 4.x
- [ ] Support de SpecKit pour la définition formelle des spécifications
- [ ] Support de OpenTelemetry pour la supervision
- [ ] Mise en place d'écrans de supervision : 
         - fonctionnelle : supervision des processus (ce qui a marché, interruptions et raisons)
         - technique : activité par agent (like top -o cpu)
         - finops : consommation par agent


