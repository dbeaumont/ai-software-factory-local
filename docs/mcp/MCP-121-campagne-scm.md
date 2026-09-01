# MCP-121 — Campagne shadow et livraison SCM

Date d'exécution : 2026-09-02  
Interlocuteur et approbateur : David Beaumont  
Dépôt de démonstration : `customer-api`

## Résultat

La résolution de `main` par `scm.resolve_revision` a retourné le SHA immuable
`0e7be5c505da26d063d1e9a2ddbfd046b27df9e5`, identique au HEAD du workspace de
qualification. Le serveur a ensuite créé, exclusivement par
`scm.create_draft_pull_request`, la branche `ai-factory/003ec671-mcp121`, le
commit `a36defcdf00dc92d045a95a89224e28072587da0` et la PR Gitea n°1.

La PR est ouverte en brouillon à l'adresse
`http://localhost:3000/aiadmin/customer-api/pulls/1`. Un second appel avec une
nouvelle clé d'idempotence a retrouvé la même branche et la même PR, sans
écriture supplémentaire.

## Contrôles exercés

- registre serveur et branche de base allow-listés ;
- preuve `APPROVED` HMAC liée à la tâche, la tentative, la source et au digest du patch ;
- six digests de preuves vérifiés avant toute écriture ;
- workspace partagé monté en lecture seule ; une copie éphémère, sans lien symbolique, est créée dans l'état privé du serveur pour le commit ;
- remote et refspec reconstruits côté serveur, sans force push ;
- artefacts de fabrique exclus du commit ;
- secret Gitea et clé d'attestation lus depuis le secret Docker `.vault`, jamais depuis les arguments MCP ;
- événements d'audit avant/après écriture et résultat idempotent persisté.

## Écarts détectés et corrigés pendant la campagne

1. Collision de noms de beans Spring au démarrage : provider renommé.
2. `WebClient.Builder` absent de l'auto-configuration : builder explicite ajouté.
3. Propriétaires Unix différents entre orchestrateur et serveur SCM : accès source rendu strictement read-only et staging privé ajouté ; `safe.directory` est borné au workspace validé.
4. Gitea 1.23 ignore le seul champ JSON `draft` : le titre est préfixé `WIP:` et la réponse est désormais refusée si Gitea ne confirme pas `draft=true`.

La tentative de lancer une nouvelle génération cloud a été bloquée par le
contrôle d'autorisation de contenu. La qualification de livraison a donc utilisé
un patch documentaire déterministe local, sans transmission de code à un tiers.
