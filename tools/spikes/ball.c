/* Ball arithmetic probe: does an enclosure PROVE a bound that floats cannot?
 *
 * The test case is deliberately one where double precision lies. Compute
 * zeta(2) - pi^2/6, which is exactly zero, and ask whether the result is
 * distinguishable from zero. Then a case where the bound is real and strict:
 * establish 3.14159 < pi < 3.1416 rigorously from the enclosure alone.
 */
#include <stdio.h>
#include <flint/arb.h>

int main(void) {
    slong prec = 128;
    arb_t zeta2, pi, pi2_6, diff, lo, hi;
    arb_init(zeta2); arb_init(pi); arb_init(pi2_6);
    arb_init(diff); arb_init(lo); arb_init(hi);

    /* zeta(2) and pi^2/6 */
    arb_zeta_ui(zeta2, 2, prec);
    arb_const_pi(pi, prec);
    arb_sqr(pi2_6, pi, prec);
    arb_div_ui(pi2_6, pi2_6, 6, prec);
    arb_sub(diff, zeta2, pi2_6, prec);

    printf("zeta(2)      = "); arb_printn(zeta2, 30, 0); printf("\n");
    printf("pi^2/6       = "); arb_printn(pi2_6, 30, 0); printf("\n");
    printf("difference   = "); arb_printn(diff, 30, 0); printf("\n");
    printf("  contains 0 : %s\n", arb_contains_zero(diff) ? "yes" : "no");

    /* A strict bound, certified: is 3.14159 < pi < 3.1416? */
    arb_set_str(lo, "3.14159", prec);
    arb_set_str(hi, "3.1416", prec);
    printf("\n3.14159 < pi : %s\n", arb_lt(lo, pi) ? "PROVED" : "not established");
    printf("pi < 3.1416  : %s\n", arb_lt(pi, hi) ? "PROVED" : "not established");

    /* And one that is FALSE, to check it refuses rather than rounds into it */
    arb_set_str(hi, "3.14159", prec);
    printf("pi < 3.14159 : %s\n", arb_lt(pi, hi) ? "PROVED" : "not established (correct)");

    arb_clear(zeta2); arb_clear(pi); arb_clear(pi2_6);
    arb_clear(diff); arb_clear(lo); arb_clear(hi);
    return 0;
}
