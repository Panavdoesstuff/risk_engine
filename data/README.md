# Market data

Nothing in `data/raw/`, `data/processed/` or `data/seed/` is committed. Everything here is rebuilt from the raw files with the commands below.

## What you need in `data/raw/`

| File | Used for | Where to get it |
| --- | --- | --- |
| `fo03JUN2024bhav.csv` | Instruments + 3 June closing prices | NSE archives, F&O bhavcopy for 03-Jun-2024 (legacy format; zipped). |
| `fo_mktlots.csv` **as of June 2024** | Lot sizes per expiry month | NSE publishes only the *current* file. Get a June 2024 copy (e.g. from a web archive snapshot) or check NSE lot-size circulars. Lot sizes changed during 2024; do not use today's file. |
| Nifty 50 1-minute bars, 3–4 June 2024 | Replay ticks | NSE does not publish free historical minute bars. Broker historical-candle APIs or a public dataset; note the source in `docs/decisions.md`. |
| Bank Nifty 1-minute bars, same days | *Not needed until after M5* (Nifty only until then) | As above. |
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
$R bars --in data/raw/<vix-file>.csv       --from 2024-05-01 --to 2024-06-04 --out data/processed/INDIAVIX.csv

# Cash is sized with the margin engine, so the seed needs the 3 June India VIX close:
VIX=$(grep '^2024-06-03' data/processed/INDIAVIX.csv | tail -1 | cut -d, -f5)
$R seed --instruments data/processed/instruments-2024-06-03.csv --vix "$VIX" --out data/seed --seed 42 --accounts 1000

# Replay ticks for 4 June, written to every partition log (run once; it refuses to overwrite):
$R ticks --bars data/processed/NIFTY-1m.csv --vix data/processed/INDIAVIX.csv \
         --from 2024-06-04 --to 2024-06-04 --partitions 4 --out data/processed/logs
```

With daily VIX, the VIX file must include 3 June: ticks on 4 June use the last value known at the time, which is 3 June's close.

`bars` accepts a header with OPEN/HIGH/LOW/CLOSE plus DATETIME (or TIMESTAMP), or DATE + TIME, or DATE alone (daily). Timestamps without an offset are read as IST. Each output gets a `.meta` file with the resolution, row count, missing session minutes and a SHA-256.

`seed` prints the SHA-256 of each file it writes; run it twice and the hashes match. `book.meta` records the margin config hash, spot and VIX it was sized with: change the margin config and the cash balances change.
