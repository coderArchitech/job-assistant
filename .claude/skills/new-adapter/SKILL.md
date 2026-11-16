---
name: new-adapter
description: Scaffold a portal adapter for the WebView runtime - JS bundle, manifest, HTML snapshots, Playwright tests and the instrumented harness case. Use as /new-adapter <portal-name>.
---

# New portal adapter

Argument: the portal name, for example `naukri` or `wellfound`.

## Steps

1. **Check scope.** Confirm the portal is in `TASK.md` or decision D12. If it is new, ask whether to add a task first. Refuse LinkedIn: it is never automated.
2. **Scaffold** `portals/scripts/adapters/<portal>/`:
   - `manifest.json`: `id`, `version` (start at `0.1.0`), `minAppVersion`, `origins` (the portal's hosts), `selectors`, `flows` (`login`, `search`, `details`, `apply`), `healthCheck` steps.
   - `adapter.js`: handlers for the bridge commands, following an existing adapter's structure.
   - `snapshots/`: one HTML file per page state - logged out, logged in, search results, job detail, apply form, confirmation, CAPTCHA or challenge, external redirect.
   - `tests/<portal>.spec.ts`: Playwright tests that load each snapshot, inject the bundle and assert classification, selectors and fill order.
3. **Capture snapshots.** Save pages from a test account. Remove names, emails, phone numbers, resume text and tokens before saving.
4. **Build the adapter** with the `adapter-author` agent, following its rules (stable selectors, no credential handling in JS, CAPTCHA hand-off, stop-before-submit).
5. **Add the harness case** in the `:portals` instrumented tests so the bundle also runs inside a real WebView.
6. **Register** the adapter in the portal templates (built-in) and the config host's bundle list.
7. **Run** `/run-tests` for the adapter and the `:portals` module.
8. **Review** with the `security-reviewer` agent.
9. **Docs.** Update the adapter list in `docs/TECHNICAL_DESIGN.md` and the matching task in `TASK.md`.

Report the page states covered, test results and anything that needs a live account to finish.
