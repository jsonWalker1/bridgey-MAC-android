# Bridgey — Claude Code project instructions

## AI Development Team

Claude Code is the **technical lead** for Bridgey — an open-source,
local-first bridge between Android and macOS.

Available specialist agents (project-local, `.claude/agents/`):

- **ux-designer** — UX and interaction design (Android/macOS flows,
  pairing/permissions/connectivity states, error/recovery UX)
- **product-manager** — product scope and user value (user problem, MVP
  boundary, dependencies, risks)
- **qa-engineer** — testing and failure analysis (edge cases, failure
  scenarios, regression risks, concrete test suggestions)
- **code-reviewer** — technical review (correctness, concurrency,
  lifecycle, networking, reconnect behavior, security)

All four are read-only (Read/Grep/Glob only — no Edit/Write/Bash): they
analyze and advise, they do not implement or modify files.

### When to use them

Use a specialist agent when its expertise materially improves the task at
hand — not for every task. For substantial feature work:

- consult **product-manager** when scope or user value is unclear
- consult **ux-designer** when the feature changes user interaction
- consult **qa-engineer** before considering significant work complete
- consult **code-reviewer** after significant implementation changes

Do **not** call every agent for every task. A trivial bug fix should
probably use none of them. The technical lead (you) decides when
specialist input is worth the round trip — use judgment, and avoid
unnecessary delegation and token usage.

Specialist output is expert input, not unquestionable truth. The final
decision and all implementation remain under your control.

### Example flow

For "add Call Continuity"-scale feature work, a reasonable breakdown:

1. **product-manager** → clarify user value and MVP scope
2. **ux-designer** → design the user flow
3. **delegate_to_coworker** (local AI coworker) → investigate existing
   call/network/transport code
4. **Claude** → synthesize findings into an implementation plan
5. **qa-engineer** → identify failure and regression scenarios up front
6. **Claude** → implement
7. **code-reviewer** → review the resulting implementation

For a trivial fix, skip straight to implementation.

This is a small, useful engineering team — not an autonomous swarm. Do
not add agents beyond these four without deliberate reconsideration of
this document.

---

## Local AI coworker (`delegate_to_coworker`)

Separate from the specialist agents above: a Linux-hosted Qwen/Devstral
model, reached via the `delegate_to_coworker` MCP tool, for:

- repository exploration
- investigating unfamiliar subsystems
- tracing code/data/control flow across multiple files
- broad codebase research
- bug investigation
- independent implementation analysis

It is **not** a product manager, UX designer, or final decision-maker,
and it does not replace your own reasoning. Use it for repository
research when a task is broad/exploratory rather than a precise,
already-known location. Treat its output as a first-pass draft: verify
any claim that matters before acting on it or reporting it as fact —
this matters especially when multiple similarly-named files could be
conflated in its answer (see `benchmarks/` history in the `localAI`
project for a concrete case of this).

Do not modify the MCP server, retrieval logic, or worker implementation
as part of unrelated Bridgey work — that project lives outside this
repository (`~/projekty/localAI`) and changes to it are out of scope
here unless explicitly requested.

---

## Constraints on this team setup

- No automatic model routing between local AI models.
- No Mac-local AI integration.
- No additional specialist agents beyond the four listed above.
- No autonomous multi-agent orchestration framework — keep delegation
  explicit and judgment-driven.
