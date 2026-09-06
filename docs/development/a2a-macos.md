# Exécuter la flotte A2A sur macOS avec Docker Desktop

## Dimensionnement

La flotte conserve un processus et une identité par rôle : aucun regroupement de JVM n'est autorisé, car il
affaiblirait l'isolation des permissions, certificats, task queues et réseaux MCP. Les quatorze services utilisent
une seule image locale, donc les couches Java et applicatives ne sont stockées qu'une fois.

Configuration Docker Desktop recommandée pour la fabrique complète :

| Ressource | Minimum A2A isolé | Fabrique complète recommandée |
| --- | ---: | ---: |
| CPU | 8 vCPU | 10 à 12 vCPU |
| Mémoire | 10 Gio | 20 à 24 Gio |
| Disque disponible | 12 Gio | 30 Gio |
| Swap Docker Desktop | 2 Gio | 4 Gio |

Chaque runtime est borné à `0.75` CPU et `768 Mio`, avec réservation de `0.10` CPU et `384 Mio`. PostgreSQL A2A
est borné à 1 CPU et 1 Gio. La JVM adapte son heap à la limite cgroup avec `MaxRAMPercentage=70`.

Mesure de référence sur Apple Silicon, le 6 septembre 2026 : après le smoke complet, chaque runtime inactif
consomme 237 à 257 Mio et moins de 1 % CPU. Les quatorze JVM et PostgreSQL totalisent environ 3,5 Gio ; la base
consomme alors 124 Mio. L'image partagée du runtime représente environ 201 Mo et l'image PostgreSQL 108 Mo, hors
cache Maven de construction et données de tâches. Le démarrage à image chaude et la validation des quatorze cartes
ont terminé en moins d'une minute.

## Démarrage

Initialiser une seule fois les fichiers locaux hors Git :

```shell
make init
```

Cette commande initialise notamment `.env`, la PKI et les secrets A2A. Elle reconstruit aussi atomiquement le
registre public d'empreintes de cartes à partir des certificats de rôle déjà présents, sans faire tourner leurs
clés privées. Avant chaque démarrage, valider le rendu Compose, l'isolation réseau et les montages du runtime :

```shell
make a2a-config
```

Démarrer un rôle pour le développement ciblé :

```shell
make a2a-up-role A2A_ROLE=developer
```

Démarrer et tester les quatorze rôles :

```shell
make a2a-up-full
```

Le premier build peut prendre plusieurs minutes. À image déjà construite, le délai attendu est inférieur à deux
minutes ; la commande attend chaque healthcheck avant le smoke test. Utiliser `make a2a-status` et
`make a2a-logs` si ce délai est dépassé.

## Diagnostic courant

Afficher l'état de PostgreSQL A2A et des runtimes sélectionnés :

```shell
make a2a-status
```

Limiter l'inspection à un ou plusieurs rôles, sans changer leur isolation :

```shell
A2A_ROLES="developer test-agent" make a2a-status
```

Valider la santé des conteneurs, le mTLS et toutes les cartes signées :

```shell
make a2a-smoke
make a2a-cards
```

Suivre les 200 dernières lignes de logs, ou ajuster la profondeur :

```shell
make a2a-logs
A2A_LOG_TAIL=500 A2A_ROLES=developer make a2a-logs
```

Une absence de conteneur, un healthcheck `unhealthy`, une carte non signée ou un rôle incohérent fait échouer ces
commandes. Ne pas contourner l'échec avec `--insecure` depuis l'hôte : les scripts réalisent la lecture dans le
réseau privé avec l'identité mTLS prévue.

## Cartes et tâches

`make a2a-cards` lit `/.well-known/agent-card.json` pour chaque rôle et contrôle au minimum le rôle déclaré et la
présence d'une signature. La validation complète du registre fermé, de la chaîne de confiance et des compétences
est effectuée par l'orchestrateur lors de la découverte.

Temporal reste l'autorité de l'exécution. Ouvrir son interface avec `make temporal-ui`, puis rechercher le
`workflow_id`, le `task_id` ou le `delegation_id`. La projection PostgreSQL permet un diagnostic corrélé en lecture
seule :

```shell
docker compose --env-file .env -f infrastructure/compose.yaml exec -T a2a-task-db \
  psql -U ai_factory_a2a -d ai_factory_a2a -c \
  "SELECT task_id, attempt_id, delegation_id, agent_role, a2a_task_id, workflow_id, updated_at FROM a2a_task_associations ORDER BY updated_at DESC LIMIT 20"
```

Pour les callbacks, inspecter sans modifier les transitions non signalées :

```shell
docker compose --env-file .env -f infrastructure/compose.yaml exec -T a2a-task-db \
  psql -U ai_factory_a2a -d ai_factory_a2a -c \
  "SELECT agent_role, a2a_task_id, task_state, signal_status, occurred_at FROM a2a_notification_inbox ORDER BY occurred_at DESC LIMIT 20"
```

Ne jamais corriger manuellement cette projection : réconcilier par A2A/Temporal ou appliquer le runbook
correspondant.

## Traces et métriques

SigNoz est publié uniquement sur `http://localhost:3301`. Le dashboard **AI Factory A2A Fleet** présente tâches
actives, backlog, latences, transitions, refus d'authentification, validations de cartes, retries, timeouts,
notifications et divergences Temporal/A2A.

Pour une investigation :

1. filtrer les métriques par `agent_role`, `agent_skill`, `task_state`, `rpc_operation` et `result` ;
2. ouvrir une trace du même créneau puis rechercher `ai_factory.task.id`, `ai_factory.attempt.id`,
   `a2a.task.id`, `a2a.message.id` ou `temporal.workflow.id` ;
3. ouvrir les logs corrélés par `trace_id` ;
4. confirmer l'état durable dans Temporal UI avant toute relance.

Les identifiants de tâche sont réservés aux traces et logs : ils ne doivent pas être ajoutés comme dimensions de
métriques afin de préserver une cardinalité bornée.

## Certificats et secrets

Créer ou vérifier séparément les artefacts locaux :

```shell
make a2a-pki
make a2a-secrets
```

Ils sont écrits sous `.local/a2a-pki` et `.local/a2a-secrets`, exclus de Git et montés en lecture seule. Ne jamais
les copier dans `.env`, un ticket, un log ou une preuve. La rotation locale coordonnée s'effectue avec :

```shell
make a2a-pki-rotate
make a2a-up-full
make a2a-cards
```

La rotation conserve une sauvegarde datée, régénère la PKI, vérifie CRL et permissions, puis exige le redémarrage
des workloads pour charger les nouveaux fichiers. En cas d'échec, restaurer la sauvegarde créée par le script avant
de redémarrer ; ne pas mélanger certificats et clés issus de générations différentes.

## Reset contrôlé

Le reset A2A arrête uniquement PostgreSQL A2A et les quatorze runtimes, puis supprime exclusivement le volume de
projection identifié par les labels Compose. Il est irréversible et refuse de s'exécuter sans la garde exacte :

```shell
CONFIRM_A2A_RESET=DELETE_A2A_LOCAL_STATE make a2a-reset-state
```

La commande ne supprime ni Temporal, ni Evidence, ni les dépôts, ni les secrets/PKI locaux. Après un reset,
redémarrer avec `make a2a-up-full` et vérifier `make a2a-smoke`.

## Réglages et entretien

- Utiliser le moteur Apple Virtualization Framework et VirtioFS sur Apple Silicon.
- Conserver le cache Maven et les volumes de données entre deux sessions ; `make down` ne les supprime pas.
- Réserver `make clean` aux remises à zéro complètes : il supprime tous les volumes de la fabrique.
- Pour la seule projection A2A, employer `a2a-reset-state` avec sa garde explicite.
- Augmenter d'abord la mémoire Docker Desktop si des JVM deviennent `unhealthy` ou sont tuées avec le code 137.
