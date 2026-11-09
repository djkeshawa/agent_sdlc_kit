---
name: jira-confluence
description: 'How agents read and write Jira and Confluence in this repository: read with MCP or the sdlc tool, write only with the sdlc tool (audited), ticket statuses, comment style, the approval line and publishing the test design and LLD. Use whenever a task touches a Jira ticket or a Confluence page.'
---

# Jira and Confluence

All commands are `java .agent/Sdlc.java ...` (written `sdlc ...` below).

## Reading (allowed freely)

- Ticket with parents, sub-tasks, links, last comments: `sdlc jira get PROJ-123 [--comments 20]`
- Search: `sdlc jira search "project = PROJ AND parent = PROJ-123"`
- Every page for a key and its parents: `sdlc docs find PROJ-123`
- Copy them for reading: `sdlc docs pull PROJ-123` -> `.sdlc/PROJ-123/sources/index.md` lists
  the one pager, HLD, test design, LLDs (ours and other teams'), the Jira issues, and the
  approval status of our test design and LLD
- One page: `sdlc confluence get <pageId>`
- The Atlassian MCP tools may be used for reading too (search, get issue, get page).

## Writing (only these commands)

| Action | Command |
| --- | --- |
| Publish the test design | `sdlc docs publish PROJ-123 test-design --file .sdlc/PROJ-123/drafts/test-design.md` |
| Publish this team's LLD | `sdlc docs publish PROJ-123 lld --file .sdlc/PROJ-123/drafts/lld.md` |
| Sub-tasks from the approved LLD | `sdlc tasks create PROJ-123` |
| Start a task (moves it and the story to In Progress) | `sdlc task start PROJ-124` |
| Comment | `sdlc jira comment PROJ-123 --file .sdlc/PROJ-123/inbox/comment.md` |
| Move to In Progress / In Review | `sdlc jira transition PROJ-123 "In Review"` (usually automatic: `sync`) |
| Link a URL | `sdlc jira link PROJ-123 https://... --title "..."` |

Write comment files in `.sdlc/<KEY>/inbox/` (agent-writable, never committed). MCP write tools
are refused by the hook on purpose: the tool logs every write to `.sdlc/logs/atlassian.jsonl`,
refuses approval text, and moves issues only to the configured In Progress / In Review
statuses. Opening the PR (`gh pr create`) moves the story and its sub-tasks to In Review and
links the PR automatically.

## Statuses

`To Do` -> `In Progress` (tool, at `task start`) -> `In Review` (tool, when the PR is open) ->
`Done` (human, after merge, unless `jira.agentMayClose` is set). Agents never close tickets.

## Comment style

Plain and short: what happened, links, what the human must do. Jira wiki markup works:
`*bold*`, `{{code}}`, `[title|https://link]`, `* bullet`.

## The approval line

`docs publish` prints and comments a line such as
`SDLC-APPROVED PROJ-123 lld 229614v1 b6bf6e6c6ece` (page id, version, hash of the content).
Only a human posts it, from their own account, as a new comment. It counts only while the page
is unchanged, the comment unedited, and the author allowed (`gates.approvers`). Agents never
write this line anywhere; the hooks and the tool refuse it. Check with
`sdlc approval check PROJ-123 lld` (or `test-design`).
