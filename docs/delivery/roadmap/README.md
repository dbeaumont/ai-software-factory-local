# Backlog et pistes d'évolution

> État revu le 7 septembre 2026. Les éléments cochés sont présents et actifs dans le déploiement Compose ; ils ne
> valent pas qualification automatique de la cible GKE ou d'un usage d'entreprise.

## Baseline active

- [x] Point d'entrée Nginx, interface web et API orchestrateur.
- [x] Temporal obligatoire, sept files spécialisées et projection métier PostgreSQL.
- [x] Quatorze runtimes agents A2A isolés, Agent Cards signées et vingt-huit pollers dédiés.
- [x] Cinq serveurs MCP séparant contexte, sandbox, assurance, Evidence et SCM.
- [x] Runners sandbox Compose statiques sans `/var/run/docker.sock`.
- [x] Backend sandbox GKE et manifests préparés sans modifier le contrat métier.
- [x] OpenTelemetry pour métriques, traces et logs, avec stockage et dashboards SigNoz.
- [x] Sauvegarde, restauration, rétention et rollback couverts par les qualifications Temporal/A2A locales.
- [x] `make all`, `up`, `build`, `restart`, `status`, `down` et `clean` alignés sur le profil A2A complet.

## Backlog priorisé

- [ ] Ajouter OIDC et RBAC/ABAC, avec séparation demandeur/approbateur.
- [ ] Imposer une CI distante pour tests, replay Temporal, contrats, scans, signatures et provenance.
- [ ] Externaliser les secrets vers Secret Manager et utiliser Workload Identity.
- [ ] Qualifier la topologie agents et la sandbox sur un cluster GKE réel.
- [ ] Automatiser les sauvegardes, restaurations et exercices PRA avec RTO/RPO mesurés.
- [ ] Relier le kill switch à une commande opérateur et une vue IHM auditée.
- [ ] Compléter la console de supervision métier, technique et FinOps.
- [ ] Mesurer qualité, coût et productivité sur un corpus représentatif.
- [ ] Qualifier une destination d'astreinte externe et sa déduplication.
- [ ] Définir puis tester les SLO de production et les seuils de capacité.

## Interface d'exploitation actuelle

| Commande | État |
|---|---|
| `make build` | Construit la sandbox, LiteLLM, l'identité et l'image commune des 14 agents, les MCP et les applications |
| `make up` | Démarre par étapes le profil `a2a-full` et applique la barrière de disponibilité |
| `make all` | Supprime les volumes Docker, reconstruit, bootstrappe et vérifie une usine neuve |
| `make verify-ready` | Contrôle provisioning, Agent Cards, 7 files de contrôle, 28 pollers A2A et admissions |
| `make restart` | Recrée l'orchestrateur, réactive son Build ID et revalide Temporal/A2A |
| `make status` | Affiche tous les services et jobs one-shot du profil complet |
| `make down` | Arrête le profil complet sans supprimer les volumes |
| `make clean` | Supprime tous les volumes Docker ; opération destructive |
| `make urls` | Affiche uniquement les URLs, jamais les mots de passe |

## Plans réalisés et trajectoires

- [Migration Temporal](../migrations/raccordement-orchestrateur-temporal.md)
- [Migration des agents vers A2A](../migrations/migration-agents-a2a-temporal.md)
- [Retrait de la socket Docker](../migrations/retrait-docker-socket.md)
- [Migration OpenTelemetry](../migrations/migration-opentelemetry.md)
- [Roadmap détaillée](ameliorations-usine-logicielle-ia.md)
