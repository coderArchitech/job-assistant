---
name: implement-task
description: Implement one task from TASK.md end to end - check dependencies and decisions, build it, test it, review it, update docs and tick it off. Use as /implement-task T1.07.
---

# Implement a task

Argument: a task ID from `TASK.md`, for example `T1.07`. If none is given, ask for one, or suggest the first unticked task whose dependencies are all ticked.

## Steps

Every step runs for every task. Do not skip one because the change looks small; if a step truly has nothing to do, say so in the PR description.

1. **Read the task.** Find it in `TASK.md`. Note its priority, **Depends on** list and **Done when** check.
2. **Check it can start.**
   - Every dependency must be ticked. If one is not, stop and list what is missing.
   - If a decision in the **Open decisions** table blocks it, stop and ask for the decision, offering the options and a recommendation.
3. **Branch.** Create `task/<ID>-<short-slug>` from the latest `main`.
4. **Plan.** Read the design sections the task touches and the rules in `CLAUDE.md`. Write a short plan: files to add or change, tests to add, how Done when will be checked. Share it before large changes.
5. **Write the code.** Use the `android-dev` agent (or `adapter-author` for portal adapters).
6. **Write the tests.** Use the `test-writer` agent for every test `CLAUDE.md` requires for this kind of change.
7. **Run the tests.** Run `/run-tests`. Fix failures and repeat until everything passes.
8. **Review the code.** Run `/code-review` on the branch diff and fix every correctness finding. Then run `/simplify` if the review raised cleanups.
9. **Security review.** Run the `security-reviewer` agent when the change touches credentials, AI calls, portals, logging, storage, networking, permissions or applying. Fix every critical or high finding. Otherwise note "not security-sensitive" and why.
10. **Verify Done when.** Run the check and record how it passed.
11. **Docs.** Run `/update-docs`: tick the task with the PR link, update changed design sections, record any decision.
12. **Commit, push and PR.** Commit subject starts with the task ID (`T1.07: Build the credential vault`). The pre-push hook re-runs the repo checks and Gradle checks and blocks the push if one fails. Open a PR titled the same way, with: what changed, how Done when was verified, test results, code review and security review results, and doc changes.
13. **Watch CI.** Subscribe to the PR and fix failing checks until it is green.

Stop and ask rather than guess whenever the design is silent or contradicts the task.
