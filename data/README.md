# Market data

Nothing in `data/raw/`, `data/processed/` or `data/seed/` is committed. Everything here is rebuilt from the raw files with the commands below.

## What you need in `data/raw/`

| File | Used for | Where to get it |
| --- | --- | --- |
| `fo03JUN2024bhav.csv` | Instruments + 3 June closing prices | NSE archives, F&O bhavcopy for 03-Jun-2024 (legacy format; zipped). |
| `fo_mktlots.csv` **as of June 2024** | Lot sizes per expiry month | NSE publishes only the *current* file. Get a June 2024 copy (e.g. from a web archive snapshot) or check NSE lot-size circulars. Lot sizes changed during 2024; do not use today's file. |
| Nifty 50 1-minute bars, 3–4 June 2024 | Replay ticks | NSE does not publish free historical minute bars. Broker historical-candle APIs or a public dataset; note the source in `docs/decisions.md`. |
| Bank Nifty 1-minute bars, same days | Replay ticks | As above. |
| India VIX (minute if you can, else daily) | Volatility input | Daily: NSE India VIX historical download. Minute: broker API. The loader records which one you used. |

If you can get the UDiFF-format bhavcopy for the date instead, it carries lot sizes itself (`NewBrdLotQty`) and `--lots` becomes an optional cross-check.

**Verify before quoting:** check the 4 June 2024 Nifty and VIX moves against NSE's own data before any number goes in the README (M7).

## Build the processed files

```bash
./gradlew -q :replayer:installDist
R=replayer/build/install/replayer/bin/replayer

$R instruments --bhavcopy data/raw/fo03JUN2024bhav.csv --lots data/raw/fo_mktlots.csv \
               --date 2024-06-03 --out data/processed/instruments-2024-06-03.csv

$R bars --in data/raw/<nifty-file>.csv     --from 2024-06-03 --to 2024-06-04 --out data/processed/NIFTY-1m.csv
$R bars --in data/raw/<banknifty-file>.csv --from 2024-06-03 --to 2024-06-04 --out data/processed/BANKNIFTY-1m.csv
$R bars --in data/raw/<vix-file>.csv       --from 2024-05-01 --to 2024-06-04 --out data/processed/INDIAVIX.csv

$R seed --instruments data/processed/instruments-2024-06-03.csv --out data/seed --seed 42 --accounts 1000
```

`bars` accepts a header with OPEN/HIGH/LOW/CLOSE plus DATETIME (or TIMESTAMP), or DATE + TIME, or DATE alone (daily). Timestamps without an offset are read as IST. Each output gets a `.meta` file with the resolution, row count, missing session minutes and a SHA-256.

`seed` prints the SHA-256 of each file it writes; run it twice and the hashes match.
