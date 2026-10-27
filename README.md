# Agentic Banking Lab

A curated source-view showcase of a multi-agent banking application built with
Java 25, Spring Boot, Spring AI, and a React workbench.

## License and permitted access

**Publicly visible does not mean open source or free to use.**

Copyright (c) 2026 Chinnadurai K. All rights reserved.
The [Proprietary Source View License](LICENSE) is preserved unchanged from the
original project. It permits viewing solely to evaluate whether to request a
separate written license. It does not grant permission to run, compile, test,
deploy, modify, redistribute, or use the software for AI/ML training.
Third-party materials remain subject to their own licenses and notices.

Obtain separate written permission from the copyright holder before any use
beyond the limited viewing permission. Public hosting is not an additional
license grant; applicable GitHub platform terms still apply.

## Showcase scope

- Multi-agent loan review with deterministic policy checks and evidence.
- Durable case workflows, recovery, and human-in-the-loop approvals.
- Incident response, grievance handling, and document workflows.
- Model gateway controls and a workbench for inspecting workflow activity.
- Synthetic, deterministic showcase scenarios that require no model API key.

This repository contains a selected code snapshot, not the private project's
Git history. Private documentation, architecture documents, personal learning
material, planning guides, and reports are intentionally excluded.

Markdown policy, runbook, and environmental/social framework resources are
retained because the application uses them. HTML application entry points and
the in-app usage guide are also retained.

## Limitations

This is a demonstration, not a production banking system or financial advice.
Use no real customer, applicant, payment, identity, credit, or other regulated
data. Synthetic showcase results do not establish live-model quality,
regulatory compliance, or production readiness.

Authentication and operational controls require production-specific review and
hardening. Keep provider credentials outside source control. The default local
mode and optional persistence profiles have different restart behavior.

## Setup for separately authorized users only

The following technical notes do not grant execution or testing rights.
They apply only after obtaining separate written permission.

The backend requires JDK 25; the frontend requires Node.js 24 and npm.
The Maven wrapper is included.

```sh
npm ci --prefix frontend
npm run build --prefix frontend
./mvnw clean verify
bash run-showcase.sh
```

The showcase binds to localhost and exposes
`http://localhost:8080/mission-control.html?showcase=1`. Its model responses and
data are synthetic; no model-network calls are made in showcase mode.

### Browser automation and automatic test data

For separately authorized browser testing, use Python 3.12 and an isolated
environment:

```sh
python3 -m venv .venv-browser
.venv-browser/bin/python -m pip install -r src/test/ui/browser/requirements.txt
.venv-browser/bin/python -m playwright install chromium
.venv-browser/bin/python src/test/ui/browser/demo.py --list
```

Browser demonstrations use synthetic data and a deterministic test model.
Normal provider-backed operation requires separately configured credentials.
