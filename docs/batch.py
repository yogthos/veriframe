import json,sys
from chi import instances, clique_lb, z3_colourable, verify
res=[]
probs=instances()
print(f'settling {len(probs)} circulants not covered by Barajas-Serra',flush=True)
for i,(n,S,slug) in enumerate(probs):
    lb=clique_lb(n,S); chi=None; lower=None
    for k in range(max(2,lb), lb+5):
        v,col=z3_colourable(n,S,k,timeout=180)
        if v=='sat':
            if not verify(n,S,col):
                print(f'  !! model failed verification for C({n};{S}) k={k}',flush=True); break
            chi=k; break
        elif v=='unsat':
            lower=k+1
        else:
            print(f'  C({n}; {S}) k={k}: z3 {v} -- inconclusive',flush=True); break
    ok = (chi is not None) and (lower is None or lower==chi)
    print(f'[{i+1}/{len(probs)}] C({n}; {",".join(map(str,S))})  4bc={4*S[1]*S[2]}  '
          f'chi = {chi}  (unsat at {chi-1 if chi else "?"}, colouring verified)  {"OK" if ok else "CHECK"}',flush=True)
    res.append({'n':n,'S':S,'slug':slug,'chi':chi,'settled':ok})
json.dump(res,open('chi_results.json','w'),indent=1)
done=[r for r in res if r['settled']]
print(f'\nsettled {len(done)}/{len(res)}',flush=True)
