---
name: mandate
description: 'Draft the mandate of one change in docs/mandates/ID.json, then hand it to a person to approve.'
agent: agent
argument-hint: 'ID'
tools: ['read', 'search', 'edit', 'execute']
---

You draft a mandate for one change, then stop. A mandate says what the change may do. A person approves it; you never do.

1. The id is what the user typed after the command: lower-case words with dashes, such as `fix-empty-search`. If it is missing, ask for it and wait.
2. If `docs/mandates/<ID>.json` exists and holds `failsIf`, a person approved it. Never edit a mandate a person approved: an edit needs a new approval. Tell the person and ask before you change it.
3. Find what the change is for. Look in this order, use the first one that says it, and tell the user which one you used:
   1. what the user said in this chat;
   2. an issue or story the user named;
   3. your own plan for the change;
   4. an `intent.md`;
   5. a spec: Spec Kit (`specs/*/spec.md`) or Kiro (`.kiro/specs/*/requirements.md`);
   6. the kit's approved design of the story (its LLD).

   Do not read the diff to write the intent. A mandate says what the change may do, before or apart from the code. Look at the code only to find paths and tests.
4. Write `docs/mandates/<ID>.json` with these fields and no others:

   ```json
   {
     "intent": "Searching with an empty query returns an empty list, not an error",
     "scope": { "paths": ["src/main/java/com/acme/search/**", "src/test/java/com/acme/search/**"] },
     "oracle": { "scenarios": ["src/test/java/com/acme/search/SearchServiceTest.java#emptyQueryReturnsEmptyList"] },
     "mustStayTrue": [
       { "is": "A query with text returns the same results as before", "test": "src/test/java/com/acme/search/SearchRegressionTest.java" }
     ]
   }
   ```

   - `intent`: one sentence. An outcome a person can check, not a list of code edits.
   - `scope.paths`: the narrowest globs that hold the change and its tests. `**` crosses folders, `*` stays in one.
   - `oracle.scenarios`: the tests that must pass, as `path#name` for one test or `path` for every test in the file. For a bug fix, name the test that fails now.
   - `mustStayTrue`: only for something an existing test already proves, each with `is` (one sentence) and `test` (that test's file). Leave it out when no existing test proves it.

   Never write `failsIf`: it is the approver's own sentence. Do not write `mandate`, `id` or blob ids either: `mandate draft` fills them in.
5. Run `java .agent/Sdlc.java mandate draft <ID>`. When it reports a problem, fix the file and run it again, until it exits 0.
   On a protected branch the guard refuses the file: switch to a branch for the change first, as the refusal says.
6. End your answer with:
   - the card that `java .agent/Sdlc.java mandate show <ID>` prints;
   - the one or two parts of the draft you are least sure of (a path, a test, a word of the intent), so that the approver looks there;
   - this line, for the person to run in a terminal: `java .agent/Sdlc.java mandate approve <ID>`

   Then stop. Do not start the change in this turn unless the user asked for that.

Never run `mandate approve`, and never imitate it: write no approval, and do not call the mandate approved. When the person approves, the command asks them a question first. Do not suggest an answer to it, now or later: it works only when they write it alone.
