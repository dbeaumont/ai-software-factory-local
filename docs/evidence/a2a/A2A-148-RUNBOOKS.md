# Preuve A2A-148 — runbooks d'exploitation A2A

Date : 2026-09-06

## Procédures livrées

Huit runbooks dédiés couvrent carte invalide, agent indisponible, tâche bloquée, callback perdu, divergence d'état,
certificat expiré, saturation et rollback A2A. Chacun impose les mêmes étapes opérationnelles : détection,
confinement immédiat, diagnostic, rétablissement, vérification/clôture et escalade.

Les procédures sont fail-closed : aucune ne désactive mTLS, OAuth2, validation de carte, ACL ou idempotence ; aucune
ne corrige une autorité directement en base. Le rollback conserve Temporal comme orchestrateur unique et redéploie
le dernier runtime A2A qualifié, sans retour aux appels directs/in-process.

## Raccordement et validation

Les neuf alertes SigNoz A2A pointent maintenant vers les runbooks spécifiques. Le validateur contrôle l'existence
des huit fichiers, leurs sections obligatoires et chaque lien `runbook_url`. Les commandes de configuration
Compose citées ont été exercées sur la topologie locale.

```text
A2A runbooks validated: 8 procedures, 9 alert links.
A2A Compose runtime verified: 14 explicit roles share ai-factory-a2a-agent-runtime:${AI_FACTORY_A2A_RUNTIME_VERSION:-0.1.0}.
A2A internal network verified for orchestrator and 14 private agent endpoints.
Role-scoped MCP Compose networks verified for all 14 A2A agents.
SigNoz rule updated: AiFactoryA2aPollerAbsent
...
SigNoz rule updated: AiFactoryA2aStateDivergence
signoz-bootstrap-1 exited with code 0
```

Le catalogue `docs/operations/runbooks/README.md` expose les huit nouvelles entrées aux opérateurs.
