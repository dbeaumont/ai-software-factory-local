# MCP-149 — Cas négatifs assurance et preuves

La suite automatisée couvre les invariants bloquants suivants :

- digest annoncé différent du contenu : rejet avant écriture ;
- ciphertext altéré après stockage : authentification AES-GCM en échec et manifeste refusé ;
- manifeste sans l'un des neuf artefacts obligatoires : rejet ;
- URI provenant d'une autre tâche : rejet avant lecture ;
- preuve `PARTIAL` ou sévérité inconnue : verdict `INDETERMINATE` ;
- scanner inconnu : erreur d'argument, jamais un résultat passant ;
- décision de politique avec entrée manquante ou inconnue : `INDETERMINATE`, jamais `ALLOW`.

Les tests concernés sont `EvidenceStoreTest`, `AssuranceToolsTest` et
`AssuranceSchemasTest`.
