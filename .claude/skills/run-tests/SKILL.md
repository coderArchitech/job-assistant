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
| Any Kotlin in a module | `./gradlew :<module>:testDevDebugUnitTest` |
| Any Kotlin | `./gradlew lint detekt ktlintCheck` (scoped to touched modules where possible) |
| Room entities or schema JSON | `./gradlew :core:data:testDevDebugUnitTest` including migration tests |
| `:feature:*` or `:core:ui` | Compose UI and screenshot tests for that module |
| `:portals` Kotlin | `./gradlew :portals:connectedDevDebugAndroidTest` (needs an emulator or device) |
| `portals/scripts/**` | `npm --prefix portals/scripts test` (Playwright) |
| Prompts or default models | The golden-set script; report tier agreement and cost per job |
| Gradle or build logic | `./gradlew assembleDevDebug` and the full unit test suite |

If the Gradle tasks or npm scripts do not exist yet (before milestone M0 is done), say so and stop. Do not invent commands.

## 3. Instrumented tests

These need an emulator or device. If none is available here, say so, run everything else, and list the instrumented tests that still need to run.

## 4. Report

For each command: passed or failed, the number of tests, and for failures the test name, the assertion and the likely cause. Never mark a failing or skipped test as passing, and never disable a test to get green.
