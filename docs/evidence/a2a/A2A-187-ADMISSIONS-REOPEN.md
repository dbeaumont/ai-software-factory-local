# A2A-187 — Réouverture des admissions

Date de qualification : 2026-09-07 03:38:19 UTC  
Décision : `OPEN`  
Révision d'admission : `47`  
Motif : `normal_operation`

## Candidat effectivement déployé

- commit source de la correction d'inbox : `90057523c6e9460840743bd6032e8713b7e2a65b` ;
- image orchestrateur : `sha256:a58d1600269abd9d9b0c6276129a12f29d9187d4dd0b3fedc59faaf1d4fd2ff0` ;
- image commune aux quatorze runtimes A2A :
  `sha256:a9f616dd78e43300bfc7f92d992d9aae6a322711590a783401667c3311543ba8` ;
- version runtime : `0.2.0-a2a-cutover.5` ;
- Build ID Temporal des agents : `a2a-agent-eb776cd` ;
- Build ID Temporal de l'orchestrateur : `a2a-cutover-d4b55c7`.

## Contrôles avant ouverture

- `A2A_START_TIMEOUT_SECONDS=300 ./scripts/start-a2a-profile.sh full` : stockage et identité sains,
  OAuth2/JWT/mTLS actifs, quatorze runtimes `healthy`, quatorze Agent Cards signées et valides ;
- `./scripts/test-temporal-compose-readiness.sh` : namespace, UI, application et sept files de coordination
  prêts ; les quatorze rôles publient chacun un poller workflow et un poller activité, soit 28 pollers A2A ;
- `/actuator/health/readiness` : statut global `UP`, aucune carte, file ou dépendance bloquante ;
- projection A2A neuve : `tasks=0`, `pending_notifications=0`, `pending_cancellations=0` ;
- admissions maintenues fermées pendant tous les contrôles : révision `46`, motif `temporal_cutover`.

## Remédiations validées

Le redémarrage initial a révélé 70 notifications de campagnes de qualification anciennes. La boucle périodique
de reprise les a bien renvoyées et a exposé un défaut réel de typage JDBC : PostgreSQL refusait le texte sérialisé
destiné à la colonne `jsonb`. Le correctif impose `CAST(? AS jsonb)` ; sa régression ciblée et les 551 tests de
l'orchestrateur passent sans échec. Après correction, trois notifications ont été acquittées et les 67 restantes
ont été identifiées comme appartenant à des workflows historiques déjà clos ou épinglés sur deux Build IDs
retirés.

Les deux seuls workflows encore marqués actifs (`AF-0133`, `AF-0134`) ont d'abord reçu une annulation métier,
puis ont été terminés explicitement car aucun worker de leur Build ID retiré ne pouvait la consommer. Une seconde
requête Temporal a confirmé zéro workflow actif. Avant remise à zéro de la seule projection A2A locale, un dump
de récupération a été produit avec le SHA-256
`b90c31d876e7d33fb90d8945c2b4fd2c2bc19429684560cfc48e4114610b1bb8`. Temporal, Evidence, les dépôts et les
secrets n'ont pas été supprimés.

## Ouverture et vérification

Commande appliquée :

```shell
./scripts/set-admissions.sh open
```

Résultat durable :

```text
admissions_open=t
reason=normal_operation
revision=47
updated_at=2026-09-07 03:38:19.614738+00
```

L'API `/api/capabilities` confirme `admissionsOpen=true`, `admissionReason=normal_operation` et
`admissionRevision=47`. La fenêtre de stabilisation A2A-188 doit désormais surveiller cette révision et refermer
les admissions au premier dépassement de seuil.
