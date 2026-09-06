# Rapports TCK A2A

Les rapports archivés `compatibility.json`, `compatibility.html` et
`junitreport.xml` sont produits par `scripts/run-a2a-tck.sh` à partir du TCK
officiel `1.0.0.alpha2`, commit `29063fe95e903cddac5d8ff811ab94df1ad6ef86`.

Ce tag contient l'anomalie amont
[`#202`](https://github.com/a2aproject/a2a-tck/issues/202) : deux exigences qui
imposent une erreur ne déclarent pas leur `expected_error`. Le patch minimal
`infrastructure/a2a/tck/upstream-213.patch` reproduit strictement la correction
de la PR officielle `#213`, commit
`8318fd843d4b69e3cb816b59192c8fd3f241c947`. Aucun autre test amont n'est modifié.

Le SUT de conformité utilise exactement l'image du runtime projet, avec un bean
activable uniquement par `AI_FACTORY_A2A_TCK_ENABLED=true`. Le script enferme le
SUT et le TCK dans un réseau Docker interne éphémère sans accès Internet. La
construction publique du TCK est effectuée avant cette phase et sans aucun
secret. Le module `sitecustomize.py` corrige uniquement la perte du chemin
`/a2a` provoquée par le client JSON-RPC alpha lorsqu'il résout `POST /`.

## Résultat archivé

- protocole : A2A `1.0` ;
- binding déclaré et testé : `JSONRPC` ;
- compatibilité : `100.0%` ;
- pytest : `68 passed`, `167 skipped`, `30 deselected`, `0 failed` ;
- Agent Card : `6/6` ;
- JSON-RPC : `66/93`, les 27 autres exigences étant hors des capacités
  déclarées ;
- commande : `./scripts/run-a2a-tck.sh --level must`.
