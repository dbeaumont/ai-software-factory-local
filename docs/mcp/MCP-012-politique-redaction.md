# MCP-012 — Politique de redaction des secrets et données personnelles

> Statut : baseline normative v1  
> Politique machine-readable : `resources/mcp/policies/redaction-policy-v1.yaml`  
> Orthographe des contrats : le terme technique stable est `redaction`, sans accent

## 1. Objectif

La redaction empêche qu'un secret, une donnée personnelle ou un contenu sensible se propage vers un modèle, une réponse MCP, un log, une trace, une métrique ou une interface non autorisée. Elle ne remplace ni le contrôle d'accès, ni le chiffrement, ni la minimisation des données.

Une preuve de détection doit conserver le type, la règle, l'emplacement, la sévérité et un fingerprint non réversible, jamais la valeur détectée dans les canaux ordinaires.

## 2. Données couvertes

### 2.1 Secrets

- tokens Gitea, SonarQube, Artifactory, LiteLLM et fournisseurs de modèles ;
- headers `Authorization`, cookies de session et credentials dans les URI ;
- mots de passe, clés API, client secrets, access/refresh/ID tokens ;
- clés privées PEM et champs de comptes de service ;
- variables ou réglages dont le nom indique `password`, `secret`, `token`, `api_key` ou `private_key` ;
- toute valeur réellement chargée par le serveur depuis son registre de secrets.

La comparaison avec les valeurs configurées est prioritaire : elle couvre un secret même si son format ne ressemble à aucun pattern connu.

### 2.2 PII et données internes

- nom, email, téléphone, identifiant utilisateur ou approbateur ;
- adresse IP, hostname interne et identifiant d'infrastructure ;
- contenu de ticket ou dépôt classifié ;
- chemin local absolu, stacktrace et détails de topologie ;
- prompts, réponses LLM, code, patchs et rapports selon la classification du dépôt.

Une adresse email dans du code de test n'a pas nécessairement la même sensibilité qu'une identité d'approbateur. Le canal, la provenance et la classification sont donc appliqués après la détection.

## 3. Classifications

| Niveau | Exemple | Modèle | Logs ordinaires |
|---|---|---|---|
| `PUBLIC` | documentation publique approuvée | autorisé | contenu possible si utile |
| `INTERNAL` | métriques agrégées, noms de composants | autorisé selon politique | métadonnées seulement par défaut |
| `CONFIDENTIAL` | code source, ticket, email professionnel | autorisation par dépôt/modèle | pas de contenu brut |
| `RESTRICTED` | vulnérabilité sensible, données réglementées | interdit par défaut | interdit |
| `SECRET` | token, mot de passe, clé privée | toujours interdit | toujours interdit |

L'absence de classification utilise `CONFIDENTIAL`, pas `PUBLIC`.

## 4. Actions

| Action | Usage |
|---|---|
| `REJECT` | un secret apparaît dans un argument qui ne doit jamais en contenir |
| `REDACT` | remplacement complet par un marqueur typé |
| `REDACT_VALUE` | conservation du nom de réglage, suppression de sa valeur |
| `DROP` | champ inutile supprimé du canal |
| `PSEUDONYMIZE` | remplacement d'une identité par un HMAC stable dans un périmètre contrôlé |
| `ENCRYPT_AND_RESTRICT` | conservation nécessaire dans une preuve à accès limité |
| `REDACT_AND_BLOCK_EFFECT` | sortie de modèle contenant un secret : masquer et interdire toute livraison |

Marqueurs canoniques : `[REDACTED:SECRET]`, `[REDACTED:CREDENTIAL]`, `[REDACTED:PRIVATE_KEY]`, `[REDACTED:PII]`. Le marqueur ne reprend ni préfixe, ni suffixe, ni longueur de la valeur.

Un hash SHA-256 brut d'un secret court est interdit, car il permet des attaques par dictionnaire. La corrélation utilise HMAC-SHA-256 avec une clé dédiée, un contexte d'usage et une sortie courte.

## 5. Règles par canal

### 5.1 Entrées MCP

- Les schémas n'exposent aucun champ générique `token`, `headers`, `environment`, `url`, `command` ou `credentials`.
- Un champ secret inattendu provoque `POLICY_DENIED`; le serveur ne masque pas le secret pour poursuivre quand même l'opération.
- Les identités, scopes et tokens du transport sont traités par la couche de sécurité et ne sont jamais copiés dans les arguments métier.
- Le corps brut d'une requête n'est pas journalisé.

### 5.2 Réponses MCP

- Les résultats sont redacted avant persistance, pagination et transmission.
- Les messages d'erreur publics utilisent `safe_message`; stacktrace, commande, URI interne et réponse backend restent dans un canal protégé et redacted.
- Une sortie tronquée après redaction porte un indicateur explicite. La troncature ne peut pas couper une valeur avant que le détecteur l'ait examinée.
- Les URI de ressources ne contiennent ni credentials, ni hostname de backend, ni chemin absolu.

### 5.3 Prompts et réponses des modèles

- Le contexte est minimisé avant redaction : exclusion des fichiers sensibles, puis redaction des réglages restants.
- Un bloc non fiable conserve provenance, limites et marqueur de confiance ; une instruction trouvée dans le contenu ne devient pas une instruction système.
- Aucun token fournisseur ou backend n'est accessible au processus qui construit le prompt.
- Prompts et réponses ne sont pas journalisés en clair. Sont conservés par défaut : modèle, compte de tokens, taille, durée et empreinte du prompt système.
- Si une réponse LLM contient une valeur détectée comme secret, la réponse affichée est masquée et le workflow bloque tout effet jusqu'à investigation.

### 5.4 Logs, traces et métriques

- Logs applicatifs : événements, tailles, durées, statuts, codes d'erreur, digests et IDs de corrélation ; pas d'arguments/résultats bruts.
- Audit : principal pseudonymisé ou identité autorisée selon besoin réglementaire, décision, outil, scope, policy version et digests.
- Traces : spans et attributs techniques ; capture des prompts/réponses désactivée par défaut.
- Métriques : labels de faible cardinalité uniquement. `task_id`, `attempt_id`, utilisateur, dépôt et valeur détectée sont interdits comme labels.
- La redaction intervient avant l'appel au framework de logging/export, pas seulement dans le backend de collecte.

### 5.5 Preuves et UI

- Une preuve brute nécessaire à l'audit peut rester chiffrée et strictement autorisée ; son accès est explicite et audité.
- Un rapport de détection conserve règle, fichier, ligne, sévérité et fingerprint HMAC, jamais le secret en clair.
- Le résumé fourni au Reviewer ou à l'UI est redacted et limité au rôle.
- Copier/télécharger une preuve brute demande un droit distinct de la consultation du manifeste.

## 6. Ordre de traitement

```text
borne d'entrée
  -> classification des champs
  -> correspondance des secrets configurés
  -> détecteurs à haute confiance
  -> politique du canal
  -> fingerprint autorisé
  -> troncature du contenu redacted
  -> persistance ou transmission
```

La borne d'entrée protège le détecteur contre le déni de service, sans rendre le contenu sûr. Si la donnée dépasse la taille analysable, elle est rejetée ou stockée dans un canal de quarantaine ; elle n'est jamais transmise non analysée.

## 7. Détection et faux positifs

La détection combine :

1. noms de champs structurés ;
2. valeurs de secrets réellement configurées ;
3. formats à haute confiance, comme clé privée, header d'autorisation ou JWT ;
4. règles fournisseur versionnées ;
5. entropie uniquement comme signal complémentaire.

Les règles PII contextuelles peuvent être moins agressives dans une preuve brute restreinte que dans un log. Une allow-list de faux positifs doit être versionnée, limitée à un détecteur et une provenance, posséder un propriétaire et une expiration. Elle ne peut jamais autoriser une valeur égale à un secret configuré.

## 8. Tests obligatoires

Le corpus utilise exclusivement des secrets sentinelles non valides et couvre :

- valeur en clair, URL-encodée, Base64, JSON échappé, multiligne et coupée entre deux buffers ;
- header, URI avec userinfo, argument de commande, variable d'environnement et stacktrace ;
- stdout/stderr sandbox, réponse Sonar/Gitea, résultat MCP, prompt et réponse LLM ;
- clés privées, JWT factice, mots de passe de configuration et tokens de formats connus ;
- emails/téléphones réels et faux positifs typiques du code source ;
- redaction avant pagination, troncature, snapshot, retry, log et export de trace ;
- échec du moteur de détection et politique inconnue, tous deux fail-closed.

Les assertions parcourent logs de test, réponses HTTP/MCP, snapshots, traces exportées et artefacts UI. Une recherche finale de chaque sentinelle doit retourner zéro occurrence hors coffre de test explicitement autorisé.

## 9. État actuel et écarts

| Composant | Contrôle présent | Écart |
|---|---|---|
| `RepositoryContextService` / contexte MCP | réglages sensibles masqués, chemins sensibles exclus | corpus de faux positifs/faux négatifs incomplet |
| sandbox MCP | patterns de réglages + remplacement des valeurs Sonar/Artifactory configurées avant snapshot | détecteurs communs, PII et buffers fragmentés à ajouter |
| `ProcessRunner` | masque explicitement `SONAR_TOKEN` et `ARTIFACTORY_TOKEN` dans certains arguments | couverture trop spécifique ; une URI Git avec credentials peut apparaître dans un message d'échec |
| orchestrateur | ne journalise pas les prompts/réponses complets dans le chemin nominal | messages d'exception backend à normaliser en `safe_message` |
| traces | capture brute non mise en place | politique à imposer lors de MCP-221 |
| preuves | digests des sorties sandbox | stockage chiffré/RBAC et fingerprint HMAC à implémenter |

La construction actuelle d'une URL Git authentifiée par `GiteaService` est un risque prioritaire : jusqu'à sa suppression par MCP-123, toute erreur de commande doit masquer les credentials de l'URI.

## 10. Gouvernance et incident

- Le Représentant RSSI possède les types sensibles, règles et exceptions.
- Le Product Owner valide l'impact fonctionnel d'une redaction ou d'un blocage.
- Les règles sont versionnées et leurs versions figurent dans les preuves/audits.
- Un match secret inattendu déclenche blocage de l'effet, conservation d'un finding sans valeur et alerte selon sévérité.
- Une fuite confirmée déclenche révocation/rotation du secret, purge ou restriction des copies, analyse des accès et ajout d'un test de non-régression.

## 11. Critères de clôture de MCP-012

- [x] Les secrets, PII et données internes sont classifiés.
- [x] Les actions sont définies pour entrées, sorties, logs, traces, métriques, preuves, modèles et UI.
- [x] L'ordre redaction/fingerprinting/troncature est fixé.
- [x] Les exigences de tests et le comportement fail-closed sont définis.
- [x] Une politique machine-readable versionnée accompagne le document.

