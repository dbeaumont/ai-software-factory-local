# A2A-227 — Archive des preuves de migration

- Statut : `PASS`
- Date de clôture : 2026-09-07
- Candidat approuvé : `356eeebf5eab8c8d0c71884247a1789376a76d43`
- Manifeste : `docs/evidence/a2a/MANIFEST.sha256`
- Génération : `make a2a-evidence-manifest`
- Vérification : `make check-a2a-evidence-manifest`

## Couverture de l'archive

| Domaine exigé | Preuves principales |
|---|---|
| Tests unitaires et contrats | `A2A-160-UNIT-TESTS.md`, `A2A-161-CONTRACT-TESTS.md` |
| TCK A2A 1.0 | `tck/README.md`, rapports HTML, JSON et JUnit sous `tck/` |
| Interopérabilité | `A2A-163-INTEROPERABILITY.md` et son journal brut |
| Temporal et replay | `A2A-164-TEMPORAL-EMBEDDED.md` |
| Intégration et résilience | `A2A-165-COMPOSE-INTEGRATION.log`, `A2A-166-FAILURE-CAMPAIGN.md` et son journal brut |
| Concurrence et rotation | `A2A-167-CONCURRENCY.md` |
| Sécurité | `A2A-168-SECURITY-CAMPAIGN.md`, `A2A-172-DEPENDENCY-AUDIT.md` |
| Performance | `A2A-169-PERFORMANCE.md`, `A2A-169-PERFORMANCE.json` |
| Parité métier | `A2A-170-E2E-PARITY.md`, `A2A-171-PIPELINE-NON-REGRESSION.md` |
| Observabilité | `A2A-140-W3C-CONTEXT.md` à `A2A-149-READINESS.md`, `LOT-8-OBSERVABILITY-GATE.md` |
| Bascule franche | `A2A-181-ADMISSIONS-FREEZE.md` à `A2A-188-STABILIZATION.md` |
| Approbation humaine | `A2A-189-CUTOVER-APPROVAL.md` |
| Nettoyage | `A2A-220-DIRECT-RUNTIME-REMOVAL.md`, `A2A-221-NO-TRANSPORT-FLAGS.md`, `A2A-223-README.md` |

## Propriétés du manifeste

Le manifeste couvre récursivement chaque fichier régulier de `docs/evidence/a2a/`, y compris les sorties du TCK,
et exclut uniquement le manifeste lui-même afin d'éviter une dépendance circulaire. Les chemins sont relatifs à
la racine du dépôt, triés de façon déterministe et associés à leur SHA-256. La génération échoue si l'archive est
vide ou contient un lien symbolique ; la vérification échoue si un fichier est ajouté, retiré ou modifié.

Le digest du manifeste est consigné dans le ticket A2A-227 du plan après sa génération finale.
