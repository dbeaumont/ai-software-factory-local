# A2A-110 — Modèle de menaces A2A sous orchestration Temporal

- Statut : baseline de sécurité implémentée, risques opérationnels suivis jusqu'à la gate de cutover
- Date : 2026-09-06
- Portée : orchestrateur, runtimes des 14 rôles, Agent Cards, transport A2A, workflows Temporal d'agent,
  notifications, MCP et références Evidence
- Méthode : STRIDE, vraisemblance et impact notés de 1 à 5
- Registre maître : `docs/qualification/a2a/A2A-RISK-REGISTER.md`

## 1. Objectifs de sécurité

- Une identité ne peut invoquer, lire, lister ou annuler que les rôles, skills, tenants et tâches autorisés.
- Temporal reste l'unique autorité de coordination ; une sortie d'agent ne déclenche jamais directement un pair.
- Une requête rejouée, un timeout ambigu ou une notification dupliquée ne crée pas une seconde exécution logique.
- Une carte, une URL, un artefact ou une réponse non vérifiable est refusé avant effet.
- Aucun secret ni contenu métier sensible n'entre dans les journaux, traces ou historiques A2A/Temporal.
- Une charge hostile est bornée avant parsing, persistance, appel LLM ou effet MCP.

## 2. Actifs et adversaires

### Actifs

| Actif | Propriété prioritaire | Autorité |
|---|---|---|
| DAG, gates, timers, retries et annulations | intégrité, disponibilité | workflow racine Temporal |
| Identité et capacités d'un rôle | authenticité, intégrité | catalogue + Agent Card signée |
| Tâche A2A et corrélation | confidentialité, intégrité | projection A2A + historique Temporal |
| Code, prompts et résultats | confidentialité, intégrité | dépôt / runtime / Evidence MCP |
| Certificats, clés JWK, OAuth2 et HMAC | confidentialité, authenticité | secrets montés par workload |
| Artefacts et verdicts | intégrité, traçabilité | Evidence MCP |
| Autorisations d'outil et effets SCM | moindre privilège | MCP propriétaire + workflow |

### Adversaires retenus

- utilisateur authentifié malveillant ou curieux ;
- contenu hostile provenant d'un ticket, dépôt, modèle, artefact ou outil ;
- agent, serveur A2A ou serveur MCP compromis ;
- workload voisin capable d'émettre du trafic réseau ;
- dépendance ou image de supply chain compromise ;
- opérateur commettant une erreur de configuration ou de rotation.

Le réseau interne n'est jamais une preuve d'identité. Une valeur signée reste non fiable si son audience, son
tenant, sa fraîcheur, sa portée ou sa corrélation ne correspondent pas à l'opération.

## 3. Flux et frontières de confiance

```text
[API/UI non fiable]
        | F1
        v
[Orchestrateur + workflow Temporal racine]
        | F2  mTLS + OAuth2 + A2A 1.0
        v
[Serveur A2A d'un rôle] -- F3 --> [Projection de tâches]
        |
        | F4  démarrage/signal Temporal
        v
[AgentTaskWorkflowV1 + worker du rôle]
        | F5                     | F6
        v                        v
[LLM, données non fiables]   [MCP autorisés] -- F7 --> [Evidence / SCM]

[Serveur A2A] -- F8 HMAC --> [récepteur de notification orchestrateur]
[Agent Card]  -- F9 JWS + origine fixe --> [cache vérifié orchestrateur]
```

| Frontière | Décision obligatoire avant franchissement |
|---|---|
| F1 | schéma, taille, tenant et admission |
| F2 | certificat, issuer, audience, client, rôle, opération et skill |
| F3 | autorisation avant lookup, isolation tenant/client et version optimiste |
| F4 | identité logique stable et mapping d'état autorisé |
| F5 | données marquées non fiables, budget et sortie validée |
| F6/F7 | rôle, outil, tentative, URI, digest et effet autorisés côté serveur |
| F8 | callback fixe, HMAC, séquence et inbox idempotente |
| F9 | origine sans redirection, JWS, `kid`, empreinte, issuer, rôle, skills et expiration |

## 4. Cotation et règle d'acceptation

Le score initial est `P × I`. `16–25` est critique, `10–15` élevé, `5–9` modéré et `<5` faible. Aucun risque
résiduel critique n'est acceptable. Un résiduel élevé nécessite une décision RSSI explicite liée au commit, aux
images et aux preuves exactes. Les contrôles marqués « gate » doivent être rejoués avant cutover.

## 5. Scénarios prioritaires

| ID | Menace | STRIDE | Initial | Contrôles en place | Résiduel cible |
|---|---|---|---:|---|---:|
| A2A-TM-01 | spoofing ou altération d'Agent Card | S/T | 15 | JWS RFC 8785, origine fixe, empreinte, expiration, cache borné | 4 |
| A2A-TM-02 | confused deputy entre rôles, A2A et MCP | S/E | 20 | identités/scopes par rôle, `mayDelegateTo`, ACL serveur | 6 |
| A2A-TM-03 | rejeu ou double exécution | T/R | 20 | `messageId` déterministe, unicité, séquences, réconciliation | 6 |
| A2A-TM-04 | SSRF et DNS rebinding | I/E | 20 | HTTPS exact, IP interdites, zéro redirect, DNS pinning | 4 |
| A2A-TM-05 | élévation de privilège | S/E | 20 | mTLS, OAuth2 borné, rôle actif non substituable, aucun secret partagé | 6 |
| A2A-TM-06 | poisoning d'artefact ou de résultat | T/E | 15 | URI Evidence liée, digest, taille, type, commit et tentative | 6 |
| A2A-TM-07 | accès cross-tenant / task enumeration | S/I/T | 20 | autorisation avant store, clés tenant/client, erreur uniforme | 4 |
| A2A-TM-08 | déni de service | D | 16 | taille, débit, tâches actives, files, polling, tokens et rétention bornés | 6 |

### A2A-TM-01 — Spoofing de carte

**Scénario.** Un attaquant sert une carte depuis une origine ressemblante, remplace l'endpoint ou les skills,
rejoue une carte expirée, injecte un `kid` ou exploite un redirect/cache obsolète.

**Prévention.** Le registre fixe URI et endpoint par rôle. La résolution interdit redirections et destinations
réservées. La signature porte la forme canonique RFC 8785 ; rôle, issuer, provider, protocole, transport, skills,
empreinte et expiration sont revalidés avant mise en cache. Une rotation invalide le cache et conserve une période
de chevauchement contrôlée.

**Détection/réponse.** L'échec ou le changement produit un événement `a2a_decision` sans contenu de carte. Le rôle
est suspendu, les admissions associées ferment et aucun endpoint alternatif n'est essayé.

**Preuves.** `A2aAgentCardVerifierTest`, `CachingAgentCardResolverTest`, tickets A2A-054 à 058 et A2A-108.

### A2A-TM-02 — Confused deputy

**Scénario.** Un agent demande à l'orchestrateur ou à MCP un skill/outil/effet réservé à un autre rôle, substitue
`actor`, ou présente une intention de délégation comme un ordre déjà autorisé.

**Prévention.** Le principal vient du certificat/jeton et non du payload. Les scopes portent rôle, opération et
skill. Le serveur recoupe `mayDelegateTo`, le rôle actif et la matrice MCP. Une intention retourne à Temporal, qui
seul valide le DAG, les budgets et la nouvelle invocation. Les identités SCM restent hors des runtimes d'agents.

**Détection/réponse.** Refus audité, jeton révoqué, rôle suspendu et tâche annulée ; aucun fallback direct.

**Preuves.** `A2aAuthorizationOrderingTest`, `McpToolAuthorizationFilterTest`, `RoleScopedMcpClientTest`, A2A-103/104.

### A2A-TM-03 — Rejeu et ambiguïté

**Scénario.** Après un timeout ou une perte d'ACK, le client renvoie `SendMessage`; une ancienne continuation,
notification ou annulation est rejouée ; deux requêtes concurrentes visent le même identifiant.

**Prévention.** `messageId` et identités Temporal sont déterministes. Le store crée ou relit atomiquement et
refuse même identifiant avec digest différent. Les séquences et versions sont monotones. Temporal réconcilie par
`GetTask` avant toute décision et une notification n'est jamais une autorité indépendante.

**Détection/réponse.** Collision auditée, état réconcilié depuis les autorités durables, aucun retry aveugle.

**Preuves.** tests `A2aSendMessageService`, `PostgresA2aTaskStore`, inbox de notification et A2A-047/060/064/082.

### A2A-TM-04 — SSRF

**Scénario.** Une carte, un callback ou une référence Evidence force un accès loopback, link-local, metadata cloud,
réseau d'administration, redirection ou adresse différente après résolution DNS.

**Prévention.** Les URLs sont issues de registres, utilisent HTTPS, correspondent exactement à l'allow-list et ne
contiennent ni userinfo ni fragment. Chaque tentative valide IP et DNS pinning ; redirects et plages sensibles
sont refusés. Les références Evidence lient task, attempt et digest.

**Détection/réponse.** Refus avant I/O, audit digesté, retrait du workload et rotation si une tentative révèle un
secret.

**Preuves.** `AgentCoreTest`, `A2aUrlPolicyTest`, `AllowListedAgentRegistryTest`, tests de rebinding des
cartes/notifications et A2A-105.

### A2A-TM-05 — Élévation de privilège

**Scénario.** Un workload vole ou réutilise un credential, forge un rôle/tenant, demande une audience plus large,
ou lit un secret partagé avec un autre agent.

**Prévention.** Certificat SPIFFE et client OAuth2 distincts par rôle, TLS 1.3 mutuel, tokens courts à audience et
scopes exacts, fichiers secrets propriétaire seul, buffers effacés et aucun passthrough. Le serveur reconstruit le
rôle depuis l'identité authentifiée.

**Détection/réponse.** Échec d'authentification journalisé, révocation certificat/token, fermeture d'admission et
rotation atomique.

**Preuves.** A2A-100 à 104, `A2aSecurityDeclarationTest`, tests PKI/révocation et rotation A2A-109.

### A2A-TM-06 — Poisoning d'artefact

**Scénario.** Un agent retourne un artefact d'une autre tâche/tentative, une URI mutable, un digest faux, un type
mensonger ou une instruction hostile destinée au prochain modèle/gate.

**Prévention.** Seules les références Evidence internes et bornées circulent. URI, tenant, task, attempt,
sourceCommit, SHA-256, taille et media type sont contrôlés avant validation. Le contenu relu reste non fiable et la
sortie contractuelle est validée avant publication ou effet.

**Détection/réponse.** Verdict non retryable, gate bloquée, preuve conservée et tâche isolée.

**Preuves.** `AgentExecutionWorkerTest`, politiques URI Evidence, schémas A2A et A2A-041/043–046/069/106.

### A2A-TM-07 — Cross-tenant

**Scénario.** Un client devine un `Task.id`, réutilise un curseur, change son tenant ou annule une tâche étrangère.

**Prévention.** Authentification et scopes sont vérifiés avant toute interaction avec le store. Les requêtes sont
liées à `tenantId + callerSubject`; les curseurs portent le même contexte et sont à usage unique. Toute divergence
retourne le même `TaskNotFound`, sans oracle d'existence.

**Détection/réponse.** Refus digesté, seuil d'énumération, révocation et investigation par corrélation interne.

**Preuves.** `A2aAuthorizationOrderingTest`, `A2aGetTaskTest`, `A2aListTasksTest`, `A2aCancelTaskTest`, A2A-103.

### A2A-TM-08 — Déni de service

**Scénario.** Un principal envoie des payloads géants, crée trop de tâches, poll/annule en boucle, provoque une
tempête de notifications/retries ou épuise tours, tokens, mémoire, file, stockage ou cardinalité.

**Prévention.** Limite avant parsing de 1 Mio, collections/pagination bornées, rate limits par identité/opération,
quotas actifs par tenant, file et pollers bornés, budgets tours/tokens/coût, retry avec backoff et
`continue-as-new`. La table de rate limiting est elle-même bornée et fail-closed.

**Détection/réponse.** Catégorie stable `QUOTA_EXCEEDED`, métriques de saturation, fermeture d'admission et drainage
Temporal sans nouvelle exécution logique.

**Preuves.** `A2aIdentityRateLimiterTest`, `A2aAdmissionControllerTest`, tests payload/worker, A2A-072/092/107.

## 6. Abus composés prioritaires

1. Carte forgée → endpoint attaquant → vol OAuth2 : bloqué par origine exacte, JWS, mTLS et secret jamais transmis.
2. Injection dépôt → demande MCP privilégiée → PR : bloqué par données non fiables, rôle serveur et gates Temporal.
3. Timeout `SendMessage` → rejeu → deux artefacts : bloqué par `messageId`, digest et unicité transactionnelle.
4. URI Evidence → metadata cloud → élévation : bloqué avant I/O par politique URI et réseau default-deny cible.
5. Enumeration cross-tenant → polling massif : autorisation avant lookup puis rate limit par identité.

## 7. Vérification et gouvernance

- [x] Les huit familles exigées sont reliées à une frontière, un actif et une catégorie STRIDE.
- [x] Chaque scénario possède prévention, détection, réponse et tests de preuve identifiés.
- [x] Les contrôles existants sont distingués des gates de déploiement encore ouvertes.
- [x] Le registre A2A-R01 à R20 conserve score, propriétaire et tickets de traitement.
- [x] Les événements de sécurité ne journalisent que des champs fixes et des digests.
- [ ] La campagne adversariale A2A-168 rejoue ces scénarios sur la topologie Compose complète.
- [ ] Le pentest A2A-183 et l'approbation RSSI A2A-189 acceptent les risques résiduels de la release exacte.

Ce document est revu à chaque nouveau skill, changement d'identité/secret, endpoint, protocole, stockage, outil MCP,
frontière réseau ou incident. Une case de migration ne constitue jamais l'acceptation d'un risque résiduel.
