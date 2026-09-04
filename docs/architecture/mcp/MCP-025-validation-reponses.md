# MCP-025 — Validation locale des réponses MCP

## Principe

Une réponse MCP est une donnée non fiable. Elle est donc validée dans l'adaptateur client avant d'être transmise aux services du workflow. `ValidatedMcpToolInvoker` décore l'adaptateur Spring et applique systématiquement `McpResponseValidator` à chaque résultat d'outil.

Deux contrôles fail-closed sont exécutés dans cet ordre :

1. sérialisation en UTF-8 et refus au-delà de `ai-factory.mcp.client.max-response-bytes` ;
2. validation JSON Schema Draft 2020-12 contre un schéma local associé au nom exact de l'outil.

Un outil sans schéma local est refusé. Les schémas utilisent `additionalProperties: false` afin de rejeter les champs inattendus. Les erreurs applicatives n'incluent ni la réponse, ni sa valeur fautive : seulement l'outil, le nombre d'erreurs et le premier mot-clé de schéma en échec.

## Schémas actifs

Les schémas embarqués depuis `resources/mcp/schemas` couvrent les cinq outils `repository-context-mcp` et les sept outils `sandbox-execution-mcp`. Les résultats de contexte sont normalisés en `snake_case`, comme ceux du sandbox, afin que les noms sérialisés correspondent aux contrats du protocole.

Les schémas `*-runtime-v1.schema.json` décrivent les objets effectivement retournés aujourd'hui par les serveurs. Les schémas enveloppés existants restent la cible d'industrialisation ; leur adoption nécessitera une évolution coordonnée client/serveur et une nouvelle version de contrat.

## Vérification

`McpResponseValidatorTest` couvre :

- une réponse strictement conforme ;
- un champ requis absent et un champ supplémentaire ;
- une réponse dépassant la limite en octets ;
- l'absence de contenu sensible dans le message d'erreur.

Les tests complets de l'orchestrateur et du serveur de contexte valident aussi le chargement des schémas dans l'artefact et la sérialisation `snake_case`.
