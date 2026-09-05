# ADR-OBS-001 — OpenTelemetry et SigNoz sans Prometheus/Grafana

- Statut : accepté
- Date : 2026-09-05
- Portée : observabilité locale macOS et cible partagée GKE

## Contexte

La pile locale utilise Prometheus 3.5 et Grafana 12.1. Prometheus collecte réellement l'orchestrateur et
Temporal ; les deux cibles MCP configurées répondent 404 et trois autres MCP ne sont pas déclarés. Grafana
provisionne six dashboards et Prometheus charge neuf alertes. Aucun export OTLP n'est opérationnel.

La décision produit impose de remplacer immédiatement Prometheus et Grafana plutôt que de les conserver pendant
l'introduction d'OpenTelemetry. OpenTelemetry ne stockant pas les signaux et ne fournissant pas d'interface, un
backend OTLP est nécessaire.

La baseline complète et exploitable hors ligne est conservée dans
`docs/evidence/observability/prometheus-grafana-baseline-2026-09-05.json`.

## Décision

1. Micrometer Observation reste l'API applicative ; OpenTelemetry devient le SDK, le modèle de données et le
   protocole d'export.
2. Les six applications Spring Boot exportent métriques et traces en OTLP vers un OpenTelemetry Collector.
3. Les logs applicatifs restent disponibles sur stdout et sont également exportés en OTLP après redaction ; le
   chemin stdout reste la voie de secours indépendante.
4. Le Collector assure limitation mémoire, batch, filtrage, redaction, retry borné et routage. Une panne du
   Collector ne bloque jamais une tâche métier.
5. SigNoz self-hosted est le backend local unique pour stockage, requêtes, dashboards et alertes. Son interface
   utilise le port hôte 3301 afin de ne pas entrer en conflit avec l'application sur 8080.
6. Les métriques Temporal sont collectées par le receiver Prometheus du Collector tant que Temporal ne fournit
   pas un export OTLP qualifié. Aucun serveur Prometheus n'est déployé pour ce receiver.
7. Les métriques de conteneurs Docker ne justifient pas le remontage de la socket. La cible locale se limite aux
   métriques applicatives, JVM, Collector et services accessibles sans privilège. GKE utilise les receivers et
   identités Kubernetes approuvés par la plateforme.
8. GKE exporte via des gateways Collector vers Google Cloud Monitoring, Trace et Logging avec Workload Identity.
9. La bascule locale est atomique : le même commit ajoute OTel/Collector/SigNoz et retire Prometheus/Grafana.
   Aucune double collecte ou configuration legacy n'est maintenue dans la branche active.
10. Le rollback redéploie intégralement le commit précédent après arrêt de Collector/SigNoz. Il ne mélange jamais
    les deux chaînes.

## Contrat et objectifs de service

| Sujet | Local Compose | GKE partagé |
|---|---:|---:|
| délai d'apparition p95 | 30 s | 60 s |
| perte en régime nominal | 0 % | 0 % |
| perte transitoire maximale mesurée | 0,1 % | 0,1 % |
| disponibilité du chemin métier si OTel est indisponible | 100 % | 100 % |
| rétention métriques initiale | 30 jours | politique plateforme approuvée |
| rétention traces et logs initiale | 15 jours (défaut SigNoz borné) | politique plateforme approuvée |

Les budgets locaux sont 4 Go de mémoire Docker au minimum pour SigNoz seul, puis une mesure de la pile complète
avant qualification. Les limites finales CPU, mémoire et disque sont inscrites dans Compose après mesure. En GKE,
les quotas et budgets de coût doivent être approuvés avant déploiement.

## Gouvernance

- L'équipe application possède les instruments, attributs métier et tests de contrat.
- L'équipe plateforme possède Collector, stockage, rétention, sauvegarde et export GKE.
- L'exploitation possède dashboards, alertes, notifications, SLO et runbooks.
- La sécurité approuve redaction, accès, rétention et capture éventuelle de contenu.
- Les secrets, prompts, réponses, code, patchs et preuves ne sont jamais capturés par défaut.
- Les identifiants de tâche et d'exécution sont réservés aux traces/logs, jamais aux dimensions métriques.

## Dashboards et alertes

Les six dashboards Grafana sont portés vers SigNoz : global, Supervisor, agents, MCP, sandbox et Temporal. Un
septième dashboard surveille le Collector. Les neuf alertes historiques sont recréées avec leurs seuils, délais,
severities, notifications et liens de runbook, puis déclenchées avec des fixtures OTLP. Six règles supplémentaires
surveillent refus, export, files, absence d'ingestion, mémoire et redémarrage du Collector.

## Sauvegarde et retour arrière

Les JSON Grafana et règles Prometheus versionnés constituent la spécification historique. Le volume Grafana est
détaché lors de la bascule, conservé pendant la fenêtre de retour arrière puis supprimé explicitement. Prometheus
n'ayant pas de volume déclaré, aucune série durable supplémentaire n'est attendue ; la fixture datée conserve les
noms, cibles, règles, expressions, cardinalités principales et valeurs de référence nécessaires.

## Conséquences

- La pile locale gagne traces distribuées, corrélation et alertes dans une interface unique.
- SigNoz et ClickHouse consomment nettement plus de ressources que les deux conteneurs remplacés.
- Les requêtes PromQL et JSON Grafana doivent être réécrits, pas simplement copiés.
- Les cinq MCP acquièrent une couverture réelle au lieu de reproduire les cibles 404 historiques.
- Le receiver de compatibilité Temporal reste une dette explicite et testée.
- L'absence de coexistence réduit la durée de migration mais rend les fixtures et le rollback de version critiques.

## Alternatives écartées

- **Collector seul** : aucune persistance, interface ou alerte durable.
- **Conserver Grafana avec Tempo** : contraire à la décision de remplacement complet.
- **Prometheus remote write derrière le Collector** : conserve Prometheus comme backend ou protocole principal.
- **Double collecte temporaire** : contraire à la bascule immédiate demandée et susceptible de doubler les séries.
- **Agent Docker avec socket** : réintroduit l'accès au daemon retiré par la migration sandbox.

## Références qualifiées

Références officielles consultées le 5 septembre 2026 :

- [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/configuration/) 0.160.0 : configuration,
  processors et export OTLP ;
- [Spring Boot observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html) 4.1.1 :
  starter OpenTelemetry, export OTLP et propagation de contexte ;
- [SigNoz self-hosted](https://signoz.io/docs/install/docker/) 0.135.0 / ingester 0.144.6 : installation,
  API dashboards/alertes et rétention ;
- [Google Cloud OpenTelemetry](https://cloud.google.com/stackdriver/docs/instrumentation/opentelemetry) : export
  Collector `googlecloud` et Workload Identity GKE.

Les liens maintenus sont ceux de la section 19 du plan de migration. Toute montée de ces versions exige la
revalidation des schémas Collector, conventions sémantiques, requêtes et tests de confidentialité.
