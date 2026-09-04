# Compatibilité des contrats multi-agents

La politique
[`contract-compatibility-policy-v1.yaml`](../../../../resources/multiagents/policies/contract-compatibility-policy-v1.yaml)
retient une compatibilité N/N-1 et un chevauchement minimal de 28 jours. Les lecteurs sont déployés avant les
producteurs ; ils doivent accepter la version courante et la précédente pendant toute la fenêtre.

Dans une version majeure, seuls les ajouts optionnels réellement compris par les lecteurs sont additifs. Ajouter
un champ obligatoire, supprimer ou renommer un champ, changer un type ou un sens, durcir une contrainte ou
retirer une valeur d'enum impose une nouvelle version majeure et une entrée de catalogue parallèle.

Les migrations sont des upcasters purs et déterministes. Elles conservent document et digest originaux, génèrent
une preuve et ne perdent aucun champ. Une migration impossible produit `INCOMPATIBLE_SCHEMA`. Aucune
down-migration destructive n'est autorisée.

Le retrait N-1 exige zéro usage pendant 14 jours, tests golden/négatifs des deux versions, rejeu des documents
persistés, répétition du rollback et approbation Architecture/Exploitation.

L'API REST historique du pipeline est figée par le manifeste
[`rest-api-pipeline-v1.1.json`](../../../../resources/multiagents/contracts/rest-api-pipeline-v1.1.json).
Les routes, statuts HTTP, champs de requête, champs de réponse et valeurs d'état qui y figurent ne peuvent être
retirés ou modifiés dans la version 1.2 ; `PipelineRestContractTest` applique cette règle à chaque build.
