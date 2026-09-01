# MCP-151 — Port Cloud Storage pour les preuves

Le backend cible conserve l'URI logique `evidence://<task>/<attempt>/<type>/<id>`.
Seule sa résolution physique change vers :

`gs://<bucket>/v1/<task>/<attempt>/<type>/<id>`

Contraintes préparées dans `GcsEvidenceBackend` :

- bucket avec politique de rétention verrouillée obligatoire ;
- chiffrement CMEK obligatoire ;
- création conditionnelle `ifGenerationMatch=0`, donc aucun écrasement ;
- date de rétention minimale vérifiée avant écriture ;
- métadonnées `logical-uri`, `sha256`, classification et type d'attestation ;
- identité prévue par Workload Identity, sans fichier de clé persistant.

L'adaptateur local chiffré reste actif pour le prototype. L'exécution du
descripteur par le SDK Cloud Storage sera branchée avec le déploiement GCP, sans
changer les contrats ni les URI exposées aux clients.
