---
name: implement-task
description: Implement one task from TASK.md end to end - check dependencies and decisions, build it, test it, review it, update docs and tick it off. Use as /implement-task T1.07.
---

# Implement a task

Argument: a task ID from `TASK.md`, for example `T1.07`. If none is given, ask for one, or suggest the first unticked task whose dependencies are all ticked.

## Steps

1. **Read the task.** Find it in `TASK.md`. Note its priority, **Depends on** list and **Done when** check.
2. **Check it can start.**
   - Every dependency must be ticked. If one is not, stop and list what is missing.
   - If a decision in the **Open decisions** table blocks it, stop and ask for the decision, offering the options and a recommendation.
3. **Branch.** Create `task/<ID>-<short-slug>` from the latest `main`.
4. **Plan.** Read the design sections the task touches and the rules in `CLAUDE.md`. Write a short plan: files to add or change, tests to add, how Done when will be checked. Share it before large changes.
5. **Build.** Use the `android-dev` agent (or `adapter-author` for portal adapters) for the implementation.
6. **Test.** Use the `test-writer` agent for the tests `CLAUDE.md` requires, then run `/run-tests`.
7. **Review.** If the change touches credentials, AI calls, portals, logging, storage or applying, run the `security-reviewer` agent and fix every critical or high finding.
8. **Verify Done when.** Run the check and record how it passed.
9. **Docs.** Run `/update-docs`: tick the task with the PR link, update changed design sections, record any decision.
10. **Commit and PR.** Commit subject starts with the task ID (`T1.07: Build the credential vault`). Open a PR titled the same way, with: what changed, how Done when was verified, test results, security review result, and doc changes.

Stop and ask rather than guess whenever the design is silent or contradicts the task.
