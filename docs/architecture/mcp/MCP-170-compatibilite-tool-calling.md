# MCP-170 — Compatibilité du tool calling

## Périmètre qualifié

Le seul modèle exposé à l'orchestrateur est l'alias LiteLLM `factory-code-cloud`. Le modèle fournisseur placé derrière cet alias est une configuration d'exploitation : il n'est supporté qu'après passage du même test contractuel.

Le contrat vérifie sans appel cloud :

- le nom complet de l'outil, y compris son namespace MCP ;
- le JSON Schema d'entrée, sans simplification silencieuse ;
- l'identifiant opaque `tool_call.id` ;
- le nom et les arguments JSON renvoyés ;
- la corrélation du résultat via `tool_call_id`.

Un appel incomplet ou dont les champs stables ont été perdus est refusé fermé par l'hôte. La campagne n'envoie aucun code, ticket, log ou preuve à un fournisseur externe.

## Règle d'ajout d'un modèle

Tout nouvel alias ou modèle fournisseur doit exécuter ce test avec ses réponses enregistrées avant d'être ajouté à `config.template.yaml`. La qualification live reste une étape d'exploitation distincte, avec un corpus explicitement autorisé.
