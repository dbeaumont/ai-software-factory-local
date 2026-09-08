# Tâches

## En cours:
- [x] Retrait socket docker
- [x] Mise en place de OpenTelemetry à la place de Prométheus + grafana?
- [x] Activer Temporal
- [x] Protocole A2A entre les agents
- [ ] Vérifier ce qui est présent mais pas encore activé (les migrations en cours de promotion)
- [ ] modes : PIPELINE, HIERARCHICAL_SHADOW, HIERARCHICAL_CANARY : virer et garder uniquement HIERARCHICAL_ACTIVE ?
- [ ] Distinguer les responsabilités : control plane / data plane / execution plane ?
- [ ] Regénérer/mettre à jour la doc
- [ ] Nettoyer makefile des tâches inutiles (tests via des scripts temporal qui n'existent plus par exemple)
- [ ] Resources : fusionner agents / multiagents ?
- [ ] Resources : observability à conserver ?
- [ ] Répertoire /tmp/pdfs : répertoire à conserver ?

## Next:
- Refondre l'architecture : reprendre les écritures MCP dans leurs agents respectifs (et plus dans l'orchestrateur)
- [ ] Mise en place d'écrans de supervision :
  - fonctionnelle : 
    - écran listant les tickets en cours et passées
    - pour chaque ticket
      - ajouter des opérations de maintenance (logs, stop, retry, continue, restart, ...)
      - ajouter un graphe de suivi du workflow : 
        - indiquer les tâches déjà effectuées et celles qui n'ont pas été effectuées 
        - affichage des logs, evidences et décisions associées à chacune des tâches
  - technique : 
    - état de santé
    - performances
  - finops
    - gestion des quotas : affichage, heatmap, modification en live
    - état finops
      - gestion des budgets, des tokens consommés
- [ ] Mise en place SSO OIDC

## A étudier:
- [ ] Gardrails : Voir pour avoir un contexte d'architecture sous forme de PDF, DOCX, etc
  - Voir pour prendre en compte todo/CLAUDE.md
  - Voir pour brancher un MCP Figma ?
- [ ] Voir pour reprise des projets existants
  - Proposition de refonte dans les résultats proposés par l'usine
- [ ] Voir pour boucle agentique avec amélioration continue et automatique
- [ ] Chemins de générations différents en fonction de critères ?
- [ ] Prévoir des workflows différents en fonction du sujet :
  - Dev simple
  - Dev complexe
  - Reprise d'un codebase existant pour le mettre à niveau (failles, obsolescences)
- Exposer l'usine derrière un serveur MCP 
  - Cas d'usage : pour que Figma puisse déclencher des livraison d'ihm (que l'usine irait capter via le MCP Figma de Jesson)
