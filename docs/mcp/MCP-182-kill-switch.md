# MCP-182 — Coupe-circuits opérationnels

> État courant : le mécanisme est implémenté dans l'application, mais le fichier n'est ni monté ni configuré par
> `infrastructure/compose.yaml`. Sans cette intégration, l'exploitation locale doit arrêter/isoler le service ou
> bloquer les admissions ; la procédure cible suppose un montage en lecture seule.

L'orchestrateur relit le fichier désigné par `AI_FACTORY_MCP_KILL_SWITCH_FILE` avant chaque appel. Le format `properties` accepte :

```properties
revision=incident-2026-09-02-01
global.disabled=false
servers.disabled=sandbox-execution-mcp
tools.disabled=context.search_code
roles.disabled=planner
modes.disabled=HIERARCHICAL_CANARY
role-modes.disabled=developer@HIERARCHICAL_ACTIVE,security-agent@HIERARCHICAL_CANARY
```

L'absence de fichier conserve le comportement configuré. Un fichier présent mais illisible ou sans `revision` coupe tous les appels. Il n'existe volontairement aucune API applicative d'écriture : en cible, le fichier/configuration est monté en lecture seule et sa modification est réservée au compte d'exploitation. Chaque changement est pris en compte sans redéploiement.
