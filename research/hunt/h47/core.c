/* h47 signal/exit state machine for the TQE 9 EMA + VWAP rules (see PREREG.md).
   Build: gcc -O2 -shared -fPIC -o core.so core.c
   One call = one signal variant on one bar series. Outputs one row per trade. */
#include <math.h>

typedef struct {
    int setup;      /* 0 A cross, 1 B hold, 2 C snapback */
    int slope;      /* A: 0 off, 1 with (flat or rising for longs), 2 literal (flat or falling for longs) */
    int volf;       /* A: volume filter */
    int hold_bars;  /* A: bars of hold (ceil(H/N)), 0 = none */
    int hours;      /* entries only when et_ok */
    int skip;       /* bits: 1 K1 EMA flat, 2 K2 chase, 4 K3 chop (A, B) */
    int window;     /* B: 1 = first 30 min after anchor, 2 = 30 min after a cross */
    double k;       /* C: stretch in ATRs */
    double f30, e15, d80, f60;
} Params;

static int skip_ok(const Params *p, int i, const double *ema, const double *vw, const double *atr,
                   const double *erng15, const double *dv60, int ncross) {
    if ((p->skip & 1) && erng15[i] < p->e15 * atr[i]) return 0;
    if ((p->skip & 2) && fabs(ema[i] - vw[i]) > p->d80 * atr[i]) return 0;
    if ((p->skip & 4) && fabs(dv60[i]) < p->f60 * atr[i] && ncross >= 3) return 0;
    return 1;
}

/* arrays length n. sess: session id or -1. msa: minutes from anchor to bar close. send: 1 on the last bar of a session.
   out arrays sized cap. Returns number of trades (or -1 if cap exceeded). */
int run(int n, const double *o, const double *h, const double *l, const double *c, const double *v,
        const double *ema, const double *vw, const int *sess, const int *msa, const int *send, const int *etok,
        const double *atr, const double *sopen, const double *dv30, const double *dv60, const double *erng15,
        const double *vavg, Params p, int cap,
        int *o_sig, int *o_ef, int *o_xf, int *o_side, double *o_ep, double *o_xp, int *o_type) {
    int nt = 0, pos = 0, ef = -1, sig = -1, psess = -1;
    double ep = 0;
    int pend = 0, pk = -1, run_ = 0;
    int ncross = 0, lastsess = -2;
    int lastbull = -1, lastbear = -1;   /* msa of the last cross in this session (B W2) */
    for (int i = 1; i < n - 1; i++) {
        int s = sess[i];
        /* ---- exits ---- */
        if (pos != 0 && i >= ef) {
            int xf = -1, typ = 0;
            double xp = 0;
            if (p.setup == 2) {
                double tg = vw[i - 1];
                if (pos > 0 && o[i] >= tg) { xf = i; xp = o[i]; typ = 1; }
                else if (pos < 0 && o[i] <= tg) { xf = i; xp = o[i]; typ = 1; }
                else if (pos > 0 && h[i] >= tg) { xf = i; xp = tg; typ = 1; }
                else if (pos < 0 && l[i] <= tg) { xf = i; xp = tg; typ = 1; }
            }
            if (xf < 0) {
                if ((pos > 0 && c[i] < ema[i]) || (pos < 0 && c[i] > ema[i])) { xf = i + 1; xp = o[i + 1]; typ = 0; }
                else if (send[i] || s != psess) { xf = i + 1; xp = o[i + 1]; typ = 2; }
            }
            if (xf >= 0) {
                if (nt >= cap) return -1;
                o_sig[nt] = sig; o_ef[nt] = ef; o_xf[nt] = xf; o_side[nt] = pos; o_ep[nt] = ep; o_xp[nt] = xp; o_type[nt] = typ;
                nt++; pos = 0;
                if (typ == 1) continue;   /* exited intrabar; take no new signal on this bar */
            }
        }
        if (s < 0) { pend = 0; continue; }
        if (s != lastsess) { lastsess = s; ncross = 0; pend = 0; lastbull = -1000000; lastbear = -1000000; }
        int same = (sess[i - 1] == s);
        /* ---- crosses ---- */
        int cross = 0;
        if (same) {
            if (ema[i] > vw[i] && ema[i - 1] <= vw[i - 1]) cross = 1;
            else if (ema[i] < vw[i] && ema[i - 1] >= vw[i - 1]) cross = -1;
        }
        int ncross_before = ncross;
        if (cross) {
            ncross++;
            if (cross > 0) lastbull = msa[i]; else lastbear = msa[i];
        }
        if (pos != 0 || send[i]) { if (p.setup == 0 && cross) pend = 0; continue; }
        int hours_ok = (!p.hours) || etok[i];
        int d = 0;
        if (p.setup == 0) {
            if (cross) {
                int ok = 1;
                if (p.slope == 1 && cross * dv30[i] < -p.f30 * atr[i]) ok = 0;
                if (p.slope == 2 && cross * dv30[i] > p.f30 * atr[i]) ok = 0;
                if (p.volf && !(v[i] > vavg[i])) ok = 0;
                pend = ok ? cross : 0; pk = i; run_ = 0;
            }
            if (pend != 0) {
                if (pend * (ema[i] - vw[i]) <= 0) { pend = 0; continue; }
                double lo = ema[i] < vw[i] ? ema[i] : vw[i], hi = ema[i] > vw[i] ? ema[i] : vw[i];
                if ((pend > 0 && c[i] > hi) || (pend < 0 && c[i] < lo)) run_++; else run_ = 0;
                int need = p.hold_bars > 1 ? p.hold_bars : 1;
                if (run_ >= need && msa[i] >= 30 && hours_ok && skip_ok(&p, i, ema, vw, atr, erng15, dv60, ncross - 1)) {
                    d = pend; pend = 0;
                } else if (i - pk >= p.hold_bars + 3) pend = 0;
            }
        } else if (p.setup == 1) {
            if (!same) continue;
            for (int dd = -1; dd <= 1; dd += 2) {
                int inwin;
                if (p.window == 1) inwin = msa[i] <= 30;
                else inwin = (msa[i] - (dd > 0 ? lastbull : lastbear)) <= 30;
                if (!inwin) continue;
                if (dd * (ema[i] - vw[i]) <= 0) continue;
                if (!(dd * (c[i] - ema[i]) > 0)) continue;
                if (dd > 0 && !(l[i] <= ema[i])) continue;
                if (dd < 0 && !(h[i] >= ema[i])) continue;
                if (!(dd * (c[i] - vw[i]) > 0)) continue;
                if (!(dd * (c[i - 1] - ema[i - 1]) > 0)) continue;
                if (p.window == 1 && !(dd * (c[i] - sopen[i]) > 0)) continue;
                if (!skip_ok(&p, i, ema, vw, atr, erng15, dv60, ncross_before)) continue;
                d = dd; break;
            }
        } else {
            if (!same || msa[i] < 30 || !hours_ok) continue;
            if (vw[i - 1] - c[i - 1] > p.k * atr[i] && c[i - 1] < ema[i - 1] && c[i] > ema[i] && c[i] > o[i] && c[i] < vw[i]) d = 1;
            else if (c[i - 1] - vw[i - 1] > p.k * atr[i] && c[i - 1] > ema[i - 1] && c[i] < ema[i] && c[i] < o[i] && c[i] > vw[i]) d = -1;
        }
        if (d != 0) { pos = d; sig = i; ef = i + 1; ep = o[i + 1]; psess = s; }
    }
    return nt;
}
