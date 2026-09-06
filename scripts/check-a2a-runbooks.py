#!/usr/bin/env python3
"""Check A2A runbook coverage and every SigNoz A2A alert link."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNBOOKS = ROOT / "docs/operations/runbooks"
REQUIRED = {
    "A2A-CARTE-INVALIDE.md",
    "A2A-AGENT-INDISPONIBLE.md",
    "A2A-TACHE-BLOQUEE.md",
    "A2A-CALLBACK-PERDU.md",
    "A2A-DIVERGENCE-ETAT.md",
    "A2A-CERTIFICAT-EXPIRE.md",
    "A2A-SATURATION.md",
    "ROLLBACK-A2A.md",
}
SECTIONS = {"## Détection", "## Confinement immédiat", "## Diagnostic", "## Rétablissement",
            "## Vérification et clôture", "## Escalade"}


def main() -> None:
    for name in REQUIRED:
        path = RUNBOOKS / name
        if not path.is_file():
            raise SystemExit(f"Missing A2A runbook: {name}")
        body = path.read_text(encoding="utf-8")
        missing = sorted(section for section in SECTIONS if section not in body)
        if missing:
            raise SystemExit(f"A2A runbook {name} lacks sections: {missing}")
    rules = json.loads((ROOT / "infrastructure/observability/signoz/rules/ai-factory.json")
                       .read_text(encoding="utf-8"))
    a2a_rules = [rule for rule in rules if rule.get("labels", {}).get("component") == "a2a"]
    for rule in a2a_rules:
        url = rule.get("annotations", {}).get("runbook_url", "")
        prefix = "/docs/operations/runbooks/"
        if not url.startswith(prefix) or not (RUNBOOKS / url.removeprefix(prefix)).is_file():
            raise SystemExit(f"Alert {rule.get('alert')} has no local runbook: {url}")
    print(f"A2A runbooks validated: {len(REQUIRED)} procedures, {len(a2a_rules)} alert links.")


if __name__ == "__main__":
    main()
