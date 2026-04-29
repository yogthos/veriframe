# Reasoning Harness — Plan

LLM generates facts → Z3 verifies consistency → harness controls the loop.
Externally presents as a standard OpenAI-compatible API.

## Architecture

```
POST /v1/chat/completions
        │
        ▼
┌───────────────────────────────┐
│         Harness Loop           │
│                                │
│  while not complete:           │
│    1. prompt LLM → facts       │
│    2. parse assertions (JSON)  │
│    3. z3.push() + assert each  │
│    4. z3.check()               │
│       ├─ SAT    → accept step  │
│       └─ UNSAT  → unsat core   │
│                   → fix prompt │
│                   → z3.pop()   │
│                   → retry      │
│    5. if complete:true → done  │
│                                │
│  return OpenAI-formatted resp  │
└───────────────────────────────┘
```

## Fact Format (LLM Output)

```json
{
  "explanation": "All users must be authenticated.",
  "assertions": [
    "(declare-sort User 0)",
    "(declare-fun authenticated (User) Bool)",
    "(assert (! (forall ((u User)) (authenticated u)) :named a1))"
  ],
  "complete": false
}
```

## Project Structure

```
src/
├── index.ts            Entry point (start server)
├── server.ts           OpenAI-compatible HTTP server (from rlm-sandbox)
├── harness/
│   ├── harness.ts      Main loop controller
│   ├── solver.ts       Z3 incremental wrapper (adapted from chiasmus)
│   ├── parser.ts       Extract + validate assertions from LLM JSON
│   └── prompts.ts      System prompt + step prompt templates
├── llm/
│   ├── types.ts        LLMClient interface
│   ├── factory.ts      Provider factory
│   └── openai.ts       OpenAI-compatible provider
└── types.ts            Core types (Fact, Step, HarnessState, etc.)

tests/
├── solver.test.ts
├── parser.test.ts
├── prompts.test.ts
├── harness.test.ts
└── server.test.ts
```

## TDD Sequence

1. Project skeleton (package.json, tsconfig, vitest)
2. Z3 incremental solver wrapper
3. DSL parser
4. Prompt templates
5. Harness loop (integration)
6. OpenAI-compatible server

## Dependencies

- z3-solver (WASM, from chiasmus)
- TypeScript + vitest + tsx
- Node.js built-in http module (no express)
