import json,re,subprocess,tempfile,os,sys,random
def instances():
    it=json.load(open('idx.json'))['items']
    op=[x for x in it if x.get('resolution_state')=='open' and not x.get('solution_claimed')]
    out=[]
    for x in op:
        if 'circulant-graphs' not in (x.get('domain_tags') or []): continue
        m=re.search(r'/(\d+)\\mathbb', x['statement'])
        ss=sorted(set(int(t) for t in re.findall(r'\\pm(\d+)', x['statement'])))
        if m and ss and len(ss)==3:
            n=int(m.group(1)); a,b,c=ss
            if n < 4*b*c: out.append((n,ss,x['slug']))
    return sorted(out)

def clique_lb(n,S):
    """exact max clique through vertex 0 (graph is vertex-transitive)"""
    D=set()
    for s in S: D.add(s); D.add(n-s)
    best=1
    verts=sorted(D)
    # cliques are small; brute force subsets of N(0) up to size 4
    import itertools
    for r in range(1,5):
        for comb in itertools.combinations(verts,r):
            q=(0,)+comb
            if all(((y-x)%n in D) for i,x in enumerate(q) for y in q[i+1:]):
                best=max(best,len(q))
    return best

def z3_colourable(n,S,k,timeout=120):
    lines=[f"(set-option :timeout {timeout*1000})"]
    for v in range(n):
        lines.append(f"(declare-const c{v} Int)")
        lines.append(f"(assert (and (>= c{v} 0) (< c{v} {k})))")
    lines.append("(assert (= c0 0))")               # symmetry break
    seen=set()
    for v in range(n):
        for s in S:
            u=(v+s)%n
            e=(min(u,v),max(u,v))
            if e in seen: continue
            seen.add(e)
            lines.append(f"(assert (not (= c{e[0]} c{e[1]})))")
    lines.append("(check-sat)")
    lines.append("(get-model)")
    with tempfile.NamedTemporaryFile('w',suffix='.smt2',delete=False) as f:
        f.write("\n".join(lines)); path=f.name
    try:
        r=subprocess.run(['z3',path],capture_output=True,text=True,timeout=timeout+30)
        out=r.stdout
        verdict=out.strip().split("\n")[0].strip()
        if verdict=='sat':
            col={}
            for m in re.finditer(r'\(define-fun c(\d+) \(\) Int\s+(-?\d+)\)',out):
                col[int(m.group(1))]=int(m.group(2))
            return 'sat',col
        return verdict,None
    finally:
        os.unlink(path)

def verify(n,S,col):
    assert len(col)==n, f'model covers {len(col)} of {n}'
    for v in range(n):
        for s in S:
            if col[v]==col[(v+s)%n]: return False
    return True

if __name__=='__main__':
    probs=instances()
    print(f'{len(probs)} three-distance circulants NOT covered by Barajas-Serra (N < 4bc)',flush=True)
    n,S,slug=probs[int(sys.argv[1])] if len(sys.argv)>1 else probs[0]
    lb=clique_lb(n,S)
    print(f'C({n}; {",".join(map(str,S))})  4bc={4*S[1]*S[2]}  clique lower bound = {lb}',flush=True)
    for k in range(lb, lb+4):
        v,col=z3_colourable(n,S,k)
        print(f'   k={k}: z3 says {v}',flush=True)
        if v=='sat':
            print('   verified proper colouring:', verify(n,S,col),flush=True)
            print(f'   => chi = {k} (unsat at {k-1}, verified colouring at {k})' if k>lb else f'   => chi = {k} = clique bound',flush=True)
            break
