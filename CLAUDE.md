# Project Instructions for AI Agents

This file provides instructions and context for AI coding agents working on this project.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:7510c1e2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->


## Build & Test

```bash
jolt -M:test          # the whole suite; exits non-zero on failure
jolt serve            # HTTP on 3985, nREPL on 7888
jolt -M:gui           # the GL scene graph; a strict HTTP client of the server
```

## Deploy changes WITHOUT restarting the server

`jolt serve` already runs an nREPL (port from `HARNESS_NREPL_PORT`, default
7888, written to `.nrepl-port`). **Use it.** A restart costs whatever is
running — a campaign generation is hours of provider spend, and the Lean pool
pays ~12s re-importing Mathlib on top.

```bash
jolt -A:dev -M -m nrepl-client '(require (quote veriframe.store.journal) :reload)'
```

It must be `-M -m nrepl-client`. Passing the file path — which the client's
own docstring used to show — loads the namespace without calling `-main` and
exits 0 having printed nothing, which reads exactly like a command that
worked.

Reloading takes effect immediately for every namespace except
`veriframe.system`: the server holds handler **vars**, not the functions
inside them. `veriframe.system/restart!` is there for the cases that need it.

Reload every namespace you touched, in dependency order. Verify against the
live server afterwards rather than assuming — a reload that throws leaves the
old code in place and the process running.

Worked example: `journal/branch-turns` plus `api.runs` were reloaded into a
server mid-run, taking a branch-detail request from >50s (client timeout) to
1.9s, with the generation still going.

## Architecture Overview

_Add a brief overview of your project architecture_

## Conventions & Patterns

_Add your project-specific conventions here_
