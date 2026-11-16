---
name: run-tests
description: Run the right tests and checks for the current change - JVM unit and contract tests, Room migrations, static checks, Compose UI tests, instrumented WebView tests and Playwright adapter tests. Use before committing or when asked to test.
---

# Run tests

## 1. Work out what changed

Run `git diff --name-only origin/main...HEAD` plus uncommitted changes, and map paths to modules.

## 2. Run the matching set

| Changed | Run |
| --- | --- |
| Kotlin in a library module | `./gradlew :<module>:unitTest :<module>:staticChecks` |
| Kotlin in `:app` | `./gradlew :app:unitTest :app:staticChecks` |
| `build-logic/`, `gradle/` or any `*.gradle.kts` | `./gradlew assembleDevDebug assembleProdRelease unitTest staticChecks` |
| Room entities or schema JSON | `./gradlew :core:data:unitTest` (includes migration tests) |
| `:feature:*` or `:core:ui` | That module's `unitTest`; Compose UI and screenshot tests arrive with T5.10 |
| `:portals` Kotlin | `./gradlew :portals:connectedDebugAndroidTest` (needs an emulator or device) |
| `portals/scripts/**` | `npm --prefix portals/scripts test` (Playwright; exists from task T3.11) |
| Prompts or default models | The golden-set script; report tier agreement and cost per job |
| Docs, `TASK.md` or CI scripts | `python3 scripts/ci/check_md_links.py`, `python3 scripts/ci/check_task_md.py`, `python3 scripts/ci/check_forbidden_patterns.py` |

`unitTest` and `staticChecks` exist in every module, so running them at the root covers everything. If a command named here does not exist yet, say so and stop. Do not invent commands.

## 3. Instrumented tests

These need an emulator or device. If none is available here, say so, run everything else, and list the instrumented tests that still need to run.

## 4. Report

For each command: passed or failed, the number of tests, and for failures the test name, the assertion and the likely cause. Never mark a failing or skipped test as passing, and never disable a test to get green.
