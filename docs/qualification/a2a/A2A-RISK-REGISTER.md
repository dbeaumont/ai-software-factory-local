# A2A-026 — Registre des risques de migration

## Échelle et gouvernance

- Probabilité (`P`) et impact (`I`) vont de 1 à 5 ; exposition = `P × I`.
- `CRITIQUE` ≥ 16, `ÉLEVÉ` de 10 à 15, `MODÉRÉ` de 5 à 9, `FAIBLE` < 5.
- Un risque reste `OUVERT` tant que les tickets de traitement et le test de preuve associés ne sont pas terminés.
- Le propriétaire accepte le risque résiduel dans la gate de cutover ; une case du plan ne vaut pas acceptation.

## Registre prioritaire

| ID | Risque / scénario | P | I | Niveau | Prévention | Détection et preuve attendue | Réponse | Propriétaire | Tickets |
|---|---|---:|---:|---|---|---|---|---|---|
| A2A-R01 | Double exécution logique après timeout ambigu ou retry de `SendMessage` | 4 | 5 | CRITIQUE | `messageId` déterministe, unicité transactionnelle et lookup avant renvoi | test concurrent et panne ACK, compteur de collision/déduplication | réconcilier tâche/workflow/artefacts avant toute relance | plateforme | 047, 060, 065, 082, 167 |
| A2A-R02 | Perte, duplication ou désordre d'une notification push | 4 | 4 | CRITIQUE | séquence monotone, inbox idempotente, callback fixe | timer `GetTask`, âge de notification, test callback coupé | rejouer transitions manquantes sans réexécuter le rôle | plateforme | 064, 083, 085, 165 |
| A2A-R03 | Divergence entre état A2A, corrélation et historique Temporal | 3 | 5 | ÉLEVÉ | autorité explicite, transitions transactionnelles, version optimiste | métrique de divergence et campagne de restart | figer admissions, choisir l'historique Temporal et réconcilier la projection | plateforme | 049, 065, 067, 068, 146 |
| A2A-R04 | Rejeu d'un message, callback ou jeton valide mais ancien | 4 | 4 | CRITIQUE | nonce/ID stable, audience, expiration courte, fenêtre anti-rejeu | audit de rejeu et tests avec séquences/timestamps anciens | refuser sans révéler la tâche ; révoquer la clé compromise | sécurité | 047, 100–103, 108, 168 |
| A2A-R05 | Usurpation ou altération d'une Agent Card | 3 | 5 | ÉLEVÉ | JWS RFC 8785, `kid`, issuer/provider et origine allow-listée | carte forgée/expirée/rotation dans tests de contrat | fail-closed, invalider cache, suspendre le rôle | sécurité | 054–058, 168 |
| A2A-R06 | SSRF via carte, endpoint, callback, redirection ou URI Evidence | 4 | 5 | CRITIQUE | registre statique, HTTPS, validation DNS/IP et redirections interdites | fuzz URLs, canaris metadata/loopback/link-local | bloquer, auditer et retirer le workload fautif | sécurité | 056, 064, 105, 168 |
| A2A-R07 | Fuite de prompt, code, secret ou preuve dans message, erreur, log, trace ou historique | 3 | 5 | ÉLEVÉ | références digestées, limites, redaction, capture contenu désactivée | canaris de secrets, scan des historiques/logs/traces | rotation immédiate et purge selon procédure d'incident | sécurité | 007, 041, 048, 106, 109, 140–144 |
| A2A-R08 | Saturation des agents, du stockage, de Temporal ou du fournisseur LLM | 4 | 4 | CRITIQUE | quotas tenant/rôle, queues bornées, backpressure et autoscaling | backlog, tâches actives, mémoire, throttling et test de charge | fermer admissions, drainer, augmenter capacité bornée | plateforme | 072, 107, 129, 143, 146, 169 |
| A2A-R09 | Accès cross-tenant ou révélation de l'existence d'une tâche | 3 | 5 | ÉLEVÉ | autorisation avant lookup, clés composées tenant/client et ACL | tests lecture/list/cancel croisés | fail-closed, audit et isolation de la projection | sécurité | 061–063, 065, 103, 168 |
| A2A-R10 | Confused deputy : un agent obtient un skill, outil ou effet d'un autre rôle | 4 | 5 | CRITIQUE | identité par rôle, scopes skill, matrice MCP côté serveur, aucun credential partagé | tests négatifs par couple rôle/outil/skill | révoquer jeton, suspendre rôle et tâche | sécurité | 053, 058, 100, 102–104 |
| A2A-R11 | Un agent délègue directement et contourne le DAG/gates/budgets | 2 | 5 | ÉLEVÉ | aucun client/egress vers les pairs ; intention seulement en artefact | NetworkPolicy, tests egress et recherche statique | annuler tâche, refuser résultat et auditer | plateforme | 003, 036, 104, 121–122 |
| A2A-R12 | Annulation perdue ou résultat accepté après annulation | 3 | 5 | ÉLEVÉ | `CancelTask` idempotent, état terminal et séquence liés au workflow | course cancel/complete et délai de propagation | état de réconciliation explicite, preuve conservée | plateforme | 063, 086, 147, 167 |
| A2A-R13 | Artefact empoisonné, remplacé ou lié à une autre source/tentative | 3 | 5 | ÉLEVÉ | URI interne, SHA-256, taille/media type, sourceCommit et tenant obligatoires | relecture Evidence, mismatch digest et tests adversariaux | rejeter non retryable et bloquer toute gate/SCM | sécurité | 041, 043–046, 069, 106 |
| A2A-R14 | Incompatibilité de version, binding, carte ou schéma entre client et serveur | 3 | 4 | ÉLEVÉ | A2A 1.0 strict, SDK épinglé, schémas versionnés et gate de cohérence | TCK, tests d'interopérabilité et `VersionNotSupportedError` | refuser le downgrade et garder admissions fermées | plateforme | 010–017, 052, 058, 161–163 |
| A2A-R15 | Rupture de déterminisme/replay d'un workflow Temporal existant | 3 | 5 | ÉLEVÉ | nouveau type/build, versioning pinned, aucune I/O dans workflow | replay des historiques gelés | drainer ancien Build ID, rollback vers release A2A compatible | plateforme | 091, 164, 184, 200–201 |
| A2A-R16 | Croissance non bornée des messages, historiques et tables | 4 | 4 | CRITIQUE | limites avant parsing, rétention, pagination et `continue-as-new` | tailles/âges, quota disk et tests payload extrême | refuser admission, compacter/prolonger via procédure contrôlée | plateforme | 048, 057, 062, 065, 092, 107 |
| A2A-R17 | Rotation de certificat/clé/JWKS coupe des tâches actives | 3 | 4 | ÉLEVÉ | chevauchement de clés et cache revalidé | test rotation pendant tâche et notification | maintenir ancienne confiance jusqu'au drainage borné | sécurité | 054, 057, 101–102, 167 |
| A2A-R18 | Dépendance LLM/MCP/Evidence indisponible ou résultat ambigu | 4 | 4 | CRITIQUE | readiness par rôle, retry classé, idempotence d'outil et evidence-first | pannes injectées et état `stuck`/dependency | attente/réconciliation Temporal, jamais fallback direct | plateforme | 068, 070–071, 148, 166 |
| A2A-R19 | Régression latence, tokens, coût ou mémoire masquée par l'absence de baseline par rôle | 4 | 3 | ÉLEVÉ | métriques bornées obligatoires dès le runtime extrait | comparaison à A2A-025 et campagne performance par rôle | bloquer cutover si seuils non approuvés | plateforme | 142–147, 169, 180 |
| A2A-R20 | SDK/image compromis ou vulnérabilité critique de supply chain | 3 | 5 | ÉLEVÉ | versions/digests, SBOM, licences, signature et provenance | scan Trivy et vérification de signature à chaque build | bloquer release et mettre à niveau sans downgrade protocolaire | sécurité | 011, 111, 172, 180 |

## Conditions de fermeture

Un risque ne peut passer à `TRAITÉ` que si :

1. les tickets listés sont terminés ou la gate référence une mesure compensatoire explicite ;
2. un test reproductible exerce réellement le scénario négatif ;
3. une preuve digestée est archivée dans `docs/evidence/a2a/` ;
4. le dashboard/alerte et le runbook existent lorsque la détection est opérationnelle ;
5. le propriétaire approuve le risque résiduel lié au commit et aux images exacts.

Les risques A2A-R01, R02, R03, R05, R06, R07, R08, R10, R15 et R18 sont bloquants pour la coupure. Aucun ne
peut être accepté implicitement par l'existence d'un rollback, car le rollback ne réactive jamais les appels
directs en mémoire.
