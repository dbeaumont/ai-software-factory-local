# MCP-087 — Campagne shadow sandbox

Date : 1er septembre 2026.

## Méthode

La socket Docker ayant déjà été retirée de l'orchestrateur, le chemin historique `SandboxService` n'a pas été réintroduit dans son conteneur. La comparaison a été exécutée depuis l'hôte sur deux clones jetables et distincts du même dépôt :

- référence directe : `mcp087direct` ;
- candidat MCP : `mcp087mcp` ;
- source commune : `3ddff5310c53a19614101aa1b4888827807ed9d3` ;
- patch commun : SHA-256 `ed155c08c2aa8f8fb46bccad984bea66df2c6509099bc2201bae9ab561491df9`.

Les commandes de référence reprennent les scripts et contraintes de `SandboxService`. Le candidat passe exclusivement par les outils MCP et leurs handles asynchrones.

## Résultats

| Contrôle | Chemin direct | Chemin MCP | Conclusion |
|---|---|---|---|
| application du patch | exit `0` | `SUCCEEDED/PASSED`, exit `0`, exécution `0ee051c5689b454a9931c3fbf69df7fb` | diff stat exact : 2 fichiers, 28 insertions, 1 suppression |
| tests Maven | exit `0`, 3 tests, 0 échec | `SUCCEEDED/PASSED`, exit `0`, 3 tests, 0 échec, exécution `031fa3641fee479995f610d6b6aaa081` | parité fonctionnelle |
| qualité Sonar | échec de résolution Maven sur réseau durci sans proxy | `SUCCEEDED/PASSED`, quality gate `PASSED`, exécution `c0917ef8a5c04792afe6bdbe19c81736` | divergence attendue et favorable au candidat MCP |
| SBOM Syft | produit avant l'échec Trivy | produit avec preuve complète | 4 composants dans chaque SBOM ; liste triée des PURL de même digest `24694710a3636c23de8e6b70cd1a0fcec51ec69f26cdfc08699c6ac160aa4fc1` |
| Trivy | échec DNS vers `mirror.gcr.io` sur réseau durci sans proxy | `SUCCEEDED/PASSED`, 0 vulnérabilité, exécution `901b66526e114385b02fc21c7cdfab13` | divergence attendue : MCP utilise exclusivement le proxy allow-listé |

Les fichiers compilés ajoutent des entrées binaires variables au `git diff --stat` après les tests. La comparaison de l'application du patch, effectuée avant les builds, est strictement identique sur les deux workspaces.

## Qualification des divergences

Le chemin historique lançait qualité et sécurité sur un réseau sans transmettre les variables du proxy contrôlé. Une fois l'egress direct interdit, Maven et Trivy ne pouvaient plus résoudre leurs dépôts. Rouvrir DNS/Internet au conteneur aurait affaibli la cible.

Le serveur MCP corrige cette divergence en conservant :

- une liste de destinations gérée par Squid ;
- des réseaux distincts pour les dépendances et SonarQube ;
- des variables d'environnement construites côté serveur ;
- aucun paramètre de réseau, proxy ou secret dans l'appel MCP.

La différence est donc acceptée comme un gain de sécurité et de disponibilité, et non comme une régression fonctionnelle.

## Décision

La campagne valide la bascule des cinq opérations sandbox vers MCP. Le chemin direct peut être supprimé après vérification des feature flags indépendants de MCP-088. Les deux fixtures restent dans le volume local de démonstration pour audit et seront éliminées avec la prochaine purge des workspaces.
