# A2A-188 — Fenêtre de stabilisation

Date : 2026-09-07  
Verdict : `PASSED`  
Révision d'admission surveillée : `49`

## Périmètre surveillé

Le moniteur `scripts/monitor-a2a-cutover.sh`, exposé par `make monitor-a2a-cutover`, contrôle de manière
fail-closed :

- l'ouverture et l'immutabilité de la révision d'admission ;
- la readiness globale, les quatorze Agent Cards, les 28 pollers A2A et les sept workers de coordination ;
- les digests de l'orchestrateur et des quatorze runtimes, leur santé, leurs redémarrages et les OOM ;
- les plafonds de tâches actives par rôle/tenant et de backlog par rôle ;
- les notifications et annulations non acquittées ainsi que l'inbox orchestrateur en retard ;
- les identités de message dupliquées, divergences, collisions d'idempotence et transitions hors ordre ;
- les échecs A2A et métier apparus pendant la fenêtre ;
- les tokens et coûts enregistrés pendant la fenêtre ;
- la saturation mémoire à 95 %, la disponibilité des métriques, dashboards et alertes SigNoz.

Toute violation appelle `set-admissions.sh close` avant de retourner une erreur.

## Exécution

```shell
A2A_HYPERCARE_SECONDS=300 \
A2A_HYPERCARE_INTERVAL_SECONDS=15 \
EXPECTED_ADMISSION_REVISION=49 \
EXPECTED_A2A_RUNTIME_IMAGE_ID=sha256:a9f616dd78e43300bfc7f92d992d9aae6a322711590a783401667c3311543ba8 \
EXPECTED_ORCHESTRATOR_IMAGE_ID=sha256:a58d1600269abd9d9b0c6276129a12f29d9187d4dd0b3fedc59faaf1d4fd2ff0 \
make monitor-a2a-cutover
```

Résultat :

```text
A2A stabilization passed: duration_seconds=378 samples=12 admission_revision=49
runtime_image=sha256:a9f616dd78e43300bfc7f92d992d9aae6a322711590a783401667c3311543ba8
orchestrator_image=sha256:a58d1600269abd9d9b0c6276129a12f29d9187d4dd0b3fedc59faaf1d4fd2ff0
log_violations=0 memory_breaches=0
```

Les douze échantillons ont rapporté `active=0`, `backlog=0`, `stale_notifications=0`, `stale_inbox=0` et
`usage_tokens_cost_micros=0:0`. Cette fenêtre était volontairement inactive : la charge fonctionnelle,
l'annulation et la reprise sont couvertes par A2A-186, et la saturation bornée par A2A-169. Le zéro de coût est
donc une mesure réelle de cette fenêtre, pas une substitution à une donnée absente.

## Validation fail-closed

La première invocation du moniteur a rencontré l'absence des tableaux associatifs dans Bash 3.2 sur macOS. Elle
a immédiatement refermé les admissions à la révision 48, démontrant la barrière fail-closed. Le moniteur a ensuite
été rendu compatible avec Bash 3.2, la flotte et les pollers ont été revalidés, l'outbox est restée vide, puis les
admissions ont été rouvertes à la révision 49 avant l'exécution complète ci-dessus.

## État final

```text
tasks=0
active=0
backlog=0
pending_notifications=0
pending_cancellations=0
admissions_open=true
admission_reason=normal_operation
admission_revision=49
```

SigNoz confirme `866` métriques, `8` dashboards, `30` alertes et la présence des familles A2A de disponibilité,
durée, retry, notification, divergence, backlog, tâches actives, collisions et doublons. Les rétentions restent
fixées à `720h/360h/15d`.
