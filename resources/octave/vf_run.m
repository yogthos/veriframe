% veriframe - a claim-first verification harness
% Copyright (C) 2026 Dmitri Sotnikov
%
% This program and the accompanying materials are made available under
% the terms of the Eclipse Public License 2.0 which is available at
% https://www.eclipse.org/legal/epl-2.0/
%
% SPDX-License-Identifier: EPL-2.0

% One Octave invocation: load the branch's workspace, run one command against
% it, save it back, print one JSON object.
%
% NOT a persistent read-eval-print session, which is what the Prolog and Lean
% engines use and what this started as. Octave's fgetl(stdin) block-buffers on
% a pipe: it returns nothing until roughly 4KB has arrived or stdin closes, so
% a request-per-line protocol deadlocks on the first request. Verified by
% sending 8KB of pings, at which point every reply arrived at once.
%
% Invocation costs about 0.8s, against a branch turn that averages 37 seconds.
% The bead argued latency was the weak reason to prefer in-process, and the
% same arithmetic applies here. What the per-invocation shape buys back is
% worth more than the 0.8s: there is no session to wedge, so none of the
% busy-flag and kill-on-abandon machinery the Prolog session needs, and each
% call is bounded and killable through engine/proc.clj like z3 is.
%
% Reads dir/request.json, writes the reply on stdout. The workspace lives in
% dir/ws.mat.

function vf_run (dir)
  more off;
  page_screen_output (0);
  warning ("off", "all");

  ws = fullfile (dir, "ws.mat");
  try
    req = jsondecode (fileread (fullfile (dir, "request.json")));
  catch err
    vf_emit (struct ("ok", false, "error", ["malformed request: " err.message]));
    return;
  end_try_catch

  % A model calling either of these would block forever on a stdin nobody is
  % writing to. Shadowed globally, which is fine here because this process runs
  % exactly one command and then exits.
  eval ("function varargout = input (varargin), error ('input() is not available'); end");
  eval ("function keyboard (varargin), error ('keyboard() is not available'); end");

  % Everything the model touches lives in `base`, never in this function's
  % scope. eval() inside a helper makes the model's variables that helper's
  % locals, so they are discarded the moment it returns and the next call finds
  % nothing -- which is exactly what happened first time round.
  if (exist (ws, "file"))
    evalin ("base", sprintf ("load ('%s');", ws));
  endif
  vf_define_helpers ();

  reply = vf_dispatch (req);

  % Saved even when the command failed, so a bad expression does not discard
  % the workspace the branch built up over previous turns.
  vf_save_workspace (ws);
  vf_emit (reply);
endfunction

% Approximate comparison has to be EXPLICIT. A model writing `x == 0.3` after
% summing 0.1 three times gets false and reads it as a refutation, when the
% real answer is that it asked an exact question about inexact arithmetic.
function vf_define_helpers ()
  evalin ("base", ["function r = vf_approx (a, b, tol)\n", ...
         "  if (nargin < 3) tol = 1e-9; endif\n", ...
         "  r = all (abs (a(:) - b(:)) <= tol .* max (1, abs (b(:))));\n", ...
         "end"]);
endfunction

function vf_save_workspace (ws)
  % Saving `base` rather than this scope, for the same reason.
  try
    evalin ("base", sprintf ("save ('-binary', '%s');", ws));
  catch
  end_try_catch
endfunction

function vf_emit (reply)
  printf ("%s\n", jsonencode (reply));
  fflush (stdout);
endfunction

function reply = vf_dispatch (req)
  try
    switch (req.op)
      case "ping"
        reply = struct ("ok", true, "pong", true, "version", version ());
      case "eval"
        reply = vf_eval (req.code);
      case "check"
        tol = 0;
        if (isfield (req, "tol")) tol = req.tol; endif
        reply = vf_check (req.expr, tol);
      otherwise
        reply = struct ("ok", false, "error", ["unknown op: " req.op]);
    endswitch
  catch err
    reply = struct ("ok", false, "error", err.message);
  end_try_catch
endfunction

function reply = vf_eval (code)
  try
    out = evalin ("base", ["evalc(" vf_quote(code) ")"]);
    reply = struct ("ok", true, "output", vf_trim (out));
  catch err
    reply = struct ("ok", false, "error", err.message);
  end_try_catch
endfunction

% The verdict is deliberately narrow. The expression must reduce to a real
% logical scalar: anything else is a modelling error rather than a result, and
% saying so is more useful than coercing it. An empty result is NOT false, a
% matrix is not "true if any element is", and NaN is neither.
function reply = vf_check (expr, tol)
  try
    val = evalin ("base", expr);
  catch err
    reply = struct ("ok", false, "error", err.message);
    return;
  end_try_catch

  if (isempty (val))
    reply = struct ("ok", false, "error", "the expression produced an empty value, which is neither true nor false");
  elseif (! (islogical (val) || isnumeric (val)))
    reply = struct ("ok", false, "error", ["the expression produced a " class(val) ", not a logical"]);
  elseif (any (isnan (val(:))))
    reply = struct ("ok", false, "error", "the expression produced NaN, which is not a verdict");
  elseif (! isscalar (val))
    reply = struct ("ok", false, "error", sprintf ("the expression produced a %dx%d value, not a scalar; wrap it in all(...) or any(...) to say which you mean", rows (val), columns (val)));
  else
    reply = struct ("ok", true, "verdict", logical (val), "tol", tol, "exact", (tol == 0));
  endif
endfunction

function s = vf_quote (str)
  s = ["'" strrep(str, "'", "''") "'"];
endfunction

function s = vf_trim (str)
  lim = 4000;
  if (numel (str) > lim)
    s = [str(1:lim) sprintf("\n... [truncated]")];
  else
    s = str;
  endif
endfunction
