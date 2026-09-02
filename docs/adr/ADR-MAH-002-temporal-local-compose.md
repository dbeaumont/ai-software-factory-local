# ADR-MAH-002 — Topologie Temporal locale

- Statut : accepté pour le prototype de migration
- Date : 2026-09-02
- Portée : développement local, tests d'intégration et démonstration

## Contexte

La migration doit pouvoir qualifier la durabilité, les Child Workflows, les Signals et les reprises sans dépendre
d'un service externe. Le dépôt utilise déjà Docker Compose pour l'orchestrateur et les services techniques.

## Décision

1. Temporal est exécuté localement dans Docker Compose.
2. Le service Temporal utilise une base PostgreSQL dédiée ; il ne partage pas les bases de Gitea, SonarQube ou la
   future projection métier de l'orchestrateur.
3. Temporal Server et ses workers communiquent sur un réseau privé `workflow-internal`.
4. Le port gRPC Temporal n'est pas exposé publiquement par le reverse proxy.
5. Temporal UI est un service de diagnostic développeur, publié uniquement sur une adresse loopback configurable.
6. Le démarrage de l'orchestrateur en mode `PIPELINE` ne dépend pas de Temporal.
7. Les modes `HIERARCHICAL_*` échouent au démarrage ou à l'admission si Temporal n'est pas prêt.
8. Les images, schémas de base et paramètres de rétention sont épinglés dans la configuration du prototype.

## Services locaux prévus

| Service | Rôle | Exposition |
|---|---|---|
| `temporal-db` | Persistance interne du moteur | Réseau privé uniquement |
| `temporal` | Frontend et services Temporal | Réseau `workflow-internal` |
| `temporal-ui` | Inspection des workflows | Loopback, diagnostic uniquement |
| `orchestrator` | Client et worker Java | Réseaux applicatif, MCP et workflow |

## Données et cycle de vie

- un volume nommé conserve la base Temporal entre redémarrages ;
- les données Temporal ne remplacent pas les preuves immuables d'Evidence MCP ;
- `make down` ne détruit pas les données par défaut ;
- une commande explicite et documentée sera nécessaire pour réinitialiser l'environnement de développement ;
- les tests automatisés pourront utiliser une instance éphémère ou le serveur de test officiel du SDK.

## Conséquences

- le profil local consomme des ressources supplémentaires ;
- l'UI Temporal ne devient pas une interface opérateur de l'usine ;
- les tests de parité du mode `PIPELINE` restent exécutables sans démarrer Temporal ;
- l'ajout de Temporal au Compose sera réalisé après introduction du port `WorkflowCoordinator`.

## Alternatives écartées

- **Temporal Cloud pour le développement local** : dépendance réseau et coût inutiles pour la boucle développeur.
- **Une instance Temporal partagée dès le prototype** : isolation et reproductibilité moindres.
- **Réutiliser une base PostgreSQL applicative existante** : cycles de vie, sauvegardes et privilèges seraient couplés.
