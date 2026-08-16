# Emit one line per Mathlib declaration: "<kind> <name> :: <statement>".
#
# 43% of declarations continue past their first line, so a single-line grep
# drops the statement for nearly half the library — which is what left the
# index as 215k bare names. Accumulate from the declaration head until the
# signature ends: `:=`, or `where`, or a blank line, or the next declaration.
#
# awk rather than in-process: extraction over Mathlib's 7871 files took more
# than thirty minutes inside the runtime. awk is POSIX and always a real
# binary, unlike rg which is often a shell function or absent.

function flush() {
  if (kind != "") {
    gsub(/[ \t]+/, " ", stmt)
    sub(/^ /, "", stmt)
    sub(/ $/, "", stmt)
    print kind " " name " :: " stmt
  }
  kind = ""; name = ""; stmt = ""
}

/^[ \t]*(private |protected |noncomputable |nonrec )*(theorem|lemma|def|abbrev|instance|structure|inductive) / {
  flush()
  line = $0
  # strip modifiers so $1 is the kind and $2 the name
  sub(/^[ \t]*/, "", line)
  gsub(/^(private |protected |noncomputable |nonrec )+/, "", line)
  n = split(line, parts, /[ \t]+/)
  kind = parts[1]
  name = parts[2]
  sub(/[:({[].*$/, "", name)
  # the rest of the head line begins the statement
  rest = line
  sub(/^[^ \t]+[ \t]+[^ \t]+/, "", rest)
  stmt = rest
  if (index(line, ":=") > 0) { sub(/:=.*$/, "", stmt); flush() }
  next
}

kind != "" {
  if ($0 ~ /^[ \t]*$/) { flush(); next }
  chunk = $0
  if (index(chunk, ":=") > 0) { sub(/:=.*$/, "", chunk); stmt = stmt " " chunk; flush(); next }
  if (chunk ~ /^[ \t]*(where|by)[ \t]*$/) { flush(); next }
  stmt = stmt " " chunk
}

END { flush() }
