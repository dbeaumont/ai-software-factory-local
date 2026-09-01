# MCP-179 — Simulation d'un serveur compromis

La suite locale `CompromisedMcpServerTest` couvre les cinq comportements attendus :

1. ajout dynamique d'un outil hors allow-list : serveur `INCOMPATIBLE` ;
2. modification du schéma de réponse : rejet par le JSON Schema local fermé ;
3. réponse surdimensionnée : rejet avant interprétation ;
4. URI de preuve externe : rejet, seules les URI `evidence://` sont admises ;
5. instruction malveillante dans un résultat par ailleurs valide : conservation comme donnée non fiable, sans possibilité de fermer l'enveloppe.

Les contrôles résident dans l'hôte. Un serveur ne peut donc pas les désactiver en modifiant son catalogue ou sa réponse.
