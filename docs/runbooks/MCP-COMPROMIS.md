# Runbook — serveur MCP compromis ou preuve altérée

## Objectif

Isoler un serveur MCP suspect, empêcher tout nouvel effet et préserver les éléments d'enquête. Ce runbook couvre
`AiFactorySandboxHeartbeatInvalid`, `AiFactoryEvidenceAltered`, une divergence de protocole/outil et toute
suspicion de compromission d'un serveur MCP.

## Confinement immédiat

1. Stopper les nouvelles admissions et identifier le serveur, l'outil, sa version/digest, la première et la
   dernière tâche potentiellement affectées.
2. Si le fichier `AI_FACTORY_MCP_KILL_SWITCH_FILE` est effectivement monté, désactiver le serveur ou ses outils et
   inscrire une nouvelle `revision`; un contrôle illisible échoue fermé. Dans le Compose courant, ce montage manque :
   arrêter et isoler le service suspect, puis bloquer les admissions jusqu'à son rétablissement contrôlé.
3. Pour `scm-delivery-mcp`, `assurance-mcp`, `evidence-mcp` ou `sandbox-execution-mcp`, geler tous les effets en vol
   dont l'issue n'est pas confirmée. Ne pas activer de chemin direct de secours.
4. Préserver conteneurs, journaux, volumes et historiques ; ne pas modifier la preuve suspecte.

Exemple de contrôle minimal réservé à Exploitation :

```properties
revision=incident-<identifiant>
global.disabled=false
servers.disabled=evidence-mcp
tools.disabled=
roles.disabled=
modes.disabled=
role-modes.disabled=
```

## Diagnostic

```bash
docker compose -f infrastructure/compose.yaml ps
docker compose -f infrastructure/compose.yaml logs --tail=200 orchestrator evidence-mcp sandbox-execution-mcp scm-delivery-mcp assurance-mcp repository-context-mcp
```

Comparer version, digest d'image, protocole annoncé, allow-list et schémas aux valeurs versionnées. Vérifier hors
du serveur suspect les digests des preuves, manifestes, décisions, approbations et sorties sandbox. Rechercher les
accès cross-task et les appels refusés dans le journal de sécurité chaîné, sans y copier de secret.

## Rétablissement

1. Révoquer et renouveler les identités ou secrets accessibles au serveur compromis.
2. Restaurer depuis un artefact signé et épinglé connu, ou déployer une version corrigée sur une instance isolée.
3. Rejouer les tests de compatibilité, permissions, isolation, intégrité et idempotence.
4. Réconcilier les effets par clé d'idempotence et invalider toute approbation liée à un digest divergent.
5. Réactiver d'abord en environnement isolé puis en shadow ; retirer le kill switch seulement après approbation
   Exploitation et Sécurité.

## Vérification et clôture

- aucune nouvelle anomalie de heartbeat ou d'intégrité sur deux fenêtres ;
- identité, image, outils et schémas correspondent aux références approuvées ;
- périmètre des tâches affectées et statut de chaque effet établis ;
- secrets renouvelés et anciens accès révoqués ;
- preuve d'exercice de rollback et rapport d'incident conservés.

## Escalade

Traiter toute preuve altérée ou compromission probable comme critique et prévenir immédiatement Sécurité,
Exploitation et le propriétaire du système affecté. Appliquer le [rollback global](ROLLBACK-MULTI-AGENTS.md) si
l'intégrité, l'isolation ou l'étendue de l'incident ne peut pas être démontrée.
