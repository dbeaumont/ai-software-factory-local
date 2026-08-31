# MCP-028 — Clés d'idempotence

Les commandes sandbox utilisent la forme lisible et stable :

```text
<task_id>:<attempt_id>:<step>:<input_digest>
```

`input_digest` est le SHA-256 de `changes.patch` lorsqu'il existe, sinon le SHA-256 du commit source. La clé est créée une seule fois au début de l'opération et le décorateur de retry réutilise exactement la même map d'arguments.

Le serveur indexe les jobs par tâche, opération et clé. Un retry retourne le job existant. La réutilisation d'une même clé avec un digest différent échoue en conflit ; elle ne récupère jamais silencieusement un résultat produit pour une autre entrée.
