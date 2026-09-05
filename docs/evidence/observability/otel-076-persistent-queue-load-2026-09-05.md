# OTEL-076 — file persistante et charge OTLP

## Changement

Le Collector local utilise désormais `file_storage/queue` dans le volume dédié `otel-collector-queue`. Les trois
files d'export métriques, traces et logs sont bornées à 2 048 éléments, utilisent quatre consommateurs, réessaient
pendant 60 secondes et sont compactées au démarrage/rebond. L'initialiseur Compose attribue le volume à l'UID/GID
non-root `10001:10001`.

Le backend OTLP ne propose pas de dead-letter queue dans cette configuration. Les éléments expirés restent
comptés/alertés ; aucun payload rejeté n'est copié dans un stockage secondaire.

## Qualification du 5 septembre 2026

`./scripts/test-otel-load.sh` a produit les résultats suivants :

- charge nominale : 6 000 enregistrements métriques/traces/logs acceptés en 0,755 s, soit 7 951,1/s ;
- campagne avec redémarrage de `repository-context-mcp` : 24 000 enregistrements acceptés en 1,551 s, soit
  15 471,1/s, puis retour du service à l'état `healthy` ;
- présence de `ai_factory_otel_load_probe` confirmée dans les métadonnées ClickHouse ;
- arrêt de l'ingester, puis 1 200 enregistrements supplémentaires acceptés en 0,248 s ;
- présence de données dans les trois fichiers de file persistante ;
- redémarrage du Collector pendant la panne, puis redémarrage de l'ingester et drainage réussi ;
- Collector sain à 73,02 Mio sur la limite Compose de 512 Mio après la campagne ;
- ingestion, sept dashboards, 15 alertes et rétentions revalidés après reprise.

Une seconde exécution instrumentée a ensuite vérifié les volumes exacts sur les trois tables distribuées :

- 10 400 points métriques, 10 400 spans et 10 400 logs acceptés puis livrés ;
- zéro perte, zéro duplication et 10 400 `trace_id` distincts ;
- une seule série métrique pour la sonde, conformément au budget de cardinalité ;
- drainage complet et visibilité des trois signaux en 32 s, panne et redémarrage compris ;
- Collector à 0,19 % CPU, 52,17 Mio sur 512 Mio et file persistante à 768 Kio à la fin du test ;
- coût cloud direct de la campagne locale : 0 USD (le coût de la machine macOS est hors périmètre).

Après stabilisation, une mesure ponctuelle de l'ensemble du backend donne : Collector 0,42 % / 66,81 Mio,
SigNoz 1,20 % / 73,69 Mio, ingester 4,14 % / 136,3 Mio, ClickHouse 26,06 % / 2,601 Gio et PostgreSQL
1,24 % / 22,49 Mio. Les volumes occupent 1 560 Kio pour la file Collector, 1 035 664 Kio pour ClickHouse et
67 064 Kio pour PostgreSQL. Ces valeurs sont des bornes de qualification locale, pas un dimensionnement GKE.

La validation syntaxique avec l'image Collector épinglée et `docker compose config --quiet` est également passée.

## Limites

`./scripts/test-otel-saturation.sh` lance par ailleurs un Collector isolé, limité à une file de huit éléments, face
à un backend retardant chaque réponse de deux secondes. Sur 1 800 enregistrements acceptés par le receiver, 24 sont
livrés et 1 776 sont refusés/perdus après saturation. Le signal de file pleine est présent, le Collector reste actif
et les deux conteneurs jetables sont supprimés. Ces valeurs caractérisent volontairement la configuration de test
sous-dimensionnée, pas la file de production à 2 048 éléments.

La campagne mesure un débit court, la persistance après panne, la livraison exacte et le refus borné en saturation.
Elle ne démontre pas encore le SLO sur une fenêtre longue, les délais par percentile de la pile nominale, ni le coût
GKE.
