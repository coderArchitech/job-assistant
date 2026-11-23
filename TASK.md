# Phase 1 Implementation Tasks

This file lists every task needed to build Phase 1 of the AI Job Search Agent, based on the [PRD](docs/PRD.md) and the [Technical Design](docs/TECHNICAL_DESIGN.md). The tasks are grouped into milestones in build order.

**How to use this file**

- Tick a task (`[x]`) when its "Done when" check passes, and link the PR next to it.
- Task IDs (`T1.01`) stay fixed so PRs, commits and issues can refer to them.
- **Depends on** lists tasks that must be finished first. Tasks with no dependency in a milestone can run in parallel.
- **Priority:** P0 must ship at launch. P1 ships in Phase 1 but may follow the first internal build.
- Each milestone ends with a **gate**: its exit criteria must pass before the next milestone counts as started.

## Milestones at a glance

| Milestone | Tasks | Gate |
| --- | --- | --- |
| [M0 Project setup](#m0-project-setup) | T0.01–T0.08 | Empty app builds and passes CI |
| [M1 Foundation](#m1-foundation) | T1.01–T1.17 | Keys validate on both providers; resume becomes a confirmed profile; SDK decision made |
| [M2 Discovery and scoring](#m2-discovery-and-scoring) | T2.01–T2.22 | 3+ sources deduplicated; 70% of jobs filtered before the LLM; tiers shown in Review |
| [M3 Portals and applying](#m3-portals-and-applying) | T3.01–T3.25 | Naukri and Greenhouse applied end to end; SMS OTP login works; sessions reused |
| [M4 Outreach and reporting](#m4-outreach-and-reporting) | T4.01–T4.15 | Gmail send with approval; report counts match; budget cap pauses runs |
| [M5 Hardening and beta](#m5-hardening-and-beta) | T5.01–T5.16 | Security review closed; Play policy pre-check passed; 20-user beta live |
| [Decisions](#open-decisions) | D01–D13 | Each decision recorded in the Technical Design |

---

## M0. Project setup

- [x] **T0.01 Create the Android project** (P0)
  Single-activity Kotlin app, Jetpack Compose with Material 3, `minSdk 29`, `targetSdk 36`, package name decided.
  _Done when:_ the empty app launches on an API 29 and an API 36 emulator.
  _Done in [#4](https://github.com/coderArchitech/job-assistant/pull/4). Verified by `MainActivityTest` in CI on Android 10 (API 29), 14 and 16 (API 36) emulators._

- [x] **T0.02 Set up the Gradle module structure** (P0) · Depends on T0.01
  Create the modules from the design: `:app`, `:core:ui`, `:core:model`, `:core:data`, `:core:security`, `:core:network`, `:core:config`, `:ai`, `:sources`, `:portals`, `:otp`, `:email`, `:agent`, `:work` and the `:feature:*` modules. Add a version catalog (`libs.versions.toml`) and convention plugins in `build-logic/`.
  _Done when:_ every module compiles, and a dependency check fails the build if a feature module depends on another feature module.
  _Done in [#4](https://github.com/coderArchitech/job-assistant/pull/4). Verified: a `:feature:home` → `:feature:jobs` dependency fails the build._

- [x] **T0.03 Add dependency injection** (P0) · Depends on T0.02
  Hilt in every module that needs it, with one test component.
  _Done when:_ a ViewModel gets a repository through Hilt in a test.
  _Done in [#4](https://github.com/coderArchitech/job-assistant/pull/4). Verified by `HiltWiringTest` (Robolectric)._

- [x] **T0.04 Add static checks** (P0) · Depends on T0.02
  Android Lint, detekt and ktlint with shared configs; warnings fail CI.
  _Done when:_ a deliberate lint error fails the build.
  _Done in [#4](https://github.com/coderArchitech/job-assistant/pull/4). Verified: deliberate Android Lint, detekt, ktlint and `force-unwrap` mistakes each fail the build._

- [ ] **T0.05 Set up CI** (P0) · Depends on T0.04
  GitHub Actions: lint, unit tests, a release build with R8, and instrumented tests on emulators at API 29, 34 and 36.
  _Done when:_ a PR shows all checks, and a failing test blocks merging.
  _Workflows added in [#4](https://github.com/coderArchitech/job-assistant/pull/4) and all checks run on PRs. Still open: make the checks required in branch protection (T0.08) so a failing test blocks merging._

- [ ] **T0.06 Define build types and flavors** (P0) · Depends on T0.01
  `debug` and `release` build types; `dev` and `prod` flavors (dev points at a test config host). HTTP logging only in debug.
  _Done when:_ both flavors install side by side on one device.

- [ ] **T0.07 Set up release signing** (P0)
  Play App Signing enrolled; the upload key is kept outside the repo, in CI secrets.
  _Done when:_ CI produces a signed release bundle.
  _Signing config and the "Release bundle" workflow added in [#6](https://github.com/coderArchitech/job-assistant/pull/6); a bundle signed with a throwaway key verified locally. Still open: the owner creates the upload key, adds the four secrets and enrolls in Play App Signing (design: "Release signing")._

- [ ] **T0.08 Set up the repo for collaboration** (P0)
  Branch protection on `main`, a PR template that asks for the task ID, CODEOWNERS, and an issue label per milestone.
  _Done when:_ a direct push to `main` is rejected.
  _PR template (in #3), CODEOWNERS, milestone labels and the label sync workflow added in [#6](https://github.com/coderArchitech/job-assistant/pull/6). Still open: the owner turns on branch protection for `main` (design: "Repository settings")._

---

## M1. Foundation

### Spikes and decisions

- [x] **T1.01 Spike: official AI SDKs on Android** (P0) · Depends on T0.02
  Try `com.anthropic:anthropic-java` and `com.openai:openai-java` on API 29 with R8: APK size, method count, startup time, and whether they work. Record the result as decision D01.
  _Done when:_ D01 is decided and recorded in the Technical Design.
  _Done in [#7](https://github.com/coderArchitech/job-assistant/pull/7). D01 recorded in the design ("Client libraries") with sizes and on-device timings from the spike branch._

### Core data and settings

- [x] **T1.02 Define the domain model** (P0) · Depends on T0.02
  Pure Kotlin types in `:core:model` for every entity in the design (`Profile`, `SearchPreset`, `Portal`, `Job`, `MatchResult`, `Draft`, `Decision`, `Application`, `OutreachMessage`, `Run`, `SourceCursor`, `UsageRecord`, `DailyReport`, `CompanyWatchlist`) and the status enums.
  _Done when:_ the types compile with no Android dependency.
  _Done in [#8](https://github.com/coderArchitech/job-assistant/pull/8). `:core:model` compiles as a Kotlin JVM module whose classpath holds only kotlin-stdlib, kotlinx-serialization-core and javax.inject; 12 unit tests cover validation, tiers, statuses and JSON round trips._

- [x] **T1.03 Create the Room database** (P0) · Depends on T1.02 · [#20](https://github.com/coderArchitech/job-assistant/pull/20)
  Entities, DAOs, JSON type converters, unique constraints (`Job` on source + externalId, `Application` on jobId), indexes on `dedupeHash` and `status`, and an exported schema.
  _Done when:_ DAO tests pass on an in-memory database and the schema JSON is committed.

- [x] **T1.04 Add migration testing** (P0) · Depends on T1.03 · [#22](https://github.com/coderArchitech/job-assistant/pull/22)
  `MigrationTestHelper` setup and a CI check that every schema version has a migration test.
  _Note from T1.03:_ Schemas are exported to `core/data/schemas/<db class>/<version>.json` by the Room Gradle plugin. `AgentDatabase.VERSION` is the single source of the version.
  _Done when:_ adding a schema version without a migration test fails CI.

- [x] **T1.05 Implement status transitions** (P0) · Depends on T1.03 · [#21](https://github.com/coderArchitech/job-assistant/pull/21)
  Repository methods that move a job between statuses in one transaction, with the expected current status as a precondition (`UPDATE … WHERE status = ?`).
  _Note from T1.03:_ `JobDao`, `ApplicationDao`, `OutreachMessageDao` and `RunDao` are insert and read only on purpose; add the guarded `UPDATE … WHERE status = ?` transitions here, and keep a plain `@Update` off these entities.
  _Done when:_ a test shows two concurrent transitions of the same job cannot both succeed.

- [x] **T1.06 Add settings storage** (P0) · Depends on T0.02 · [#10](https://github.com/coderArchitech/job-assistant/pull/10)
  Proto DataStore for thresholds, schedule, caps, selected models and feature toggles.
  _Done when:_ settings survive an app restart and have tested defaults.

### Security

- [x] **T1.07 Build the credential vault** (P0) · Depends on T0.02 · [#11](https://github.com/coderArchitech/job-assistant/pull/11)
  Tink AEAD (AES-256-GCM) with the keyset wrapped by an Android Keystore key (StrongBox where available). Ciphertexts stored in `vault.pb` (Proto DataStore), each bound to its slot name as associated data. API: `put`, `get`, `delete`, `wipe`.
  _Done when:_ round-trip tests pass on API 29 and 36, and a value copied to another slot fails to decrypt.

- [x] **T1.08 Configure backup exclusions** (P0) · Depends on T1.07 · [#13](https://github.com/coderArchitech/job-assistant/pull/13)
  `dataExtractionRules` (API 31+) and `fullBackupContent` (API 29–30) exclude `vault.pb`, `app_webview/` and `files/proofs/`.
  _Note from T1.06:_ Decide whether `settings.pb` is backed up (D03); if it is, reset `autoSendEmails` to false after a restore. Exclude `datastore/vault.pb` by exact path, never the whole `datastore/` directory.
  _Note from T1.07:_ **Blocks wiring the vault into the app:** no PR may add `:core:security` to `:app` or a feature module before this lands, because Auto Backup would include `vault.pb` today.
  _Done when:_ a `bmgr` backup and restore on both API levels contains no vault, cookies or proofs.

- [x] **T1.09 Add log redaction** (P0) · Depends on T0.02 · [#24](https://github.com/coderArchitech/job-assistant/pull/24)
  A logging tree that strips vault values, API keys, `Authorization`, `x-api-key` and `Cookie` headers. No request bodies logged in release.
  _Note from T1.06:_ Log, redacted, when `settings.pb` is reset after corruption or a stored settings value falls back to its default (T1.06).
  _Done when:_ a test logs a known secret and finds it redacted in the output.

### Networking and AI

- [x] **T1.10 Build the network layer** (P0) · Depends on T0.02 · [#23](https://github.com/coderArchitech/job-assistant/pull/23)
  OkHttp clients per destination group (AI, job sources, Google, config host), shared kotlinx.serialization config, retry with exponential backoff and jitter that honors `retry-after`, and a network security config with cleartext disabled.
  _Done when:_ MockWebServer tests cover retries on 429, 529 and 5xx and no retry on other 4xx.

- [x] **T1.11 Define the AI interfaces** (P0) · Depends on T1.02 · [#18](https://github.com/coderArchitech/job-assistant/pull/18)
  `LlmProvider`, `LlmRequest<T>`, `LlmResult<T>`, `OutputSchema<T>`, `Embedder` and the `AiTask` enum, as in the design.
  _Done when:_ a fake provider passes a contract test suite that both real providers will also run.

- [ ] **T1.12 Implement the Anthropic provider** (P0) · Depends on T1.01, T1.10, T1.11
  Official `anthropic-java` SDK (D01). Messages API with structured outputs (`output_config.format`) from a hand-written JSON Schema, never the class-derived overload; `GET /v1/models` for the key check and model list; prompt caching on the stable prefix; `stop_reason` handling, including `refusal`; no sampling parameters sent; usage (including cached tokens) mapped to `UsageRecord`.
  _Note from T1.11:_ Extend `LlmProviderContract` (`:ai` tests) and serve each `Scenario` from MockWebServer. Map `input_tokens`, `cache_read_input_tokens` and `cache_creation_input_tokens` straight into `TokenUsage`. Check whether structured outputs accept value keywords such as `minimum` and `maxLength`; the design keeps those limits in the Kotlin type.
  _Note from T1.10:_ Inject `@HttpClientFor(NetworkGroup.AI) OkHttpClient`. It already retries 429, 529 and 5xx and returns the last response, so map a final 429 or 529 to `RateLimited(retryAfter)` from its `Retry-After` header, a 401 or 403 to `InvalidKey`, and an `IOException` (including `BlockedRequestException`) to `NetworkError`. Do not add a second retry loop.
  _Note from T1.15:_ Wire the provider as `GuardedLlmProvider(…, secretScanner)`, outermost, in the Hilt module that provides it. Nothing constructs the guard yet, so until this is done requests are not scanned.
  _Note from T1.14:_ Provide the provider as `GuardedLlmProvider(MeteredLlmProvider(raw, ledger, budgets, prices, models, clock), scanner)`. `ModelResolver` must return the model the provider will really use for the task (the user's override or the default), and `UsageLedger`, `BudgetSource` and `Clock` need a Hilt binding (`UsageLedger` and `BudgetSource` are already bound in `:core:data`). Pass `UsageRunId(runId)` in the coroutine context of a run's calls.
  _Done when:_ the contract suite passes against recorded fixtures, and one live smoke test passes with a real key.

- [ ] **T1.13 Implement the OpenAI provider** (P0) · Depends on T1.01, T1.10, T1.11
  Thin client on OkHttp and kotlinx.serialization (D01). Structured Outputs with a strict JSON schema, `GET /v1/models` filtered by the remote allowlist, embeddings endpoint, and refusal and `finish_reason` handling.
  _Note from T1.11:_ Extend `LlmProviderContract` (`:ai` tests). OpenAI's `prompt_tokens` includes `cached_tokens`: set `TokenUsage.inputTokens` to the difference and `cacheReadInputTokens` to `cached_tokens`.
  _Note from T1.10:_ Inject `@HttpClientFor(NetworkGroup.AI) OkHttpClient` and the shared `Json`. Retries are already done by the client, so do not add a second loop; map the final status as in T1.12.
  _Note from T1.15:_ Wire the provider as `GuardedLlmProvider(…, secretScanner)`, outermost, as in T1.12.
  _Note from T1.14:_ Wrap the provider the same way as T1.12, with `MeteredLlmProvider` inside `GuardedLlmProvider`.
  _Done when:_ the contract suite passes against recorded fixtures, and one live smoke test passes with a real key.

- [x] **T1.14 Add usage metering and the budget guard** (P0) · Depends on T1.03, T1.11 · [#26](https://github.com/coderArchitech/job-assistant/pull/26)
  Write a `UsageRecord` for every call, priced from the price table. Before each call, refuse it if month-to-date spend plus a worst-case estimate would exceed the cap; then pause the agent and show the reason on Home.
  _Note from T1.06:_ Fail closed: if `SettingsRepository.settings` fails (it does after repeated I/O errors), pause AI calls rather than assume a budget.
  _Note from T1.11:_ `TokenUsage` splits input into uncached, cache-read and cache-write tokens (cache writes cost more than base input on Anthropic). `UsageRecord` in `:core:model` has only `cachedTokens`, so add a cache-write count before pricing. Return `LlmResult.Blocked(BUDGET_EXCEEDED)` for a refused call. `FakeLlmProvider` and `LlmProviderContract` sit in `:ai` tests; move them to a shared test-fixtures source set if the guard's tests live outside `:ai`.
  _Note from T1.03:_ `UsageRecordDao.sumCostMicrosSince(from)` gives month-to-date spend. The `usage_record` table has only `cachedTokens`; add the cache-write column with a schema version and migration test (T1.04) before pricing.
  _Note from T1.15:_ `BlockReason` now also has `SECRET_CHECK_UNAVAILABLE`. Put the budget guard inside `GuardedLlmProvider`, so a request is checked for secrets before anything else sees it.
  _Done when:_ a test with a $1 cap refuses the call that would cross it.

- [x] **T1.15 Add the prompt guard** (P0) · Depends on T1.07, T1.11 · [#25](https://github.com/coderArchitech/job-assistant/pull/25)
  Scan every outgoing AI request for current vault values and the last OTP; block the call and log a redacted security event on a match.
  _Note from T1.07:_ Read secrets with `Secret.useChars` where possible; `reveal()` needs `@RevealsSecret`. Use `Vault.slots()` to enumerate secrets to scan for.
  _Done when:_ a request containing a stored password is blocked in a test.

### Profile and app shell

- [ ] **T1.16 Build resume parsing and the profile** (P0) · Depends on T1.03, T1.12
  On-device text extraction from PDF and DOCX; AI structuring into skills, experience, education and roles; a confirm-and-edit screen; preferences, deal-breakers, answers bank and writing samples. `Profile.version` increments on every edit.
  _Done when:_ three sample resumes produce profiles a user can confirm without retyping more than one field each.

- [ ] **T1.17 Build the app shell and onboarding skeleton** (P0) · Depends on T0.03, T1.06, T1.12, T1.13
  Navigation graph, bottom navigation (Home, Jobs, Review, Portals, Settings), light and dark themes, and onboarding steps 1–4: consent, resume, preferences and context, AI provider (key entry, "Validate key", model picker per task with defaults). Edge-to-edge insets handled on every screen.
  _Note (owner, 2026-11-18):_ Depends on T1.12 and T1.13 because its Done when validates a real key, which needs a provider.
  _Done when:_ a new install reaches Home after validating a key on either provider.

**M1 gate:** keys validate on both providers; a resume becomes a confirmed profile; D01 is decided.

---

## M2. Discovery and scoring

### Job sources

- [x] **T2.01 Define the source contract and cursors** (P0) · Depends on T1.03, T1.10 · [#27](https://github.com/coderArchitech/job-assistant/pull/27)
  `JobSource` interface, `RawPosting` type and `SourceCursor` storage; per-host limit of 2 concurrent requests.
  _Done when:_ a fake source runs incrementally across two runs without reprocessing jobs.

- [ ] **T2.02 Build the Greenhouse client** (P0) · Depends on T2.01
  Confirm the job board API endpoint against current docs first, then fetch per watchlist company with content.
  _Note from T2.01:_ Implement `JobSource` with key `greenhouse:<board>`, use `@HttpClientFor(NetworkGroup.JOB_SOURCES)` (it limits each host to 2 requests at a time), skip entries that fail `RawPosting` validation, and throw `IOException` on a failed fetch rather than return an empty list. Set `RawPosting.atsType`.
  _Done when:_ fixture tests pass and a live fetch of one public board works.

- [ ] **T2.03 Build the Lever client** (P0) · Depends on T2.01
  `GET api.lever.co/v0/postings/{site}?mode=json` with `skip` and `limit` paging; EU host support.
  _Note from T2.01:_ Implement `JobSource` with key `lever:<site>`; the same rules as T2.02 apply.
  _Done when:_ fixture tests pass and a live fetch of one public board works.

- [ ] **T2.04 Build the Ashby client** (P0) · Depends on T2.01
  Confirm the posting API endpoint first; include compensation.
  _Done when:_ fixture tests pass and a live fetch of one public board works.

- [ ] **T2.05 Build the RemoteOK client** (P0) · Depends on T2.01
  Skip the legal-notice element; show the required credit and link back in the UI.
  _Done when:_ fixture tests pass, and Job detail shows the source credit.

- [ ] **T2.06 Build the Remotive client** (P0) · Depends on T2.01
  Confirm the endpoint and the allowed request rate first; enforce that rate.
  _Done when:_ fixture tests pass, and the client never exceeds the documented rate.

- [ ] **T2.07 Build the We Work Remotely RSS client** (P0) · Depends on T2.01
  XML pull parser over the category feeds.
  _Done when:_ fixture tests pass for at least two feeds.

- [ ] **T2.08 Build the Hacker News "Who is hiring" source** (P0) · Depends on T2.01, T1.12
  Find the monthly thread through the Algolia HN API, fetch its comments, pre-filter with regex (a role keyword and either remote or the user's location, per D10), send at most 100 comments a month to the scoring model, and extract postings with it.
  _Done when:_ one month's thread yields structured postings at a measured, logged cost.

- [ ] **T2.09 Build the company watchlist** (P0) · Depends on T1.03
  CRUD screen for companies with ATS type and board slug, and a "check board" button that validates the slug.
  _Done when:_ a user adds a Greenhouse, a Lever and an Ashby company and each check passes.

### Normalization and deduplication

- [ ] **T2.10 Build the normalizer** (P0) · Depends on T2.01
  HTML to plain text (capped at 8,000 characters for prompts); work mode (Remote, Hybrid, Onsite, Unknown); region and work-authorization limits; salary ranges, including Indian formats such as "12–18 LPA"; company-name normalization.
  _Note from T2.01:_ The pipeline calls `SourceSync.fetchNew`, stores the new postings as jobs (normalizing and deduplicating them), and only then calls `SourceSync.commit`. Store each posting with `Job.source` equal to the source's `key`.
  _Done when:_ a table-driven test of at least 50 real postings passes.

- [ ] **T2.11 Build the deduplicator** (P0) · Depends on T2.10
  Three steps: same source and external ID; same canonical URL; same `dedupeHash` of company and title. Keep the ATS copy as primary and store the others as aliases.
  _Done when:_ a fixture set with known cross-source duplicates collapses to the expected count.

- [ ] **T2.12 Expand search context into keywords** (P0) · Depends on T1.12, T1.06
  Once per preset per day, turn the free-form context into include and exclude keywords, cached on the context hash.
  _Done when:_ editing the context refreshes the keywords, and an unchanged context makes no new AI call.

- [ ] **T2.13 Build search presets** (P0) · Depends on T1.06, T1.17
  Preset CRUD (name, keywords, portals, context up to 2,000 characters, filters, schedule) and a one-off context field for manual runs.
  _Done when:_ two presets with different schedules are saved and listed.

### Scoring

- [ ] **T2.14 Implement hard filters** (P0) · Depends on T2.10
  Deal-breakers, seniority from the title, work mode, and region or authorization limits. A failed job gets score 0 and the rule's name as its reason.
  _Done when:_ a labeled set shows every "remote, US only" posting filtered for an India-based profile.

- [ ] **T2.15 Add the on-device embedder** (P0) · Depends on D05, T1.11
  The chosen on-device model behind the `Embedder` interface; the OpenAI embedder used when an OpenAI key is present; the embedder ID stored with the profile vector.
  _Done when:_ switching embedder re-embeds the profile automatically.

- [ ] **T2.16 Implement the embedding floor** (P0) · Depends on T2.15
  Cosine similarity between the profile vector and job vector; jobs below the floor (starting at 0.30) become Low match with no LLM call.
  _Done when:_ the floor is tuned on the golden set (T2.19) and recorded in settings defaults.

- [ ] **T2.17 Implement LLM scoring** (P0) · Depends on T1.12, T1.13, T1.14
  The scoring prompt (rubric, profile, context, the last 10 decisions, then the job) with the score JSON schema; tier computed in the app from score and thresholds; score cache keyed on job, profile version, context hash and model.
  _Done when:_ changing a threshold re-tiers stored jobs with no AI calls (BR4).

- [ ] **T2.18 Record decisions as feedback** (P0) · Depends on T1.03
  Approve and reject decisions saved as `Decision` rows and used as examples in the next run's scoring prompt.
  _Done when:_ a new run's prompt contains the latest decisions.

- [ ] **T2.19 Build the scoring golden set** (P0) · Depends on T2.17
  About 50 labeled jobs, including the BR5 case ("no service companies"); a script that reports tier agreement and cost per job.
  _Done when:_ the report runs in CI on prompt changes and shows the BR5 case moving as expected.

### Pipeline and screens

- [ ] **T2.20 Build the background pipeline** (P0) · Depends on T2.01, T2.17, T1.05
  `DiscoverWorker` and `ScoreWorker` (batches of 25 that re-enqueue), a unique periodic request per preset (`run:<presetId>`), a unique one-time chain per tick (`KEEP`), network, charging and battery constraints, "Run now" as expedited work, a `Run` record with per-stage counts, and the "new matches" notification.
  _Done when:_ a run of 200 jobs finishes in under 15 minutes on Wi-Fi and at least 70% of jobs are removed before the LLM.

- [ ] **T2.21 Build the Jobs screen** (P0) · Depends on T2.17, T1.17
  Job cards with score, tier, matched and missing skills and reason; filter chips (High, Partial, All, source, work mode, posted date, salary); search; Job detail screen.
  _Done when:_ the High and Partial chips filter correctly (BR4).

- [ ] **T2.22 Build the Review screen** (P0) · Depends on T2.21, T2.18
  Swipe or tap to approve or reject, bulk approve for High match, and a draft preview area (filled by T3.03).
  _Done when:_ approving a job moves it to Approved and records a decision.

**M2 gate:** at least 3 sources deduplicated; at least 70% of jobs filtered before the LLM; tiers shown in Review.

---

## M3. Portals and applying

### Tailoring

- [ ] **T3.01 Build the fact sheet** (P0) · Depends on T1.16
  Flatten the profile into numbered facts (roles, dates, skills with years, achievements, CTC, notice period).
  _Done when:_ every profile field appears as exactly one fact.

- [ ] **T3.02 Generate tailored drafts** (P0) · Depends on T3.01, T1.12
  Cover letter (up to 250 words), recruiter email (up to 120 words), LinkedIn note (configurable length) and form answers. The output schema returns each sentence with the fact IDs it uses.
  _Done when:_ drafts are stored as `Draft` rows for a sample of 20 jobs.

- [ ] **T3.03 Add the claim check** (P0) · Depends on T3.02
  Flag any sentence without facts, and any number, company or skill not found in the cited facts; highlight flagged sentences in the draft.
  _Done when:_ the tailoring golden set (20 cases) shows zero unflagged unsupported claims.

- [ ] **T3.04 Map form questions to the answers bank** (P0) · Depends on T2.15, T1.12
  Embedding match, then a confirmation by the scoring model; no confident match sets `NeedsInput`; the user's answer is saved back to the bank.
  _Done when:_ the 10 most common Naukri questions map correctly for a test profile.

- [ ] **T3.05 Build the tailor worker** (P0) · Depends on T3.02, T2.20
  Eager tailoring for High match in the background; lazy tailoring for Partial match after approval; drafts editable in Review and Job detail and marked `editedByUser`.
  _Note from T1.05 (D13):_ Writing drafts must not change the job's status. A High-match job stays `SCORED` until the user approves it; the worker moves a job to `READY` (via `JobRepository.transition`) only from `APPROVED`.
  _Done when:_ High-match drafts are ready in Review after a background run.

### WebView runtime

- [ ] **T3.06 Build the WebView runtime** (P0) · Depends on T0.02
  `AgentWebView` with hardened settings (no file or content access, Safe Browsing on, mixed content blocked, no `addJavascriptInterface`), a navigation allowlist per portal, and LinkedIn hosts always blocked.
  _Done when:_ a test page that tries to navigate off the allowlist is stopped.

- [ ] **T3.07 Build the JS bridge** (P0) · Depends on T3.06
  `addWebMessageListener` restricted to portal origins; `addDocumentStartJavaScript` for adapter injection; typed commands (`query`, `click`, `type`, `select`, `upload`, `waitFor`, `snapshot`); file upload through `onShowFileChooser`; random 1.5–4 s pacing between actions.
  _Done when:_ a page from another origin cannot send bridge messages.

- [ ] **T3.08 Isolate sessions per portal** (P0) · Depends on T3.06
  One WebView profile per portal through `ProfileStore`, with a shared `CookieManager` fallback where multi-profile is unsupported; per-portal wipe.
  _Done when:_ wiping one portal leaves another portal's session logged in.

- [ ] **T3.09 Build the config host client** (P0) · Depends on T1.10, D07
  Fetch adapter bundles and the price table; verify Ed25519 signatures against the two public keys in the app (current and next, D07); fall back to the last good or bundled version; respect `minAppVersion`.
  _Note from T1.10:_ Use `@HttpClientFor(NetworkGroup.CONFIG)`. It reaches only the host in `BuildInfo.configHost`, so a blank host means every call fails with `BlockedRequestException`; treat that as "config host not set".
  _Note from T1.14:_ Implement `PriceTable` (in `:ai`) from the signed price table, with the user's edits. Models it does not list are priced at `FallbackPricing.PRICE`, so list every model the app offers.
  _Done when:_ a tampered bundle is rejected and the bundled version is used.

- [ ] **T3.10 Set up the config host** (P0) · Depends on D07
  Static hosting for signed bundles and the price table; a signing script the owner runs offline with the private key (D07), never in CI; a release, rollback and key rotation procedure. No user identifiers in requests.
  _Done when:_ a bundle is published, rolled back, and the app follows both.

### Portal adapters

- [ ] **T3.11 Build the adapter framework** (P0) · Depends on T3.07, T3.09
  `ScriptedPortalAdapter` implementing `PortalAdapter` (`login`, `search`, `getDetails`, `apply`, `healthCheck`); the manifest format (version, selectors, flows, health-check steps, `minAppVersion`).
  _Done when:_ a sample adapter passes the adapter test harness (T3.12).

- [ ] **T3.12 Build the adapter test harness** (P0) · Depends on T3.11
  Saved HTML snapshots served through `WebViewAssetLoader` in instrumented tests; assertions on selectors, fill order and page classification.
  _Done when:_ a broken selector fails the harness in CI.

- [ ] **T3.13 Build the Naukri adapter** (P0) · Depends on T3.11, T3.12
  Login, search, job details, direct apply with questionnaire, health check.
  _Done when:_ an end-to-end apply on a test account completes in stop-before-submit mode.

- [ ] **T3.14 Build the Instahyre adapter** (P0) · Depends on T3.11, T3.12
  Login, search, job details, "Interested" flow, health check.
  _Done when:_ an end-to-end "Interested" completes on a test account.

- [ ] **T3.15 Build the Greenhouse and Lever form adapters** (P0) · Depends on T3.11, T3.12
  Hosted application forms, including resume upload and custom questions.
  _Done when:_ a Greenhouse form is filled end to end on a test posting (BR1).

- [ ] **T3.16 Detect CAPTCHAs and redirects** (P0) · Depends on T3.11
  Recognize challenge iframes and pages and hand the page to the user (never solve them). Detect Workday and other unsupported redirects and move the job to "Apply manually" with answers ready to copy.
  _Done when:_ saved challenge and redirect pages are classified correctly in the harness.

### Login and OTP

- [ ] **T3.17 Implement portal login** (P0) · Depends on T3.08, T1.07
  Check a logged-in marker first; otherwise fill credentials from the vault through the bridge. Results: `LoggedIn`, `OtpNeeded`, or `Failed` with a reason. Two bad-credential failures in a row disable the portal.
  _Note from T1.07:_ Handle `VaultRead.Unreadable` (ask the user to re-enter) differently from `VaultException` (retry later); never count either as a bad-credential login failure.
  _Done when:_ a second run reuses the session without touching credentials.

- [ ] **T3.18 Read OTPs from SMS** (P0) · Depends on T3.17
  SMS User Consent API: start the listener before triggering the OTP, launch the consent intent, extract a 4–8 digit code, type it through the bridge and clear it from memory. Exported receiver with the Play services permission. Timeout ends the login as `Failed(OTP_TIMEOUT)`. A background notification ("Naukri needs an OTP") opens Agent at work.
  _Note from T1.15:_ Register the last code with `SecretRegistry.register("otp", chars)` as soon as it is read, and unregister it when it is used or expires. The prompt guard and the log redaction both read it from there; nothing registers an OTP yet.
  _Done when:_ an OTP login completes without typing (BR6) on a real device.

- [ ] **T3.19 Build the portal manager** (P0) · Depends on T3.17, T1.07
  Portals screen: built-in templates (Naukri, Instahyre), custom portals (name, login URL, search URL), credentials, login method, enabled toggle, caps, auto-submit toggle (disabled until 20 manual submissions on that portal, with the count shown, per D11), and "Test connection". Onboarding step 5 and the per-portal terms-of-service acceptance.
  _Done when:_ a user adds a custom portal and "Test connection" reports the right result.

### Applying

- [ ] **T3.20 Implement daily caps** (P0) · Depends on T1.03
  Transactional cap slots per portal (default 10) and overall (default 25), reset at local midnight.
  _Done when:_ a test cannot take an eleventh slot on one portal in a day.

- [ ] **T3.21 Build the apply session** (P0) · Depends on T3.13, T3.15, T3.16, T3.18, T3.20
  The apply flow from the design: take a cap slot, ensure a session, classify the page, fill, check for CAPTCHAs, stop before submit by default (or write `SUBMITTING`, click and confirm in auto-submit mode, which a portal allows only after 20 manual submissions, per D11), save a proof screenshot, write `SUBMITTED`. A `SUBMITTING` record left by a crash goes to "Check manually" and is never retried.
  _Note from T1.05:_ Settle a live attempt with `ApplicationRepository.markSubmitted`, `markFailed` or `markCheckManually`, passing the attempt ID. Change the application and its job in one `TransactionRunner.run { … }`, calling `requireMoved()` on each result. Call `recoverInterruptedSubmissions()` once at process start, before any session can begin. A failed application is final, so retrying a failed apply needs a new decision.
  _Done when:_ killing the app mid-submit never produces a second submission in 20 trials.

- [ ] **T3.22 Build the apply foreground service** (P0) · Depends on T3.21, D04
  Foreground service of type `specialUse` (D04) with `FOREGROUND_SERVICE_SPECIAL_USE` and a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` describing the visible apply session, started only from the visible screen, with a progress notification; stops itself when the session ends, and in `onTimeout()` if the platform ever calls it.
  _Done when:_ a session survives a brief switch to another app and pauses after the current step.

- [ ] **T3.23 Build the Agent at work screen** (P0) · Depends on T3.21
  Live portal view, step log, Stop button, Submit highlight in stop-before-submit mode, `NeedsInput` prompts inline, and an "Apply approved" entry point from Home and the notification.
  _Done when:_ a user applies to three approved jobs in one session.

- [ ] **T3.24 Build the generic browser agent (Beta)** (P1) · Depends on T3.07, T1.12, T1.14
  Simplified DOM snapshot (password fields as `{{password}}`), one action per step as structured output, Kotlin validation of every action, placeholder substitution only at execution, a 40-step cap, its own daily budget, and no final submit.
  _Done when:_ it fills a form on a test custom portal and stops at "ready for user".

- [ ] **T3.25 Add adapter health checks** (P0) · Depends on T3.11, T2.20
  `healthCheck()` before each run; a failure marks the adapter degraded, skips that portal, saves a screenshot and shows an alert on Home.
  _Done when:_ breaking a selector in a test bundle produces the Home alert and other portals still run.

**M3 gate:** Naukri and Greenhouse applied end to end; SMS OTP login works; sessions reused.

---

## M4. Outreach and reporting

### Gmail and outreach

- [ ] **T4.01 Start Gmail OAuth verification** (P1) · Start during M2, since Google's review takes time
  Google Cloud project, OAuth consent screen, `gmail.send` scope, privacy policy URL, and verification request.
  _Done when:_ Google approves the app for `gmail.send`.

- [ ] **T4.02 Implement Google authorization** (P1) · Depends on T0.02
  Identity `AuthorizationClient` with only `gmail.send`; no stored refresh token; silent re-authorization in the background, with a "reconnect" notification when it fails. Onboarding step 6.
  _Note from T1.10:_ Use `@HttpClientFor(NetworkGroup.GOOGLE)`, which reaches `accounts.google.com` and `*.googleapis.com` only. Add a host to `HostPolicy` if Google needs another one, with a test.
  _Done when:_ a background send gets a token silently after first consent.

- [ ] **T4.03 Send recruiter emails** (P1) · Depends on T4.02, T3.02
  Base64url MIME messages through `users.messages.send`; approval by default, optional auto-send; cap of 10 a day; opt-out line on every email; `gmailThreadId` stored.
  _Done when:_ an approved email arrives in a test inbox with the opt-out line.

- [ ] **T4.04 Add follow-ups** (P1) · Depends on T4.03
  `FollowUpWorker` drafts a follow-up for emails with no recorded reply after 7 days, threaded with `In-Reply-To` and `References`, and notifies the user.
  _Done when:_ a follow-up lands in the same thread as the original.

- [ ] **T4.05 Track replies manually** (P1) · Depends on T1.05
  "Mark replied" and status updates from the Outreach and Applications screens.
  _Done when:_ marking a reply moves the job to Replied and counts in the report.

- [ ] **T4.06 Add LinkedIn drafts** (P1) · Depends on T3.02
  "Copy and open": copy the draft and open the recruiter's profile URL with `ACTION_VIEW`. No LinkedIn API or WebView automation.
  _Done when:_ the LinkedIn app opens on the profile with the draft on the clipboard.

- [ ] **T4.07 Build the Outreach screen** (P1) · Depends on T4.03, T4.06
  Pending, sent and replied tabs; approve and edit emails; LinkedIn drafts with one-tap copy and open.
  _Done when:_ every outreach state is visible and actionable.

### Reporting and tracking

- [ ] **T4.08 Build the Applications screen** (P0) · Depends on T3.21
  Status pipeline, proof screenshots, "Check manually" and "Apply manually" lists, manual status updates.
  _Note from T1.05:_ Manual status changes go through `JobRepository.transitionManually` (any shown status; from `SUBMITTING` only Applied or Failed). Resolve "Check manually" with `ApplicationRepository.confirmSubmittedManually` or `markFailedManually`, in one transaction with the job's change.
  _Done when:_ every status from Found to Offer can be reached and edited by hand.

- [ ] **T4.09 Build the daily report** (P0) · Depends on T4.08, T1.14
  `ReportWorker` at the chosen time (default 9:00 PM IST) using the same DAO queries as the Applications and Outreach screens; `DailyReport` row; notification on its own channel.
  _Done when:_ report counts match the Applications screen on 3 consecutive days (BR3).

- [ ] **T4.10 Build the Report screen** (P0) · Depends on T4.09
  Today's report, a 30-day Vico chart of applications and replies, and CSV export through `ACTION_CREATE_DOCUMENT`.
  _Done when:_ an exported CSV opens in a spreadsheet with correct columns.

- [ ] **T4.11 Add the email digest** (P1) · Depends on T4.09, T4.02
  Optional digest sent to the user's own address through `gmail.send`.
  _Done when:_ the digest arrives at the set time when enabled.

- [ ] **T4.12 Build the Home screen** (P0) · Depends on T4.09, T3.25
  Counts by status, next run estimate, "Run now", items needing attention (OTP, `NeedsInput`, degraded adapters, budget pause), AI cost today.
  _Note from T1.14:_ Show why AI work stopped when a call returns `LlmResult.Blocked`: `BUDGET_EXCEEDED` (the monthly cap or the browser agent's daily budget), `BUDGET_UNAVAILABLE` (settings or the database could not be read) or `SECRET_IN_PROMPT`. The pipeline runner pauses the agent on the first two.
  _Done when:_ every alert type in the design appears on Home in a test.

- [ ] **T4.13 Build the usage meter** (P0) · Depends on T1.14
  Tokens and cost per day and per run, monthly budget setting, and an editable price table.
  _Done when:_ per-run cost is visible (BR2) and the budget cap pauses runs.

- [ ] **T4.14 Finish scheduling settings** (P0) · Depends on T2.20
  Schedule (3, 6 or 12 hours, or manual), Wi-Fi or any network, charging only; onboarding step 7 (schedule, caps, first run).
  _Done when:_ changing the schedule updates the periodic work without duplicates.

- [ ] **T4.15 Add the run log** (P0) · Depends on T2.20
  Per-run, per-step results and errors from `Run` records.
  _Done when:_ a failed step shows its error and screenshot in the log.

**M4 gate:** Gmail send works with approval; report counts match; the budget cap pauses runs.

---

## M5. Hardening and beta

### Security and privacy

- [ ] **T5.01 Add the app lock** (P1) · Depends on T1.17
  Optional biometric or device-PIN lock on app open and on viewing credentials; `FLAG_SECURE` on credential screens.
  _Done when:_ credentials cannot be viewed or screenshotted without authentication.

- [ ] **T5.02 Implement "Delete all data"** (P0) · Depends on T1.07, T3.08, T4.02
  Cancel all work, delete the database, `vault.pb` and Keystore aliases, cookies, web storage and WebView profiles, resume, proofs and caches; revoke Google authorization; clear DataStore; restart into onboarding.
  _Note from T1.07:_ Call `Vault.wipe()`; deleting `vault.pb` directly lets the running vault write its cached keyset back.
  _Done when:_ an inspection of app storage after the wipe finds no user data.

- [ ] **T5.03 Add consent records** (P0) · Depends on T1.17
  Versioned consent notice at onboarding (data on device, AI sharing, portal terms, SMS consent, Gmail scope) stored with a timestamp.
  _Done when:_ a changed notice version asks for consent again.

- [ ] **T5.04 Run the security review** (P0) · Depends on all M1–M4 security tasks
  Static scan, manual review of the vault, backup exclusions, log redaction, prompt guard and bridge origin rules; fix every high finding.
  _Done when:_ no open high or critical findings remain.

### Platform and Play policy

- [ ] **T5.05 Add the battery optimization step** (P0) · Depends on T1.17
  Onboarding step that opens the battery optimization settings with instructions per brand (Xiaomi, Oppo, Vivo, Realme, Samsung); no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
  _Done when:_ scheduled runs fire on time for 3 days on one Xiaomi and one Samsung device.

- [ ] **T5.06 Write the privacy policy and disclosures** (P0)
  Privacy policy page; in-app prominent disclosures before collecting portal credentials and before AI data sharing.
  _Done when:_ the policy URL is live and linked in the app and Play listing.

- [ ] **T5.07 Complete the Play declarations** (P0) · Depends on T3.22, T5.06
  Data safety form; foreground service type declaration with a demo video; confirmation that remotely updated WebView JavaScript is allowed.
  _Done when:_ the Play pre-review passes with no policy warnings.

- [ ] **T5.08 Check accessibility** (P0)
  TalkBack labels, 48dp touch targets, dark and light themes, and tablet layouts on every screen.
  _Done when:_ automated accessibility checks pass and a TalkBack walkthrough completes onboarding.

- [ ] **T5.09 Measure performance and battery** (P0)
  Run time for 200 jobs on Wi-Fi, share of jobs filtered before the LLM, and battery use over a day with a 6-hour schedule.
  _Done when:_ under 15 minutes, at least 70%, and under 5% battery per day.

### Testing

- [ ] **T5.10 Add UI and screenshot tests** (P0)
  Compose UI tests and light and dark screenshot tests for every screen.
  _Done when:_ CI runs them on every PR.

- [ ] **T5.11 Set up live checks** (P0)
  A physical device with test accounts runs daily adapter health checks and one end-to-end apply per built-in portal, at low volume.
  _Done when:_ the daily results are posted where the team sees them.

- [ ] **T5.12 Run the acceptance tests** (P0)
  BR1–BR6 scripts on Android 10, 14 and 16, including one Xiaomi or Samsung device.
  _Done when:_ all six pass on all three versions.

### Beta

- [ ] **T5.13 Add on-device analytics** (P1)
  On-device metrics (run success per portal, adapter failure reasons, approval rate by tier, apply success, reply rate by source, AI cost per applied job) and on-device crash logs, with a user-initiated export of both; nothing is sent off the device (D08).
  _Done when:_ metrics and crash logs are visible in a debug screen, the export produces a file the user can share, and no analytics or crash data leaves the device on its own.

- [ ] **T5.14 Write the beta guide** (P0)
  Setup steps, known limits, how to report problems, and portal account risks.
  _Done when:_ a new tester completes onboarding using only the guide.

- [ ] **T5.15 Launch the closed beta** (P0) · Depends on T5.04, T5.07, T5.12
  Closed testing track with 20 users; a feedback channel; staged rollout plan for production.
  _Done when:_ 20 testers have installed and completed at least one run.

- [ ] **T5.16 Triage beta feedback** (P0) · Depends on T5.15
  Daily triage of crashes, adapter failures and feedback during the beta.
  _Done when:_ no open blocker remains for production rollout.

**M5 gate:** security review closed; Play policy pre-check passed; 20-user closed beta live.

---

## Open decisions

Record each decision in the Technical Design's "Open technical questions" section when it is made.

| ID | Decision | Needed by | Blocks |
| --- | --- | --- | --- |
| D01 | Official AI SDKs or thin Retrofit clients. **Decided in [#7](https://github.com/coderArchitech/job-assistant/pull/7):** `anthropic-java` for Anthropic, thin OkHttp client for OpenAI, hand-written JSON schemas | Start of M1 | T1.12, T1.13 |
| D02 | Encrypt the Room database with SQLCipher. **Decided 2026-11-18 (owner): no SQLCipher.** `agent.db` holds no secrets, and Android's file-based encryption already protects app storage at rest | End of M1 | T1.03 |
| D03 | Cloud backup of non-secret data, or no backup. **Decided 2026-11-18 (owner): back up non-secret data** (`agent.db`, `settings.pb`); exclude `datastore/vault.pb`, `app_webview/` and `files/proofs/` by exact path; after a restore, auto-send is off (its consent is stored only in `no_backup/`) and secrets are asked for again. Follow-up (owner): cloud backup only when end-to-end encrypted (`disableIfNoEncryptionCapabilities="true"`) | End of M1 | T1.08 |
| D04 | Foreground service type: `dataSync` or `specialUse`. **Decided 2026-11-18 (owner): `specialUse`** with `FOREGROUND_SERVICE_SPECIAL_USE` and a subtype description of the visible apply session; filling a portal form in a WebView is not data sync. Confirm in the Play pre-review (T5.07) | Start of M3 | T3.22 |
| D05 | On-device embedding model, bundled or downloaded, size budget. **Decided 2026-11-18 (owner): bundle a small quantized sentence-embedding model in the APK, at most 25 MB**, run with LiteRT or MediaPipe; the exact model is picked in T2.15 on the golden set. A downloaded larger model stays possible behind `Embedder` | Start of M2 | T2.15 |
| D06 | Request `gmail.readonly` for reply tracking and email OTP, or ship manual marking only. **Decided 2026-11-18 (owner): manual marking only in Phase 1;** only `gmail.send` is requested. Email OTPs are pasted from a notification. Ask Google separately which review on-device-only `gmail.readonly` needs | Start of M4 | T4.05 |
| D07 | Who holds the config-host signing key and how it rotates. **Decided 2026-11-18 (owner): the owner holds the Ed25519 private key offline** and signs each release by hand; CI never holds it. The app embeds two public keys (current and next), so a rotation ships the next key in an app update before it is used | Start of M3 | T3.09, T3.10 |
| D08 | Crash reporting: on-device export only, or opt-in Crashlytics. **Decided 2026-11-18 (owner): on-device logs with a user-initiated export only;** no Crashlytics and nothing sent off the device | Start of M5 | T5.13 |
| D09 | Use Message Batches for background scoring. **Decided 2026-11-18 (owner): no batches in Phase 1;** score synchronously. Scoring is about $0.06 a day, so halving it is not worth results arriving a run late. Revisit if measured scoring cost is much higher | End of M2 | T2.17 |
| D10 | Hacker News regex pre-filter strictness. **Decided 2026-11-18 (owner): strict:** a comment needs a role keyword and (remote or the user's location); at most 100 comments a month go to the model. Tune both on one real thread in T2.08 | End of M2 | T2.08 |
| D11 | Auto-submit at launch, or only after 20 manual approvals (PRD). **Decided 2026-11-18 (owner): only after 20 manual submissions on that portal;** until then the per-portal auto-submit toggle is disabled with the count shown | End of M3 | T3.19, T3.21 |
| D12 | Next portals for dedicated adapters (Wellfound, Cutshort, Hirist, Indeed India) (PRD). **Decided 2026-11-18 (owner): Cutshort and Hirist first** (Indian tech jobs); Wellfound and Indeed India later | End of M3 | Phase 2 |
| D13 | Whether background tailoring moves a High-match job to `READY` before approval. **Decided 2026-11-19 (owner): no;** drafts are written while the job stays `SCORED`, and `READY` is reachable only from `APPROVED`, so "Approved" always means the user approved it | Start of M1 | T1.05, T3.05 |
