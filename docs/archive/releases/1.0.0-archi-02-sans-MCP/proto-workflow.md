# Fonctionnement de l'usine logicielle

Le pipeline transforme un besoin exprime sous forme de ticket en Pull Request Gitea, avec un ensemble de contrôles déterministes et une approbation humaine préalable obligatoire.

![Diagramme du flux de l'usine logicielle](assets/proto-software-factory-workflow.svg)

| Sujet | Étape | Objectif | Outils et composants |
|---|---|---|---|
| Préparation | 1. Saisie du besoin | Rédiger le ticket structuré ; l'inférence utilise le modèle cloud configuré | `factory-web` (SPA), Nginx |
| Préparation | 2. Création de la tâche | Attribuer une référence (`AF-0001`), instancier l'état en mémoire et démarrer le pipeline asynchrone | `orchestrator` (Spring Boot / API REST) |
| Préparation | 3. Analyse du contexte | Cloner le dépôt Git cible et extraire la structure du projet | Gitea, Git, `RepositoryContextService` |
| Conception | 4. Planification | Générer une feuille de route détaillée (`.ai-plan.md`) | Agent `Planner`, LiteLLM, OpenAI |
| Développement | 5. Développement | Produire un patch unifié (`unified diff`) à partir du besoin et du plan | Agent `Developer`, `UnifiedDiffNormalizer` |
| Développement | 6. Validation du patch | Vérifier l'applicabilité du diff sans accès réseau (`git apply --check`) | Sandbox Docker (`ai-factory-sandbox:local`) |
| Développement | 7. Réparation de patch | Régénérer un diff unifié valide si la première version échoue à l'application | Agent `PatchRepair`, LiteLLM |
| Développement | 8. Application en sandbox | Appliquer le patch et contrôler l'absence d'erreurs de format (`git diff --check`) | Sandbox Docker, Git |
| Validation | 9. Tests automatisés | Exécuter la suite de tests (Maven via miroir Artifactory, Gradle ou npm) et analyser les journaux | Maven / Gradle / npm, Artifactory, Agent `Tester` |
| Validation | 10. Analyse de qualité | Exécuter l'analyse qualimétrique du code source pour les projets Maven | SonarQube Scanner (`sonar-maven-plugin`) |
| Validation | 11. Analyse de sécurité | Générer le SBOM CycloneDX et scanner les vulnérabilités et secrets | Syft (`sbom.cdx.json`), Trivy (`trivy.txt`) |
| Revue | 12. Revue globale | Synthétiser le besoin, le plan, le patch et l'ensemble des preuves déterministes dans `.ai-review.md` | Agent `Reviewer`, LiteLLM |
| Validation | 13. Décision humaine | Examiner la proposition et autoriser la livraison (`POST /api/tasks/{id}/approve`) | API Spring Boot, IHM `factory-web` |
| Livraison | 14. Livraison SCM | Basculer sur une branche dédiée, nettoyer les fichiers temporaires IA, committer, pousser et créer la PR | Git, Gitea REST API |

Le modèle de langage est sollicité dans le cloud via OpenAI (`gpt-5.6-luna` par défaut). LiteLLM constitue le point de passage unique et homogène pour tous les appels de modèles.
