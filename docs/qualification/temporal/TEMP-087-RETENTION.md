# TEMP-087 — rétention Temporal et conservation légale Evidence

La campagne du 6 septembre 2026 vérifie les trois comportements complémentaires :

- le namespace local annonce exactement 604 800 secondes (7 jours) de rétention d'historique ;
- les preuves expirent selon leur classe (90, 180 ou 365 jours), sauf lorsqu'un legal hold actif et audité protège
  toute la tentative ; un marqueur de hold illisible bloque également la purge (fail-closed) ;
- après expiration d'un historique, la reconstruction retourne `ProjectionHistoryUnavailableException`, conserve la
  projection existante et poursuit les autres tâches de la page sans inventer de faits depuis Evidence seul.

Les legal holds sont réservés aux acteurs `security-officer` et `legal-officer`, exigent motif et date d'expiration,
ne peuvent pas être raccourcis, et journalisent uniquement le digest SHA-256 du motif. Une libération est elle aussi
autorisée et auditée avant que la purge redevienne possible.

Commandes de qualification :

```bash
./scripts/test-temporal-retention.sh 7
mvn -f apps/mcp/evidence-server/pom.xml test
mvn -f apps/orchestrator/pom.xml test
```
