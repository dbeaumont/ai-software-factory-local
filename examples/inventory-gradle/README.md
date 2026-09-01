# Inventory Gradle demo

Minimal Java/Gradle repository used by the MCP context shadow campaign.

Current behavior:

- an in-memory inventory exposes the available quantity for a SKU;
- unknown SKUs return zero;
- negative stock updates are rejected.

The repository deliberately contains repository rules in `CONTRIBUTING.md`. The Planner must cite and respect them.
