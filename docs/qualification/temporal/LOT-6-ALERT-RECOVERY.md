# Lot 6 — déclenchement et récupération des alertes Temporal

`scripts/test-signoz-alert-fixtures.sh` injecte des métriques OTLP déterministes qui rendent vraies les quinze règles
applicatives et Temporal. Il vérifie chaque expression PromQL dans SigNoz, puis injecte sur les mêmes séries un état
sain positionné après la plus longue fenêtre glissante : compteurs stables, pollers présents, backlog/saturation et
retard à zéro, continue-as-new confirmé.

La campagne n'attend pas artificiellement quinze minutes : elle déplace les timestamps de qualification tout en
évaluant les expressions non modifiées. Une récupération n'est acquise que si aucune expression ne renvoie encore de
série. Les six alertes portant sur l'observabilité elle-même restent couvertes par `scripts/test-otel-resilience.sh`,
car leur panne réelle implique l'arrêt d'un composant et non une simple fixture métrique.
