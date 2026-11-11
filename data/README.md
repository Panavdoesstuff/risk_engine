# Market data

Nothing in `data/raw/`, `data/processed/` or `data/seed/` is committed. Everything here is rebuilt from the raw files with the commands below.

## What you need in `data/raw/`

| File | Used for | Where it comes from |
| --- | --- | --- |
| `fo03JUN2024bhav.csv` | Instruments + 3 June closing and settlement prices | NSE archives, F&O bhavcopy for 03-Jun-2024 (legacy format). `fo04JUN2024bhav.csv` is kept for checking, not used. |
| `nifty_1min_2024-06-03_04.json` | Replay ticks | Upstox v3 historical candles (one minute) for Nifty 50, 3–4 June 2024: `{"status":"success","data":{"candles":[[time, open, high, low, close, volume, oi], …]}}`, newest first. |
| `vix_1min_2024-06-03_04.json` | Volatility input, intraday | The same, for India VIX. Joined "as of": each tick uses the latest VIX bar that has closed (decisions 43, 113). |

**Lot sizes** are not in `data/raw/`: no `fo_mktlots.csv` from April–July 2024 survives in any archive, so [`data/reference/nifty_lots_2024-06-03.csv`](reference/nifty_lots_2024-06-03.csv) (committed) gives 25 for every Nifty contract, citing NSE circular NSE/FAOP/61415 and cross-checked against the bhavcopy (decision 114).

`bars` also still accepts CSV: a header with OPEN/HIGH/LOW/CLOSE plus DATETIME (or TIMESTAMP), or DATE + TIME, or DATE alone (daily). Timestamps without an offset are read as IST. A UDiFF-format bhavcopy carries lot sizes itself (`NewBrdLotQty`), and `--lots` then becomes a cross-check.

**Checked before quoting** (decision 116): the bars' 4 June open (23,179.50), low (21,281.45) and VIX high (31.71) match NSE's published figures. NSE's official close (21,884.50) is a 30-minute weighted average and differs from the last minute bar (21,996.45); the replay never uses it.

## Build the processed files

```bash
./gradlew -q :replayer:installDist
R=replayer/build/install/replayer/bin/replayer

$R instruments --bhavcopy data/raw/fo03JUN2024bhav.csv --lots data/reference/nifty_lots_2024-06-03.csv \
               --date 2024-06-03 --out data/processed/instruments-2024-06-03.csv

$R bars --in data/raw/nifty_1min_2024-06-03_04.json --from 2024-06-03 --to 2024-06-04 --out data/processed/NIFTY-1m.csv
$R bars --in data/raw/vix_1min_2024-06-03_04.json   --from 2024-06-03 --to 2024-06-04 --out data/processed/INDIAVIX-1m.csv

# Cash is sized with the margin engine, so the seed needs the 3 June India VIX close: 20.94 (decision 116).
$R seed --instruments data/processed/instruments-2024-06-03.csv --vix 20.94 --out data/seed --seed 42 --accounts 100000

# Replay ticks for 4 June, written to every partition log (run once; it refuses to overwrite):
$R ticks --bars data/processed/NIFTY-1m.csv --vix data/processed/INDIAVIX-1m.csv \
         --from 2024-06-04 --to 2024-06-04 --partitions 4 --out data/processed/logs

# The crash-day report (docs/crash-day-2024-06-04.md) from those files:
./gradlew :report:crashDay
```

To write the ticks to Redpanda instead of files (for the engine in docker compose), replace `--partitions 4 --out ...` with `--redpanda localhost:19092`. It also refuses to run twice.

**No real data yet?** `$R demo --out data/demo` writes SYNTHETIC instruments and bars (made-up prices) that the same commands accept; `scripts/m4-e2e.sh` uses them. Never quote results from them.

With daily VIX instead, the VIX file must include 3 June: ticks on 4 June use the last value known at the time, which is 3 June's close.

Each `bars` output gets a `.meta` file with the resolution, row count, missing session minutes and a SHA-256.

`seed` prints the SHA-256 of each file it writes; run it twice and the hashes match. `book.meta` records the margin config hash, spot and VIX it was sized with: change the margin config and the cash balances change.
