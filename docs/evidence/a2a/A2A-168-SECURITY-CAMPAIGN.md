# A2A-168 — Campagne de sécurité adverse

Date : 2026-09-06

## Résultat

- **Statut : réussi**
- **Commande reproductible :** `make test-a2a-security`
- **Périmètre :** 18 tests runtime + 18 tests orchestrateur, 0 échec et 0 erreur.

## Couverture

| Menace | Contrôle vérifié |
|---|---|
| Fuzzing JSON-RPC et payloads malformés | corpus vide, tronqué, types invalides, racine non objet, méthode injectée et imbrication profonde ; réponse d'erreur sans exécution |
| Cartes forgées | signature JCS/JWS, clé de confiance, expiration, rôle, endpoint et taille de carte vérifiés avant invocation |
| Jetons croisés | liaison stricte `client_id` / rôle / tenant et classement en refus d'authentification |
| Cross-tenant | lecture, liste, curseur et annulation ne révèlent aucune tâche étrangère |
| SSRF | allow-list exacte, HTTPS, DNS pinning et refus loopback, link-local, metadata, RFC1918 et IPv6 ULA |
| Rejeu | même `messageId` et même payload dédupliqués ; collision avec un payload différent refusée |
| Redirections | redirections de cartes refusées et historique de redirection HTTP d'invocation rejeté |
| Dépassement de taille | requêtes, réponses et cartes au-delà de 1 Mio refusées avant désérialisation métier |
| Injection | retours ligne W3C refusés et secrets détectés récursivement dans objets et tableaux JSON |

## Durcissements livrés

- parcours récursif des tableaux lors de la détection de credentials ;
- classement des incohérences d'identité et délégations interdites dans la catégorie `AUTH` ;
- blocage explicite des réseaux privés IPv4 et IPv6 ULA ;
- rejet des réponses A2A surdimensionnées et de toute chaîne de redirection suivie.
