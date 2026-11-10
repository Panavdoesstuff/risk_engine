# Worked example: margin on a short Nifty straddle

This is the M2 gate. `MarginExampleTest` asserts that the engine reproduces every number on this page to the rupee (in practice to the paisa). The numbers were produced by [`scripts/straddle_example.py`](../scripts/straddle_example.py), which shares **no code** with the Java engine (its normal CDF comes from Python's `math.erfc`). Run it to regenerate them.

The parameters are illustrative, not NSE's actual values for that day. They are fixed in the test, so changing `margin.properties` doesn't silently change what this page proves.

## Inputs

| | |
| --- | --- |
| Position | Short 2 lots (50 units) of NIFTY 13-Jun-2024 23000 CE **and** short 50 units of the 23000 PE |
| Spot S | 23,000.00 |
| India VIX | 20.00, so σ = 20.00 / 100 = **0.20** |
| Valuation | 6 Jun 2024 15:30 IST. Expiry is 13 Jun 2024 15:30 IST, so T = **7/365** years |
| Risk-free rate r | 0.069 |
| Dividend yield q | 0.012 |
| Price scan range | 9% (scenarios move spot by ±3%, ±6%, ±9%) |
| Volatility scan range | ±0.04 (4 vol points): 0.24 up, 0.16 down |
| Extreme move | 2 × 9% = ±18%, loss counted at 35% |
| Short option minimum | 3% of underlying notional of short options |
| Exposure margin | 2% of notional of futures and short options |
| Cash / collateral | ₹2,40,000 / ₹0 |

## Step 1: price both options now

Black-Scholes with dividend yield:

```
d1 = [ln(S/K) + (r − q + σ²/2)·T] / (σ·√T)        d2 = d1 − σ·√T
call = S·e^(−qT)·N(d1) − K·e^(−rT)·N(d2)
put  = K·e^(−rT)·N(−d2) − S·e^(−qT)·N(−d1)
```

| | value |
| --- | ---: |
| √T | 0.1384849529 |
| ln(S/K) | 0 (at the money) |
| (r − q + σ²/2)·T = (0.069 − 0.012 + 0.02) × 7/365 | 0.0014767123 |
| σ·√T | 0.0276969906 |
| d1 = 0.0014767123 / 0.0276969906 | 0.0533167069 |
| d2 | 0.0256197163 |
| N(d1), N(d2) | 0.5212602155, 0.5102196700 |
| e^(−qT), e^(−rT) | 0.9997698895, 0.9986775875 |
| **call** = 23000 × 0.99977 × 0.52126 − 23000 × 0.99868 × 0.51022 | **266.6923** |
| **put** | **241.5694** |
| straddle | 508.2617 |

Check (put-call parity): c − p = 25.122946, and S·e^(−qT) − K·e^(−rT) = 25.122946. ✓

Sanity check: an ATM straddle is worth about 0.8 × S × σ × √T = 0.8 × 23000 × 0.2 × 0.13848 ≈ 509.6. ✓

## Step 2: revalue in 16 scenarios

For each scenario, reprice both options at the scenario spot and vol (same T). A long unit loses (price now − scenario price) × weight. This account is short 50 of each, so its loss is:

```
account loss = 50 × (call_scenario − 266.6923) × w  +  50 × (put_scenario − 241.5694) × w
```

| # | spot | vol | call | put | weight | account loss (₹) |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 23,000.00 | 0.24 | 317.4417 | 292.3188 | 1.00 | 5,074.94 |
| 2 | 23,000.00 | 0.16 | 215.9575 | 190.8345 | 1.00 | −5,073.49 |
| 3 | 23,690.00 | 0.24 | 789.6909 | 74.7267 | 1.00 | 17,807.79 |
| 4 | 23,690.00 | 0.16 | 734.6190 | 19.6549 | 1.00 | 12,300.61 |
| 5 | 22,310.00 | 0.24 | 77.8525 | 742.5707 | 1.00 | 15,608.07 |
| 6 | 22,310.00 | 0.16 | 21.6305 | 686.3488 | 1.00 | 9,985.88 |
| 7 | 24,380.00 | 0.24 | 1,416.4309 | 11.6255 | 1.00 | 45,989.74 |
| 8 | 24,380.00 | 0.16 | 1,405.4004 | 0.5950 | 1.00 | 44,886.68 |
| 9 | 21,620.00 | 0.24 | 9.8289 | 1,364.3884 | 1.00 | 43,297.78 |
| 10 | 21,620.00 | 0.16 | 0.4541 | 1,355.0136 | 1.00 | 42,360.30 |
| **11** | **25,070.00** | **0.24** | **2,095.7217** | **1.0750** | **1.00** | **79,426.75** |
| 12 | 25,070.00 | 0.16 | 2,094.6516 | 0.0050 | 1.00 | 79,319.74 |
| 13 | 20,930.00 | 0.24 | 0.5459 | 2,044.9466 | 1.00 | 76,861.54 |
| 14 | 20,930.00 | 0.16 | 0.0014 | 2,044.4021 | 1.00 | 76,807.09 |
| 15 | 27,140.00 | 0.20 | 4,164.1703 | 0.0000 | 0.35 | 63,978.40 |
| 16 | 18,860.00 | 0.20 | 0.0000 | 4,113.9244 | 0.35 | 63,099.10 |

**Scenario 11 by hand** (spot +9%, vol up):

| | value |
| --- | ---: |
| ln(25070 / 23000) | 0.0861776962 |
| σ·√T = 0.24 × 0.1384849529 | 0.0332363887 |
| d1 = (0.0861776962 + (0.069 − 0.012 + 0.0288) × 7/365) / 0.0332363887 | 2.6423802077 |
| d2 | 2.6091438190 |
| N(d1), N(d2) | 0.9958837209, 0.9954615458 |
| S·e^(−qT), K·e^(−rT) | 25,064.2311, 22,969.5845 |
| call = 25064.2311 × 0.9958837 − 22969.5845 × 0.9954615 | 2,095.7217 |
| put (via parity, or the formula) | 1.0750 |
| loss = 50 × (2095.7217 − 266.6923) + 50 × (1.0750 − 241.5694) = 91,451.47 − 12,024.72 | **79,426.75** |

Note that the extreme scenarios (15, 16) lose *more* in raw terms (₹1,82,795 and ₹1,80,283), but only 35% of that counts.

## Step 3: margin

| Line | Formula | ₹ |
| --- | --- | ---: |
| Worst scenario loss | max over the 16 rows (scenario 11) | 79,426.75 |
| Short option minimum | 3% × 23,000 × (50 + 50) | 69,000.00 |
| SPAN requirement | max(69,000.00, 79,426.75) | 79,426.75 |
| Net option value | −50 × 266.6923 − 50 × 241.5694 | −25,413.09 |
| Risk requirement | max(0, 79,426.75 − (−25,413.09)) | 1,04,839.83 |
| Exposure margin | 2% × 23,000 × (50 + 50) | 46,000.00 |
| **Required** | 1,04,839.83 + 46,000.00 | **1,50,839.83** |
| **Available** | cash + collateral | **2,40,000.00** |
| **Utilisation** | 1,50,839.83 / 2,40,000.00 | **62.85%**: OK (below the 80% warning) |

Why the net option value is *subtracted*: a short option is a liability. To get out you'd pay ₹25,413 to buy both legs back, then still face the ₹79,427 scenario loss on top. For a long option the same line is a credit, and that's what caps a buyer's margin at zero once the premium is paid. See `docs/decisions.md`, M2 entry 23.

## What moves this number

- **Spot falls 5% with VIX at 30:** the same account goes to about 83% (WARNING). With ₹1,60,000 of cash it starts at 94% and is past 120% by then. `ReferenceMarginEngineTest` walks both through a crash.
- **Price scan range** is the biggest lever. It is still a guess (`margin.properties`).
