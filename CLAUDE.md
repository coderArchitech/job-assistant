# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## What this is

An Android app that finds, scores and applies to jobs for one user, on their own phone, using their own Claude or OpenAI API key. Nothing runs on a server we own.

- Product requirements: [docs/PRD.md](docs/PRD.md)
- Engineering design: [docs/TECHNICAL_DESIGN.md](docs/TECHNICAL_DESIGN.md)
- Work breakdown: [TASK.md](TASK.md)

These three files are the source of truth. When code and docs disagree, either fix the code or update the docs in the same PR, and say which in the PR description.

## Working on a task

1. Every change maps to a task ID in `TASK.md` (for example `T1.07`). Put the ID in the branch name, the commit subject and the PR title. Changes with no task (docs, chores, CI) start the PR title with a type instead: `docs:`, `chore:`, `ci:`, `build:`, `test:` or `fix:`.
2. Check the task's **Depends on** list first. If a dependency is not ticked, stop and say so instead of building around it.
3. Check the **Open decisions** table. If the task is blocked by an undecided `Dxx`, ask before choosing.
4. A task is done only when its **Done when** check passes. Then tick it in `TASK.md` in the same PR and link the PR.
5. Every task runs through `/implement-task <ID>`, even when the request is only "continue with T1.07" or "next task". That skill makes these steps mandatory: write (`android-dev`), test (`test-writer`, then `/run-tests`), review (`/code-review`, plus `security-reviewer` for sensitive changes), docs (`/update-docs`), then PR and CI.

### Automation that runs by itself

Project hooks in `.claude/settings.json` run without anyone asking:

| When | What runs | On failure |
| --- | --- | --- |
| After every Edit or Write | `scripts/hooks/after_edit.sh`: the safety rules on the changed file | Findings go back to Claude to fix at once |
| Before every `git push` | `scripts/hooks/pre_push.sh`: safety rules, `TASK.md` and docs checks, plus `./gradlew unitTest staticChecks` when code or build files changed | The push is blocked |

People get the same pre-push check with `git config core.hooksPath .githooks`. Set `JOBASSISTANT_SKIP_PREPUSH=1` only in an emergency; CI runs the same checks anyway.

## Stack

Kotlin, Jetpack Compose with Material 3, single activity, MVVM, Hilt, coroutines and Flow, Room, Proto DataStore, WorkManager, OkHttp and kotlinx.serialization, Google Tink with Android Keystore, Android WebView with JavaScript adapters. `minSdk 29`, `targetSdk 36`, `compileSdk 37`. Versions are in `gradle/libs.versions.toml`; the toolchain table is in the design's "Modules" section.

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

Needs JDK 21 and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in an untracked `local.properties`).

```bash
./gradlew assembleDevDebug                # build the dev app
./gradlew unitTest                        # JVM unit tests in every module
./gradlew staticChecks                    # Android Lint, detekt and ktlint in every module
./gradlew ktlintFormat                    # fix formatting
./gradlew deviceTest                      # instrumented tests in every module (needs an emulator or device)
```

`unitTest`, `staticChecks` and `deviceTest` are aggregate tasks registered by the convention plugins in `build-logic/`, so they cover a new module automatically. Playwright tests for the portal adapters (`npm --prefix portals/scripts test`) arrive with the adapter framework in task T3.11; until then that command does not exist.

Build variants: flavors `dev` (package suffix `.dev`, so it installs next to prod) and `prod`, each with `debug` and `release` (R8 shrinking). The config host URL per flavor is set in `gradle.properties` and is empty until task T3.10.

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

## Checks on every PR

`.github/workflows/pr-checks.yml` runs these; all must pass before merging.

| Check | Script or tool | Fix locally with |
| --- | --- | --- |
| PR title has a task ID | Regex in the workflow | Rename the PR (`T1.07: ...` or `docs: ...`) |
| Secret scan | gitleaks on the PR's commits | Remove the secret, rotate it, and rewrite the commit before merging |
| Safety rules | `scripts/ci/check_forbidden_patterns.py` | `python3 scripts/ci/check_forbidden_patterns.py` |
| `TASK.md` consistency | `scripts/ci/check_task_md.py` | `python3 scripts/ci/check_task_md.py` |
| Docs links and formatting | `scripts/ci/check_md_links.py`, markdownlint | `python3 scripts/ci/check_md_links.py` and `npx markdownlint-cli2@0.17.2 --fix` |

`.github/workflows/android.yml` adds the Android checks; they must pass too.

| Check | What it runs |
| --- | --- |
| Build, static checks and unit tests | All four variants (release with R8), `unitTest`, `staticChecks` |
| Instrumented tests (API 29, 34, 36) | `deviceTest` (every module's instrumented tests) on an emulator per API level |

Two more workflows run only on `main`: "Release bundle" (`release.yml`) builds the signed Play bundle when the upload-key secrets are set, and "Labels" (`labels.yml`) syncs `.github/labels.json`. Release signing and the GitHub settings that protect `main` are described in the design's "Release signing" and "Repository settings" sections. Never put keystores, passwords or signing values in the repo; `.gitignore` already excludes `*.jks`, `*.keystore` and `keystore.properties`.

A safety-rule match that is intentional (for example the LinkedIn host blocklist) needs `// ci-allow: <rule-id> (reason)` on that line, and a reviewer must agree with the reason. The safety rules also forbid `!!` in `src/main` code (rule `force-unwrap`), because detekt can only check that with full type analysis.

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
