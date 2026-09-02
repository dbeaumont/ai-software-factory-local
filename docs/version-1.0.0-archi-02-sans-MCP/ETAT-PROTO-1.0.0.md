# Positionnement du Proto version Pipeline (v1.0.0)

Le proto 1.0.0 est de type Architecture 02 : 
- Pipeline IA déterministe et monolythique
- Sans serveur MCP
- Sans agent autonomes

## Verdict

Le projet est actuellement dans un état de prototype fonctionnel, pas encore dans une architecture d’usine logicielle IA industrielle. La documentation du dépôt le qualifie explicitement de “prototype local” et de “POC / Proof of Concept” dans `proto-architecture.md` et `proto-security.md`, tandis que `cible-industrialisation avec GCP.md` décrit la cible d’industrialisation avec GCP comme un écart clair par rapport au prototype.

En clair, il correspond à une “AI software factory de démonstration / validation de principe”, avec un workflow agentique contrôlé, mais pas à une plateforme d’entreprise exploitable en production.

## Ce qui montre un prototype avancé

Le projet a déjà les éléments d’une architecture de preuve de concept solide :

- flux ticket → plan → patch → validation → tests → qualité → sécurité → revue → approbation → PR
- orchestration asynchrone via Spring Boot
- rôles IA spécialisés : Planner, Developer, PatchRepair, Tester, Reviewer
- sandbox Docker éphémère
- validations déterministes : diff, tests, SonarQube, SBOM, Trivy
- approbation humaine avant PR
- observabilité locale (Prometheus / Grafana)

Tout cela est visible dans `README.md`, `proto-architecture.md` et `compose.yaml`.

C’est précisément le type d’architecture “prototype de workflow agentique piloté par des preuves” que le document cible décrit comme un bon socle, mais pas comme une cible finale.

## Ce qui montre qu’on est encore en prototype

Les limites structurales sont très nettes, et elles correspondent exactement aux écarts listés dans `cible-industrialisation avec GCP.md` :

- tâches conservées en mémoire : pas de persistance durable, pas de reprise fiable
- orchestrateur monté avec le socket Docker de l’hôte dans `compose.yaml`
- sandbox sur réseau partagé, pas de default-deny ni d’isolement stricte
- secrets placés localement, pas d’identity-based access
- pas de SSO / RBAC / séparation de responsabilités d’entreprise
- pas de workflow durable ni de file de jobs idempotente
- pas de gouvernance avancée des modèles, prompts et outils
- pas de traces GenAI, coût, provenance, versioning de workflow et attestation

Le point le plus révélateur est dans `SandboxService.java` : le sandbox est lancé avec docker run et utilise un réseau explicite, avec accès au volume partagé et au socket Docker. C’est un excellent POC pour valider la logique d’agent, mais pas une isolation de niveau production.

## Positionnement par rapport aux architectures présentées

Si on classe les architectures selon la logique du document :

1. Prototype local / démonstrateur d’agent
   - C’est bien l’état actuel
   - On a la chaîne fonctionnelle, les garde-fous, l’orchestration et la validation
   - Mais le système reste mono-instance, local, partagé et fragile

2. Architecture de référence industrielle GCP
   - C’est la cible décrite dans `cible-industrialisation avec GCP.md`
   - Elle sépare contrôle, IA/context, exécution non fiable et confiance/livraison
   - Elle impose durable, réseau isolé, IAM, secret manager, sandbox dédiée, provenance, SLSA, observabilité

3. Entre les deux
   - Le projet est donc à un stade “prototype avancé / MVP de workflow agentique”, pas “plateforme d’entreprise”
   - Il est déjà plus mature que le simple assistant IDE, mais pas encore “factory industrialisée”

## Conclusion

Le projet est bien dans l’état de “prototype de référence” au sens de l’architecture présentée dans le document PDF : il démontre la faisabilité d’une usine logicielle IA orientée ticket → PR, avec validation et approbation humaine.

Mais il n’est pas encore une architecture de type “AI software factory industrielle” :
- il manque la durabilité du workflow,
- l’isolation forte des exécutions,
- la gouvernance IAM / secret / SSO,
- la provenance et la confiance du build,
- l’observabilité finie et la gestion multi-tenant.

En une phrase : il est un très bon prototype de workflow agentique contrôlé, mais pas encore une plateforme d’entreprise prète à l’industrialisation.

Si tu veux, je peux maintenant te faire une version plus structurée en :
- “niveau de maturité par catégorie d’architecture”,
- “matrice prototype vs cible GCP”,
- ou “recommandations concrètes pour passer du prototype à l’industrialisation”.

