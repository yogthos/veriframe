/**
 * Planning skeleton generator for the agent's `setup_planning` tool.
 *
 * Generates the mechanical boilerplate for a bounded state-transition
 * planning encoding: per-timestep state variables, domain bounds,
 * initial state, goal state, invariants, and per-step transition
 * disjunctions with explicit frame axioms.
 *
 * The model brings the domain content (what state to track, what
 * actions exist, what each action's preconditions and effects look
 * like, what invariants hold). This module lays down the plumbing.
 *
 * Spec convention:
 *   - state_vars carry a *base* name with no time suffix.
 *   - In `invariants` and in each action's `predicate`, the model
 *     references a state variable at time t with the suffix `_t` and
 *     at time t+1 with `_tp1`. The generator substitutes those
 *     suffixes with the concrete timestep number per transition.
 *   - `actions[i].changes` lists the base names of vars that THIS
 *     action changes. Frame axioms `(= name_t name_tp1)` are emitted
 *     automatically for every base name NOT in `changes`.
 *   - `initial` / `goal` map base name → value (number or boolean).
 */

export interface StateVarSpec {
  name: string;
  sort: "Int" | "Bool";
  domain?: [number, number];
}

export interface ActionSpec {
  name: string;
  changes: string[];
  predicate: string;
}

export interface PlanningSpec {
  horizon: number;
  state_vars: StateVarSpec[];
  initial: Record<string, number | boolean>;
  goal: Record<string, number | boolean>;
  invariants?: string[];
  actions: ActionSpec[];
}

export class PlanningSpecError extends Error {}

export function validatePlanningSpec(raw: unknown): PlanningSpec {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new PlanningSpecError("setup_planning args must be a JSON object");
  }
  const r = raw as Record<string, unknown>;

  if (
    typeof r.horizon !== "number" ||
    !Number.isInteger(r.horizon) ||
    r.horizon <= 0
  ) {
    throw new PlanningSpecError("`horizon` must be a positive integer");
  }
  if (r.horizon > 200) {
    throw new PlanningSpecError(
      `\`horizon\` capped at 200 (got ${r.horizon}); keep it tractable`,
    );
  }

  if (!Array.isArray(r.state_vars) || r.state_vars.length === 0) {
    throw new PlanningSpecError("`state_vars` must be a non-empty array");
  }
  const state_vars: StateVarSpec[] = [];
  const namesSeen = new Set<string>();
  for (const v of r.state_vars) {
    if (!v || typeof v !== "object") {
      throw new PlanningSpecError("each state_var must be an object");
    }
    const sv = v as Record<string, unknown>;
    if (typeof sv.name !== "string" || sv.name.length === 0) {
      throw new PlanningSpecError(
        "each state_var needs a non-empty string `name`",
      );
    }
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(sv.name)) {
      throw new PlanningSpecError(
        `state_var name "${sv.name}" must be alphanumeric/underscore (no spaces or punctuation)`,
      );
    }
    if (namesSeen.has(sv.name)) {
      throw new PlanningSpecError(`duplicate state_var name "${sv.name}"`);
    }
    namesSeen.add(sv.name);
    if (sv.sort !== "Int" && sv.sort !== "Bool") {
      throw new PlanningSpecError(
        `state_var "${sv.name}" sort must be "Int" or "Bool"`,
      );
    }
    let domain: [number, number] | undefined;
    if (sv.domain !== undefined) {
      if (
        !Array.isArray(sv.domain) ||
        sv.domain.length !== 2 ||
        typeof sv.domain[0] !== "number" ||
        typeof sv.domain[1] !== "number"
      ) {
        throw new PlanningSpecError(
          `state_var "${sv.name}" domain must be [min, max]`,
        );
      }
      domain = [sv.domain[0], sv.domain[1]];
    }
    state_vars.push({
      name: sv.name,
      sort: sv.sort as "Int" | "Bool",
      domain,
    });
  }

  const initial = validateAssignment(r.initial, namesSeen, state_vars, "initial");
  const goal = validateAssignment(r.goal, namesSeen, state_vars, "goal");

  const invariants: string[] = [];
  if (r.invariants !== undefined) {
    if (!Array.isArray(r.invariants)) {
      throw new PlanningSpecError(
        "`invariants` must be an array of SMT-LIB strings",
      );
    }
    for (const inv of r.invariants) {
      if (typeof inv !== "string") {
        throw new PlanningSpecError("each invariant must be a string");
      }
      invariants.push(inv);
    }
  }

  if (!Array.isArray(r.actions) || r.actions.length === 0) {
    throw new PlanningSpecError("`actions` must be a non-empty array");
  }
  const actions: ActionSpec[] = [];
  const actionNames = new Set<string>();
  for (const a of r.actions) {
    if (!a || typeof a !== "object") {
      throw new PlanningSpecError("each action must be an object");
    }
    const ac = a as Record<string, unknown>;
    if (typeof ac.name !== "string" || ac.name.length === 0) {
      throw new PlanningSpecError(
        "each action needs a non-empty string `name`",
      );
    }
    if (actionNames.has(ac.name)) {
      throw new PlanningSpecError(`duplicate action name "${ac.name}"`);
    }
    actionNames.add(ac.name);
    if (!Array.isArray(ac.changes)) {
      throw new PlanningSpecError(
        `action "${ac.name}" needs a \`changes\` array of state-var base names`,
      );
    }
    const changes: string[] = [];
    for (const c of ac.changes) {
      if (typeof c !== "string" || !namesSeen.has(c)) {
        throw new PlanningSpecError(
          `action "${ac.name}" lists unknown state var in changes: "${c}"`,
        );
      }
      changes.push(c);
    }
    if (typeof ac.predicate !== "string" || ac.predicate.length === 0) {
      throw new PlanningSpecError(
        `action "${ac.name}" needs a non-empty SMT-LIB \`predicate\``,
      );
    }
    actions.push({ name: ac.name, changes, predicate: ac.predicate });
  }

  return {
    horizon: r.horizon,
    state_vars,
    initial,
    goal,
    invariants,
    actions,
  };
}

function validateAssignment(
  raw: unknown,
  knownNames: Set<string>,
  vars: StateVarSpec[],
  field: string,
): Record<string, number | boolean> {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new PlanningSpecError(
      `\`${field}\` must be an object mapping state-var name → value`,
    );
  }
  const r = raw as Record<string, unknown>;
  const out: Record<string, number | boolean> = {};
  for (const [k, v] of Object.entries(r)) {
    if (!knownNames.has(k)) {
      throw new PlanningSpecError(
        `\`${field}\` references unknown state var "${k}"`,
      );
    }
    const sv = vars.find((s) => s.name === k)!;
    if (sv.sort === "Int") {
      if (typeof v !== "number" || !Number.isInteger(v)) {
        throw new PlanningSpecError(
          `\`${field}.${k}\` must be an integer for Int-sorted var`,
        );
      }
      out[k] = v;
    } else {
      if (typeof v !== "boolean") {
        throw new PlanningSpecError(
          `\`${field}.${k}\` must be a boolean for Bool-sorted var`,
        );
      }
      out[k] = v;
    }
  }
  return out;
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Substitute time suffixes for a single transition T → T+1.
 * For each state var name, replaces `<name>_tp1` with `<name>_<T+1>`
 * and `<name>_t` with `<name>_<T>`. Order matters: _tp1 first.
 */
export function substituteTime(
  template: string,
  vars: StateVarSpec[],
  t: number,
): string {
  let out = template;
  for (const v of vars) {
    out = out.replace(
      new RegExp(`\\b${escapeRegExp(v.name)}_tp1\\b`, "g"),
      `${v.name}_${t + 1}`,
    );
  }
  for (const v of vars) {
    out = out.replace(
      new RegExp(`\\b${escapeRegExp(v.name)}_t\\b`, "g"),
      `${v.name}_${t}`,
    );
  }
  return out;
}

function smtValue(v: number | boolean, sort: "Int" | "Bool"): string {
  if (sort === "Int") return String(v);
  return v ? "true" : "false";
}

export function generatePlanningSmt(spec: PlanningSpec): string {
  const { horizon: K, state_vars, initial, goal, invariants, actions } = spec;
  const lines: string[] = [];
  lines.push(
    `;; --- PLANNING_SETUP horizon=${K} state_vars=${state_vars.length} actions=${actions.length} ---`,
  );

  // Declarations + domain bounds
  lines.push("");
  lines.push(";; declarations");
  for (const v of state_vars) {
    const decls: string[] = [];
    for (let t = 0; t <= K; t++) {
      decls.push(`(declare-const ${v.name}_${t} ${v.sort})`);
    }
    lines.push(decls.join(" "));
  }
  for (const v of state_vars) {
    if (v.sort === "Int" && v.domain) {
      const [min, max] = v.domain;
      const conjs: string[] = [];
      for (let t = 0; t <= K; t++) {
        conjs.push(`(>= ${v.name}_${t} ${min}) (<= ${v.name}_${t} ${max})`);
      }
      lines.push(
        `(assert (! (and ${conjs.join(" ")}) :named bounds_${v.name}))`,
      );
    }
  }

  // Initial state
  lines.push("");
  lines.push(";; initial state at t=0");
  for (const [k, v] of Object.entries(initial)) {
    const sv = state_vars.find((s) => s.name === k)!;
    lines.push(
      `(assert (! (= ${k}_0 ${smtValue(v, sv.sort)}) :named initial_${k}))`,
    );
  }

  // Goal state at t=K
  lines.push("");
  lines.push(`;; goal state at t=${K}`);
  for (const [k, v] of Object.entries(goal)) {
    const sv = state_vars.find((s) => s.name === k)!;
    lines.push(
      `(assert (! (= ${k}_${K} ${smtValue(v, sv.sort)}) :named goal_${k}))`,
    );
  }

  // Invariants — expanded per timestep
  if (invariants && invariants.length > 0) {
    lines.push("");
    lines.push(";; invariants — held at every timestep 0..K");
    for (let i = 0; i < invariants.length; i++) {
      const inv = invariants[i];
      for (let t = 0; t <= K; t++) {
        const expanded = substituteTime(inv, state_vars, t);
        lines.push(`(assert (! ${expanded} :named inv_${i}_t${t}))`);
      }
    }
  }

  // Transitions: per step T -> T+1, disjunction over actions; each
  // action conjuncts its predicate with frame axioms for non-changed
  // state vars.
  lines.push("");
  lines.push(";; transitions");
  for (let t = 0; t < K; t++) {
    const disjuncts: string[] = [];
    for (const a of actions) {
      const changedSet = new Set(a.changes);
      const frameTerms: string[] = [];
      for (const v of state_vars) {
        if (!changedSet.has(v.name)) {
          frameTerms.push(`(= ${v.name}_${t} ${v.name}_${t + 1})`);
        }
      }
      const predicateAtT = substituteTime(a.predicate, state_vars, t);
      const conjuncts = [predicateAtT, ...frameTerms];
      disjuncts.push(`(and ${conjuncts.join(" ")})`);
    }
    const transition =
      disjuncts.length === 1 ? disjuncts[0] : `(or ${disjuncts.join(" ")})`;
    lines.push(`(assert (! ${transition} :named transition_t${t}))`);
  }

  lines.push("");
  lines.push(`;; --- END PLANNING_SETUP ---`);
  return lines.join("\n");
}
