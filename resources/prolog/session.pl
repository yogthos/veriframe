% veriframe - a claim-first verification harness
% Copyright (C) 2026 Dmitri Sotnikov
%
% This program is free software: you can redistribute it and/or
% modify it under the terms of the GNU General Public License as
% published by the Free Software Foundation, either version 3 of
% the License, or (at your option) any later version.
%
% This program is distributed in the hope that it will be useful,
% but WITHOUT ANY WARRANTY; without even the implied warranty of
% MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
% GNU General Public License for more details.
%
% You should have received a copy of the GNU General Public
% License along with this program. If not, see
% <https://www.gnu.org/licenses/>.

% A long-lived SWI-Prolog session for the harness.
%
% One JSON object per line on stdin, one JSON object per line on stdout.
% Line framing is safe because JSON escapes newlines inside strings.
%
% This file is consulted at startup, before any harness term reaches the
% reader, which is not optional: #> and #< are operators library(clpfd)
% defines at load time, so a term that both loads clpfd and uses it is a
% syntax error. Everything the session needs to parse must be in scope here.
%
% Commands:
%   {"op":"ping"}
%   {"op":"assert","code":"<prolog text>","name":"<optional>"}
%   {"op":"retract","name":"<name>"}
%   {"op":"query","goal":"<goal>","limit":N,"timeout":Seconds}
%
% Replies always carry "ok". A failed goal is ok:true with an empty answer
% list; a goal that threw is ok:false with the error rendered as a string.
% That distinction matters: "your claim is false" and "your encoding is
% broken" are different messages to send back to a model.

% clpfd FIRST and on its own directive. It DEFINES #= / #< / ins, and a term is
% read in full before it runs, so anything using those operators in the same
% term is unparseable.
:- use_module(library(clpfd)).
% Recent SWI prints "Library was moved: library(http/json) --> library(json)"
% and loads it anyway through the compatibility alias. Do not "fix" this to
% library(json): that name does not exist on the SWI in Ubuntu's repos (9.0.4),
% which is what CI installs, so the rename would break the older version to
% silence a warning on the newer one.
:- use_module(library(http/json)).
:- use_module(library(time)).
:- use_module(library(lists)).
:- use_module(library(apply)).

:- dynamic session_clause/2.

% --- reply plumbing ---------------------------------------------------------

reply(Reply) :-
    with_output_to(string(S), json_write_dict(current_output, Reply, [width(0)])),
    format("~w~n", [S]),
    flush_output.

% The reader prints its own message to user_error before throwing, even under
% syntax_errors(error). We return the error in the reply, so the duplicate on
% stderr is noise that would otherwise be interleaved with nothing useful.
:- multifile user:message_hook/3.
user:message_hook(error(syntax_error(_), _), error, _) :- !.

% Render a thrown term for the model. Deliberately NOT via print_message:
% that writes to user_error rather than to the stream with_output_to captures,
% so it pollutes stderr and still yields an empty string. Naming the common
% ISO error shapes gives a better message than the raw term anyway.
error_string(error(syntax_error(What), _), S) :- !,
    format(string(S), "Syntax error: ~w. Check operators, parentheses, and that the term is well-formed.", [What]).
error_string(error(existence_error(procedure, PI), _), S) :- !,
    format(string(S), "Unknown procedure: ~w. It was never asserted, or the arity differs from what you called.", [PI]).
error_string(error(type_error(Type, Value), _), S) :- !,
    format(string(S), "Type error: expected ~w, got ~q.", [Type, Value]).
error_string(error(instantiation_error, _), S) :- !,
    S = "Instantiation error: a variable was unbound where a concrete term was required.".
error_string(error(evaluation_error(What), _), S) :- !,
    format(string(S), "Evaluation error: ~w.", [What]).
error_string(E, S) :-
    term_string(E, S).

% --- clause loading ---------------------------------------------------------

read_clauses(S, Clauses) :-
    read_term(S, T, [syntax_errors(error)]),
    (   T == end_of_file
    ->  Clauses = []
    ;   Clauses = [T|Rest],
        read_clauses(S, Rest)
    ).

% A directive runs and records nothing; a clause is asserted and, when the
% caller named it, its reference is remembered so retract can take it back.
add_clause(_Name, (:- G)) :- !, call(G).
add_clause(Name, Clause) :-
    assertz(Clause, Ref),
    (   Name == null
    ->  true
    ;   assertz(session_clause(Name, Ref))
    ).

% --- answers ----------------------------------------------------------------

render_binding(Name=Value, Name-S) :-
    term_string(Value, S).

format_bindings([], "true").
format_bindings([P|Ps], S) :-
    maplist(format_pair, [P|Ps], Parts),
    atomic_list_concat(Parts, ', ', A),
    atom_string(A, S).

format_pair(Name-Value, P) :-
    format(atom(P), '~w = ~w', [Name, Value]).

render_solution(Bindings, _{bindings:Dict, formatted:F}) :-
    maplist(render_binding, Bindings, Pairs),
    dict_create(Dict, json, Pairs),
    format_bindings(Pairs, F).

% --- dispatch ---------------------------------------------------------------

handle(Cmd, Reply) :-
    get_dict(op, Cmd, Op),
    dispatch(Op, Cmd, Reply).

dispatch("ping", _, _{ok:true, pong:true}).

dispatch("assert", Cmd, Reply) :-
    get_dict(code, Cmd, Code),
    (   get_dict(name, Cmd, Name0), Name0 \== null
    ->  atom_string(Name, Name0)
    ;   Name = null
    ),
    setup_call_cleanup(
        open_string(Code, S),
        read_clauses(S, Clauses),
        close(S)),
    maplist(add_clause(Name), Clauses),
    length(Clauses, N),
    Reply = _{ok:true, clauses:N}.

dispatch("retract", Cmd, Reply) :-
    get_dict(name, Cmd, Name0),
    atom_string(Name, Name0),
    findall(Ref, session_clause(Name, Ref), Refs),
    forall(member(R, Refs), catch(erase(R), _, true)),
    retractall(session_clause(Name, _)),
    length(Refs, N),
    Reply = _{ok:true, erased:N}.

dispatch("query", Cmd, Reply) :-
    get_dict(goal, Cmd, GoalS),
    (   get_dict(limit, Cmd, Limit0) -> Limit = Limit0 ; Limit = 100 ),
    (   get_dict(timeout, Cmd, T0) -> Timeout = T0 ; Timeout = 10 ),
    term_string(Goal, GoalS, [variable_names(Vars), syntax_errors(error)]),
    Cap is Limit + 1,
    (   catch(
            call_with_time_limit(
                Timeout,
                once(findnsols(Cap, Vars, Goal, Sols))),
            Err, true)
    ->  true
    ;   Sols = [], Err = none
    ),
    (   nonvar(Err), Err \== none
    ->  (   Err == time_limit_exceeded
        ->  Reply = _{ok:false, timeout:true,
                      error:"Goal exceeded the time limit."}
        ;   error_string(Err, ES),
            Reply = _{ok:false, error:ES}
        )
    ;   length(Sols, NS),
        (   NS > Limit
        ->  Truncated = true, length(Kept, Limit), append(Kept, _, Sols)
        ;   Truncated = false, Kept = Sols
        ),
        maplist(render_solution, Kept, Answers),
        Reply = _{ok:true, answers:Answers, truncated:Truncated}
    ).

% --- main loop --------------------------------------------------------------

handle_line(Line) :-
    (   catch(( atom_json_dict(Line, Cmd, [value_string_as(string)]),
                handle(Cmd, R) ),
              E,
              ( error_string(E, ES), R = _{ok:false, error:ES} ))
    ->  Reply = R
    ;   Reply = _{ok:false, error:"Command failed without an error."}
    ),
    reply(Reply).

loop :-
    read_line_to_string(user_input, Line),
    (   Line == end_of_file
    ->  true
    ;   handle_line(Line),
        loop
    ).

main :- loop.
