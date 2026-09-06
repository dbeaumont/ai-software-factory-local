# Preuve A2A-149 — readiness globale explicable

Date : 2026-09-06

## Agrégateur livré

Le health indicator `a2aFleet` inventorie les 14 rôles agent/sub-agent du catalogue. Lorsque A2A est activé, il
vérifie pour chacun la carte signée et les pollers Temporal workflow/activity de la file
`a2a-agent-<role>-v1`. Il vérifie également le client A2A, le resolver de cartes, OAuth2, les notifications push,
mTLS et le namespace Temporal.

Toute condition non `READY` produit une readiness `DOWN`, `admissions=SUSPENDED` et une liste sanitizée de
bloqueurs `{kind, role?, resource, status}`. Aucun endpoint, certificat, secret ou message d'exception n'est
retourné. Trois jauges OTLP publient l'état global, le nombre de rôles bloqués et le nombre de dépendances
bloquantes.

Avant la bascule, `AI_FACTORY_A2A_ENABLED=false` rend explicitement `admissions=A2A_DISABLED`. À la bascule
franche, la variable passe à `true` et le healthcheck Compose empêche l'orchestrateur d'être déclaré prêt tant que
l'ensemble de la flotte ne l'est pas.

## Qualification

Le test couvre ouverture complète, blocage combiné d'une dépendance/carte/file et état pré-bascule. Le gate Docker
de l'orchestrateur est désormais fail-fast, inclut ce test et embarque les 21 ressources contractuelles A2A qui
manquaient jusque-là au JAR.

```text
Copying 21 resources from external-a2a to target/classes/a2a
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
ai-software-factory-orchestrator-1 ... Up ... (healthy)
{"components":{"a2aFleet":{"details":{"admissions":"A2A_DISABLED","requiredRoles":[...],"blockers":[]},"status":"UP"},"temporalEngine":{"details":{"ticketEngine":"ACTIVE","registeredWorkers":7,"requiredWorkers":7},"status":"UP"}},"status":"UP"}
```

Le démarrage réel Compose a aussi fermé trois défauts révélés par ce contrôle : ressources A2A absentes de
l'image, constructeurs Spring ambigus et repositories finaux incompatibles avec le proxy d'exception translation.
