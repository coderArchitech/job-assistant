# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## What this is

An Android app that finds, scores and applies to jobs for one user, on their own phone, using their own Claude or OpenAI API key. Nothing runs on a server we own.

- Product requirements: [docs/PRD.md](docs/PRD.md)
- Engineering design: [docs/TECHNICAL_DESIGN.md](docs/TECHNICAL_DESIGN.md)
- Work breakdown: [TASK.md](TASK.md)

These three files are the source of truth. When code and docs disagree, either fix the code or update the docs in the same PR, and say which in the PR description.

## Working on a task

1. Every change maps to a task ID in `TASK.md` (for example `T1.07`). Put the ID in the branch name, the commit subject and the PR title.
2. Check the task's **Depends on** list first. If a dependency is not ticked, stop and say so instead of building around it.
3. Check the **Open decisions** table. If the task is blocked by an undecided `Dxx`, ask before choosing.
4. A task is done only when its **Done when** check passes. Then tick it in `TASK.md` in the same PR and link the PR.
5. Use `/implement-task <ID>` to follow this flow.

## Stack

Kotlin, Jetpack Compose with Material 3, single activity, MVVM, Hilt, coroutines and Flow, Room, Proto DataStore, WorkManager, OkHttp and kotlinx.serialization, Google Tink with Android Keystore, Android WebView with JavaScript adapters. `minSdk 29`, `targetSdk 36`.

## Modules

Dependencies point downward only. A `:feature:*` module never depends on another `:feature:*` module.

| Module | Holds |
| --- | --- |
| `:app` | Application, single Activity, navigation, Hilt wiring |
| `:feature:*` | Compose screens and ViewModels |
| `:core:ui` | Theme and shared components |
| `:core:model` | Pure Kotlin domain types (no Android imports) |
| `:core:data` | Room, DAOs, repositories, DataStore |
| `:core:security` | Vault, key management, redaction |
| `:core:network` | OkHttp clients, retry and backoff |
| `:core:config` | Signed adapter bundles and price table |
| `:ai` | `LlmProvider`, `Embedder`, Anthropic and OpenAI clients, prompts |
| `:sources` | Job-board clients, normalizer, deduplicator |
| `:portals` | WebView runtime, JS bridge, adapters, generic browser agent |
| `:otp` | SMS User Consent integration |
| `:email` | Google authorization and Gmail |
| `:agent` | Pipeline orchestration, caps, budget guard |
| `:work` | WorkManager workers, apply foreground service |

## Commands

These are created in milestone M0. Until then, say that a command does not exist yet rather than inventing one.

```bash
./gradlew assembleDevDebug          # build
./gradlew testDevDebugUnitTest      # unit, contract and Room tests (JVM)
./gradlew lint detekt ktlintCheck   # static checks
./gradlew connectedDevDebugAndroidTest   # instrumented tests (needs an emulator or device)
npm --prefix portals/scripts test   # Playwright tests for JS portal adapters
```

Run `/run-tests` to pick the right subset for what changed.

## Rules that are never broken

These come from the PRD and design. A PR that breaks one is not mergeable.

1. **No secrets in AI prompts.** Passwords, OTPs, cookies and API keys never enter an `LlmRequest`. The prompt guard must stay on every request path.
2. **No secrets in logs.** Use the redacting logger. Never log request bodies in release builds. Never log OTPs at all.
3. **Secrets live only in the vault.** Never in Room, DataStore, SharedPreferences, files, test fixtures or the repo. Test fixtures use obviously fake values.
4. **Applying never runs unattended.** Apply code runs only from the visible Agent at work screen. Background workers stop at "ready to apply".
5. **Never submit twice.** Write `SUBMITTING` before the click and `SUBMITTED` after confirmation. Never auto-retry a `SUBMITTING` record.
6. **Never bypass CAPTCHAs or bot checks.** Detect them and hand the page to the user.
7. **No LinkedIn automation.** No LinkedIn API calls, no LinkedIn pages in the WebView runtime.
8. **The generic browser agent never clicks the final submit** in Phase 1.
9. **Respect caps and the budget.** Every apply takes a cap slot; every AI call passes the budget guard first.
10. **No `READ_SMS`, no Accessibility API.** OTPs come only through the SMS User Consent API.

## Kotlin conventions

- Compose screens read state with `collectAsStateWithLifecycle()`. ViewModels expose `StateFlow` built with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`.
- Model screen state and results as sealed types. Use the typed results from the design (`LoginResult`, `ApplyResult`, `KeyCheck`, `LlmResult`) rather than `Result<T>`.
- Do not use `runCatching` around suspend calls: it swallows `CancellationException`. Catch specific exceptions, or rethrow cancellation.
- Inject dispatchers; never hard-code `Dispatchers.IO` in classes under test.
- Status changes go through repository transition methods (one transaction, expected-status precondition). Never `UPDATE` a status directly.
- No `!!` outside tests.
- Public types and functions in `:core:*`, `:ai`, `:portals` and `:agent` get a one-line KDoc.

## Testing expectations

| Change | Tests required |
| --- | --- |
| Domain logic (filters, dedupe, tiers, caps, budget) | JVM unit tests, table-driven where possible |
| Network client (job source or AI provider) | MockWebServer contract tests with recorded fixtures, including 401, 429, 5xx and malformed bodies |
| Room schema | DAO tests and a `MigrationTestHelper` test for every new version |
| Portal adapter JS | Playwright tests against saved HTML snapshots, plus the instrumented WebView harness |
| Compose screen | Compose UI test and light and dark screenshot tests |
| Prompt or default model | Golden-set run with the result in the PR description |

Never call a live AI provider, job board or portal from tests that run in CI. Live checks run separately and are opt-in.

## Docs

When behavior changes, update the matching section of `docs/TECHNICAL_DESIGN.md` in the same PR. When a decision `Dxx` is made, record it under "Open technical questions" and in `TASK.md`. Use `/update-docs` for this.

## Agents and skills in this repo

| Name | Kind | Use it to |
| --- | --- | --- |
| `android-dev` | Agent | Implement a `TASK.md` task end to end |
| `test-writer` | Agent | Write unit, contract, UI and Playwright adapter tests |
| `adapter-author` | Agent | Build or fix a portal adapter |
| `security-reviewer` | Agent | Review a diff against the rules above |
| `docs-keeper` | Agent | Keep the PRD, design and `TASK.md` in step with the code |
| `/implement-task` | Skill | Run the full task flow for one task ID |
| `/new-adapter` | Skill | Scaffold a portal adapter with manifest, snapshots and tests |
| `/run-tests` | Skill | Run the right tests for the current change |
| `/update-docs` | Skill | Sync docs and `TASK.md` with the current change |
