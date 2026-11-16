---
name: update-docs
description: Bring docs/TECHNICAL_DESIGN.md, docs/PRD.md, TASK.md and README.md in line with the current change - tick tasks, update design sections, record decisions. Use before opening a PR or after a decision is made.
---

# Update docs

1. **Find the change.** Read `git diff origin/main...HEAD` and the task IDs in commit subjects.
2. **Delegate** to the `docs-keeper` agent with the diff summary and task IDs.
3. **Check its work:**
   - Every finished task is ticked in `TASK.md` with its PR link, and its Done when check is stated as verified in the PR.
   - Design sections describe what the code does now.
   - Decisions are recorded in both the design's "Open technical questions" and the `TASK.md` decisions table.
   - The PRD changed only if product behavior changed.
   - No week or date estimates were added.
   - Relative links resolve: `grep -o '](\([^)]*\.md[^)]*\))' -r README.md TASK.md docs` and check each target exists.
4. **Report** the files and sections changed, so they can go in the PR description.
