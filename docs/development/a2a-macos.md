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

## Réglages et entretien

- Utiliser le moteur Apple Virtualization Framework et VirtioFS sur Apple Silicon.
- Conserver le cache Maven et les volumes de données entre deux sessions ; `make down` ne les supprime pas.
- Réserver `make clean` aux remises à zéro complètes : il supprime tous les volumes de la fabrique.
- Pour la seule projection A2A, employer la garde explicite documentée par `make help`.
- Augmenter d'abord la mémoire Docker Desktop si des JVM deviennent `unhealthy` ou sont tuées avec le code 137.
