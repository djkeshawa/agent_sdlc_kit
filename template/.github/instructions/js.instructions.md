---
applyTo: "**/*.{js,jsx,ts,tsx,mjs,cjs,vue,svelte}"
description: "JavaScript and TypeScript rules for production and test code in this repository"
---

# JavaScript and TypeScript files

- Follow the `js-coding-standards` skill (rule ids `JS-..`); tests follow `js-testing` (`JST-..`).
  Reviews cite those ids.
- The project profile (`docs/agent/project-profile.md`) wins: its framework, folder layout,
  module system (ESM or CommonJS), runner and lint rules.
- TypeScript strict; no `any`, `@ts-ignore`, `@ts-nocheck` or file-wide `eslint-disable`.
- Validate external input with the project's schema library at the boundary.
- No new dependency (`package.json`) unless the LLD asks for it: the edit needs a human click.
- Before finishing: `java .agent/Sdlc.java verify` (lint, tests, build as configured).
