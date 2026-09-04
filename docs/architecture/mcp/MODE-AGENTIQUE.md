# Mode agentique Planner/Reviewer

> Portée : cette note concerne la campagne MCP-180 sur l'appel autonome d'outils par Planner et Reviewer. Son seuil
> historique de 20 cas ne remplace pas la campagne de qualification hiérarchique de 36 cas décrite dans la
> documentation multi-agent.

« Le mode agentique reste désactivé » signifie que les agents IA Planner et Reviewer ne peuvent pas, en production, choisir eux-mêmes quels outils MCP appeler ni enchaîner plusieurs appels selon leurs résultats.

Le fonctionnement actuel reste celui-ci :

1. L’orchestrateur décide des étapes.
2. Il appelle les serveurs MCP de manière déterministe.
3. Il construit le contexte.
4. Il transmet ce contexte au modèle.
5. Le modèle produit son plan ou sa revue.

Le mode agentique testé aurait plutôt fonctionné ainsi :

```text
Planner
  → choisit context.list_tree
  → analyse le résultat
  → choisit context.read_file
  → analyse le fichier
  → choisit éventuellement context.search_code
  → produit finalement son plan
```

Il est désactivé parce que MCP-180 montre que cette variante n’est pas encore assez performante :

- le taux de succès des tests passe de 10 % à 0 % ;
- la consommation moyenne augmente de 28,19 % ;
- aucun cas candidat n’atteint l’étape d’approbation ;
- la télémétrie des coûts n’est pas encore exploitable.

La règle MCP-181 est « fail-closed » : Planner et Reviewer ne peuvent être activés en mode agentique que si une campagne d’au moins 20 cas obtient le verdict `QUALIFIED`, sans régression de sécurité. La campagne ayant obtenu `REJECTED`, la configuration demeure :

```env
AI_FACTORY_AGENT_TOOL_ROLES=
AI_FACTORY_AGENT_TOOL_QUALIFICATION=INCOMPLETE
AI_FACTORY_AGENT_TOOL_SECURITY_PASSED=false
AI_FACTORY_AGENT_TOOL_EVALUATION_ENABLED=false
```

Cela ne signifie donc pas que MCP est désactivé. Les serveurs MCP de contexte, sandbox, assurance, preuves et
livraison restent utilisés ou disponibles pour l'orchestrateur. Seule l'autonomie du modèle pour sélectionner et
enchaîner les outils MCP est coupée.
