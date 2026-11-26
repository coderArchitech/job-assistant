# Privacy policy: Job Assistant

> **Draft for task T5.06.** Before publishing:
>
> - fill in the operator's name, the contact address and the effective date;
> - host the page at a public URL;
> - link that URL in the app's settings and in the Play listing.
>
> Keep this page in step with `docs/TECHNICAL_DESIGN.md` ("Security and privacy").

**Effective date:** [date]
**Operator:** [name of the developer or company publishing the app]
**Contact:** [email address for privacy questions]

## In short

- Job Assistant runs on your phone. There is no Job Assistant server and no account with us. We do not receive, store or sell your personal data.
- Your data stays on your phone, except where you send it to a service you chose yourself:
  - the AI provider you connect;
  - the job portals you sign in to;
  - your own Gmail account.
- Passwords, one-time codes and API keys are encrypted on your phone. They are never sent to an AI provider and never written to logs.
- You can delete everything the app holds at any time with **Settings → Data and privacy → Delete all data**.

## What the app stores on your phone

| Data | Why | Where it is kept |
| --- | --- | --- |
| Your profile and resume (name, contact details, work history, skills) | To find and score jobs and fill applications | App-private storage on your phone |
| Search presets, settings and budgets | To run searches the way you set them up | App-private storage |
| Jobs found, match scores, your decisions, applications, drafts and reports | To track your job search | An on-device database |
| Screenshots of submitted application pages | Proof of what was submitted | App-private storage, excluded from backups |
| Portal passwords and your AI provider API key | To sign in to portals and call the AI for you | Encrypted with Google Tink, under a key held by the Android Keystore; excluded from backups |
| Portal sign-in sessions (cookies) | To stay signed in between runs | The WebView's app-private storage, excluded from backups |
| A record of AI usage (tokens and cost per call) | To show spending and enforce your budget | The on-device database |

The app has no analytics or crash-reporting service. Diagnostic logs stay on your phone and are shared only if you export them yourself. Secrets are removed from logs before they are written.

## What leaves your phone, and to whom

The app sends data only to these services, and only to do what you asked of it.

**Your AI provider (Anthropic or OpenAI), using your own API key.**

- **Sent:** job descriptions, and the parts of your profile and resume needed to score a job or draft a tailored resume, cover letter or answer.
- **Never sent:** passwords, one-time codes, cookies or API keys. The app checks every request for them and blocks it if one is found.
- **Your terms:** the provider handles this data under your own account, according to its terms and privacy policy.

**Job portals you add (for example Naukri or Instahyre) and company job forms (Greenhouse, Lever and similar).**

- **Sign-in:** your user name and password go only to that portal, when the app signs in for you.
- **Applications:** when you approve one, the application form is filled with your details and resume, and submitted to that portal.
- **LinkedIn:** the app never automates it.

**Public job boards (Remotive, We Work Remotely, Hacker News and the public boards of companies on your watchlist).**

- **Sent:** requests for job listings. These carry no personal data.

**Google, if you connect Gmail.**

- **Scope:** the app asks only for permission to send email (`gmail.send`).
- **Use:** it sends recruiter emails you approved, and your daily report to your own address if you turn the digest on.
- **What it doesn't do:** it does not read your mailbox, and it keeps no long-lived Google token.
- **Google's terms:** Google's privacy policy applies.
- **Limited Use:** the app's use of information received from Google APIs follows the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including its Limited Use requirements.

**The app's configuration host.**

- **Downloaded:** the app fetches signed portal scripts and a model price list.
- **Sent:** the requests carry no personal data or identifiers.

## Text messages (one-time codes)

When a portal sends a one-time code by SMS, Android asks you, each time, whether the app may read that one message. This is the SMS User Consent API, so the app never has general access to your messages.

The code is used once to finish signing in, then cleared from memory. It is never stored, logged or sent to an AI provider.

## Backups

If Android backup is on, the app's non-secret data (your job history and settings) is included, so a new phone keeps it. These are never backed up:

- passwords;
- API keys;
- portal sessions;
- proof screenshots;
- your consent to send emails automatically.

On phones without end-to-end backup encryption, cloud backup of the app's data is turned off.

## Your choices and rights

- **See and change:** your profile, settings and stored items are visible and editable in the app.
- **Delete:** **Delete all data** erases the following, then returns the app to its first screen:
  - the database;
  - encrypted secrets and their key;
  - portal sessions;
  - your resume, screenshots and caches.

  It also revokes Gmail access. Uninstalling the app also removes its data from the phone.
- **Withdraw consent:** you can disconnect Gmail, remove a portal or your AI key, or turn off background runs at any time in the app.
- **India's Digital Personal Data Protection Act, 2023:** the app records your consent at setup, uses your data only for your job search, and lets you erase it with Delete all data.

  Because we operate no server, we hold no personal data about you. A request to us therefore has nothing to access or delete beyond what Delete all data already removes.

Data you sent to an AI provider, a portal or Google is held by them under their own policies. Contact them to access or delete it.

## Children

The app is meant for adults looking for work. It is not directed at children under 18.

## Changes to this policy

When this policy changes, the new version will be posted at the same address with a new effective date. If a change affects what leaves your phone, the app will ask for your consent again.

## Contact

Questions about this policy: [email address for privacy questions].
