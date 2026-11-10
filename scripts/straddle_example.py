#!/usr/bin/env python3
"""Independent re-computation of the hand-worked short straddle in docs/margin-example-short-straddle.md.

Shares no code with the Java engine: normal CDF from math.erfc, Black-Scholes written out from the
textbook formula. Run it to regenerate the numbers in the doc:  python3 scripts/straddle_example.py
"""
import math

# ---- inputs (must match the doc and MarginExampleTest)
S, K = 23000.0, 23000.0
VIX = 20.0
sigma = VIX / 100
T = 7 / 365                     # valuation 6 Jun 2024 15:30 IST, expiry 13 Jun 2024 15:30 IST
r, q = 0.069, 0.012
QTY = 50                        # short 2 lots x 25 of each leg
PSR, VSR, EXT_MULT, EXT_COV = 0.09, 0.04, 2.0, 0.35
SOMC_RATE, EXPOSURE_RATE, MIN_VOL = 0.03, 0.02, 0.01
CASH, COLLATERAL = 240000.0, 0.0


def N(x):
    return 0.5 * math.erfc(-x / math.sqrt(2))


def bs(kind, s, k, t, v):
    d1 = (math.log(s / k) + (r - q + v * v / 2) * t) / (v * math.sqrt(t))
    d2 = d1 - v * math.sqrt(t)
    if kind == "C":
        return s * math.exp(-q * t) * N(d1) - k * math.exp(-r * t) * N(d2), d1, d2
    return k * math.exp(-r * t) * N(-d2) - s * math.exp(-q * t) * N(-d1), d1, d2


c0, d1, d2 = bs("C", S, K, T, sigma)
p0, _, _ = bs("P", S, K, T, sigma)
print("## Step 1: price the two options now")
print(f"sqrt(T) = {math.sqrt(T):.10f}")
print(f"d1 = {d1:.10f}   d2 = {d2:.10f}")
print(f"N(d1) = {N(d1):.10f}   N(d2) = {N(d2):.10f}   N(-d1) = {N(-d1):.10f}   N(-d2) = {N(-d2):.10f}")
print(f"e^(-qT) = {math.exp(-q*T):.10f}   e^(-rT) = {math.exp(-r*T):.10f}")
print(f"call = {c0:.4f}   put = {p0:.4f}   straddle = {c0+p0:.4f}")
print(f"parity check c - p = {c0-p0:.6f} vs S e^-qT - K e^-rT = {S*math.exp(-q*T)-K*math.exp(-r*T):.6f}")

moves = [0, 0, 1/3, 1/3, -1/3, -1/3, 2/3, 2/3, -2/3, -2/3, 1, 1, -1, -1]
vols = [1, -1] * 7
rows = []
for i in range(16):
    if i < 14:
        f, dv, w = 1 + moves[i] * PSR, vols[i], 1.0
    else:
        f, dv, w = 1 + (EXT_MULT * PSR if i == 14 else -EXT_MULT * PSR), 0, EXT_COV
    s_i = S * f
    v_i = max(MIN_VOL, sigma + dv * VSR)
    c_i = bs("C", s_i, K, T, v_i)[0]
    p_i = bs("P", s_i, K, T, v_i)[0]
    # long-unit loss = (now - scenario) x weight; the account is SHORT 50 of each, so its loss is -50 x that
    loss_c, loss_p = (c0 - c_i) * w, (p0 - p_i) * w
    acct = -QTY * loss_c + -QTY * loss_p
    rows.append((i + 1, s_i, v_i, c_i, p_i, w, acct))

print("\n## Step 2: 16 scenarios")
print("| # | spot | vol | call | put | weight | account loss (Rs) |")
print("|---|---:|---:|---:|---:|---:|---:|")
for n, s_i, v_i, c_i, p_i, w, acct in rows:
    print(f"| {n} | {s_i:,.2f} | {v_i:.2f} | {c_i:,.4f} | {p_i:,.4f} | {w:.2f} | {acct:,.2f} |")

worst = max(0.0, max(r_[6] for r_ in rows))
worst_n = max(rows, key=lambda r_: r_[6])[0]
somc = SOMC_RATE * S * QTY * 2
span = max(somc, worst)
nov = -QTY * c0 - QTY * p0
risk = max(0.0, span - nov)
exposure = EXPOSURE_RATE * S * QTY * 2
required = risk + exposure
available = CASH + COLLATERAL
util = required / available
print("\n## Step 3: margin")
for name, v in [("worst scenario loss", worst), ("short option minimum", somc), ("span requirement", span),
                ("net option value", nov), ("risk requirement", risk), ("exposure margin", exposure),
                ("required", required), ("available", available)]:
    print(f"{name:22s} = {v:,.2f}")
print(f"worst scenario = #{worst_n}")
print(f"utilisation = {util:.6f} ({util*100:.2f}%)")
