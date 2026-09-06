# A2A-160 — qualification unitaire A2A

Date : 2026-09-05

La barrière de construction JDK 25 de l'image orchestrateur exécute toute classe `A2a*Test`, ainsi que
les tests des trois composants dont le nom décrit la responsabilité plutôt que le protocole : registre
allow-listé, cache d'Agent Cards et réception des notifications Temporal.

La sélection couvre les mappings entrée/sortie, schémas et limites, états Temporal/A2A, erreurs et ACL,
idempotence, cache, signatures, corrélation W3C, secrets et absence de contenu sensible dans les
dimensions d'observabilité. Les tests de déterminisme Temporal et de navigation API restent dans la même
barrière afin qu'une image ne puisse pas être publiée avec une régression de frontière.

Commande reproductible :

```text
docker compose --env-file .env -f infrastructure/compose.yaml build orchestrator
```

Résultat obtenu : **82 tests, 0 échec, 0 erreur, 0 ignoré** ; le paquet Spring Boot est ensuite produit
sous JDK 25. La première exécution a détecté cinq lectures de fixtures dépendantes du répertoire courant ;
elles consomment désormais les chemins injectés explicitement dans la construction.
