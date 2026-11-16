# AI Job Search Agent (Android)

A mobile-first Android app that finds, scores and applies to jobs on your behalf. It runs on your own Claude or ChatGPT API key, keeps your credentials on your phone, and asks for your approval before anything is submitted.

> **Status:** Phase 1 — in planning. See the [Product Requirements Document](docs/PRD.md) and the [Technical Design](docs/TECHNICAL_DESIGN.md).

---

## Why

Job seekers spend hours every day repeating the same work across Naukri, Instahyre, company career pages and remote job boards: searching, reading descriptions, judging fit, filling forms and writing recruiter messages. This app turns that into a few minutes a day of approving the best matches and reading a daily report.

## Phase 1 features

| Area | What it does |
| --- | --- |
| **Automated job search** | Scheduled runs discover, score, tailor and apply to jobs across multiple sources |
| **Bring your own AI** | Use Anthropic (Claude) or OpenAI (ChatGPT) with your own API key; pick a model per task from a live model list |
| **Match filters** | Every job is tiered as **High** (75–100), **Partial** (50–74) or **Low** (<50) match, with reasons, matched skills and missing skills |
| **Custom search context** | Free-form context (e.g. "no service companies, open to Bengaluru hybrid") shapes both search and scoring; saved search presets |
| **Custom portals** | Add Naukri, Instahyre or any job portal with login URL and credentials; sessions are reused between runs |
| **OTP auto-read** | Reads login OTPs from SMS through Google's SMS User Consent API (no `READ_SMS` permission) |
| **Applying** | Fills portal forms automatically; "stop before submit" by default, optional auto-submit for High match; daily caps per portal |
| **Recruiter outreach** | Sends tailored emails via your Gmail; drafts LinkedIn messages for you to send manually |
| **Daily report** | Jobs found, matched, applied, emails sent, replies and AI cost — as a push notification, in-app report and optional email digest |
| **Tracking** | Status pipeline per job (Found → Applied → Replied → Interview → Offer) with proof screenshots |

## How it works

```
Discover ──► Score ──► Tailor ──► Apply ──► Outreach ──► Report
```

1. **Discover** — Pulls jobs from Greenhouse, Lever and Ashby job-board APIs (from your company watchlist), RemoteOK, Remotive, We Work Remotely, Hacker News "Who is hiring", plus Naukri, Instahyre and your custom portals. Jobs are normalized and deduplicated.
2. **Score** — Cheapest checks first: hard filters (deal-breakers, location, "remote, US only"), then embedding similarity, then LLM scoring with structured output.
3. **Tailor** — Generates cover letters, answers to portal questions and outreach drafts using only facts from your profile.
4. **Apply** — Runs in a visible "Agent at work" screen with a live view of the portal and a Stop button. Background runs discover, score and tailor; you open the app and tap **Apply approved**.
5. **Outreach** — Recruiter emails via Gmail (approval by default); LinkedIn messages are drafted, never sent automatically.
6. **Report** — A daily summary at your chosen time (default 9:00 PM IST).

## Architecture

Everything runs on the phone. Only calls to your AI provider, job sites and Gmail leave the device.

```
┌──────────────────────────── Android phone ────────────────────────────┐
│  App UI (Jetpack Compose)                                              │
│        │                                                               │
│  Agent pipeline (WorkManager + foreground service)                     │
│        │                                                               │
│  Room database   │   Keystore vault (AES-256-GCM)   │   Session cookies │
└────────┬──────────────┬──────────────┬──────────────┬─────────────┬────┘
         │              │              │              │             │
    AI provider     Job APIs      Job portals      Gmail API     SMS inbox
  (Claude/OpenAI)  (Greenhouse,  (Naukri,         (recruiter    (OTP via User
                    Lever, ...)   Instahyre, ...)  email)        Consent API)
```

## Tech stack

| Layer | Choice |
| --- | --- |
| Language & UI | Kotlin, Jetpack Compose, Material 3 |
| Architecture | MVVM, Hilt, coroutines and Flow |
| Local data | Room (SQLite) |
| Secrets | Android Keystore with AES-GCM (e.g. Google Tink) |
| Scheduling | WorkManager; foreground service during browser automation |
| Networking | OkHttp, Retrofit, kotlinx.serialization |
| AI | Provider interface with Anthropic and OpenAI implementations |
| Embeddings | OpenAI embeddings or an on-device text embedder |
| Browser automation | Android WebView with injected JavaScript per-portal adapters |
| OTP | SMS User Consent API (Google Play services) |
| Email | Google Sign-In with Gmail API |
| Charts | Compose chart library (e.g. Vico) |

### Portal adapter contract

Every portal implements the same interface, so the pipeline treats them alike:

```kotlin
interface PortalAdapter {
    suspend fun login(): LoginResult            // LoggedIn | OtpNeeded | Failed
    suspend fun search(preset: SearchPreset): List<JobSummary>
    suspend fun getDetails(url: String): Job
    suspend fun apply(job: Job, answers: Answers, mode: ApplyMode): ApplyResult
                                                // Submitted | ReadyForUser | NeedsInput | Failed
    suspend fun healthCheck(): Boolean          // verifies selectors still work
}
```

Custom portals without a dedicated adapter use a generic AI browser agent (Beta) that reads a simplified DOM and returns the next action.

## Security and privacy

- Portal passwords, API keys and Gmail tokens are encrypted with keys held in the Android Keystore.
- Credentials, cookies and OTPs never leave the device and are never included in AI prompts.
- Only job descriptions and profile text are sent to your chosen AI provider.
- Optional biometric lock; secrets are excluded from cloud backup.
- "Delete all data" wipes everything in one action.
- Designed to comply with India's Digital Personal Data Protection Act, 2023.

## Responsible use

Most job portals' terms of service prohibit automated access, and accounts can be restricted. The app is designed to minimize this risk:

- **Stop before submit** is the default; you review every application.
- Daily apply caps per portal (default 10) and overall (default 25).
- Sessions are reused instead of logging in repeatedly.
- CAPTCHAs and anti-bot checks are **never** bypassed; the job is handed back to you.
- LinkedIn is **never** automated; messages are drafted for you to send.

You accept these risks per portal during onboarding.

## Proposed project structure

```
app/
├── ui/              # Compose screens: Home, Jobs, Review, Applications,
│                    # Outreach, Portals, Report, Settings
├── agent/           # Pipeline: discover, score, tailor, apply, outreach, report
├── ai/              # Provider interface, Anthropic & OpenAI clients, prompts
├── portals/         # PortalAdapter implementations + generic browser agent
│   └── scripts/     # Injected JavaScript per portal
├── sources/         # Job board API clients (Greenhouse, Lever, Ashby, ...)
├── data/            # Room entities, DAOs, repositories
├── security/        # Keystore vault, credential storage
├── otp/             # SMS User Consent API integration
├── email/           # Gmail API client
└── work/            # WorkManager workers and scheduling
```

## Phase 1 delivery plan

Estimated 14 weeks for two Android developers (planning assumption):

| Weeks | Milestone | Scope |
| --- | --- | --- |
| 1–3 | Foundation | Profile and resume parsing, AI provider settings, Room, Keystore vault, app shell |
| 4–6 | Discovery and scoring | Job APIs, deduplication, hard filters, embeddings, LLM scoring, Jobs and Review screens |
| 7–10 | Portals and applying | Portal manager, Naukri and Instahyre adapters, session reuse, SMS OTP, Greenhouse/Lever forms, Agent at work screen |
| 11–12 | Outreach and reporting | Gmail send, LinkedIn drafts, follow-ups, daily report, usage meter, scheduling |
| 13–14 | Hardening and beta | Security review, Play policy review, adapter health checks, closed beta with 20 users |

## Requirements

- Android 10 (API 29) or higher
- An API key from [Anthropic](https://console.anthropic.com) or [OpenAI](https://platform.openai.com)
- Accounts on the job portals you want to automate (optional)
- A Google account for Gmail outreach (optional)

## Documentation

- [Product Requirements Document — Phase 1](docs/PRD.md): goals, user stories, functional and non-functional requirements, risks, acceptance criteria and open questions.
- [Technical Design — Phase 1](docs/TECHNICAL_DESIGN.md): architecture, modules, pipeline and scheduling, data model, AI provider layer, job sources, portal automation, OTP, outreach, security, Android platform constraints, testing and delivery plan.
- [Implementation tasks](TASK.md): every task needed to build Phase 1, grouped by milestone, with dependencies and done-when checks.
