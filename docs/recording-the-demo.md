# Recording the 60-second demo GIF

What the GIF should show: the Grafana dashboard while 4 June 2024 replays. The replay clock runs from 09:16 to 15:30 IST, the Nifty falls, VIX climbs, and the margin-alert bars and square-off counters jump. It should be about 60 seconds, loop cleanly, and stay under 10 MB so GitHub shows it inline.

## Before you start

1. Close everything heavy. The shard host and Redpanda need the cores, and a stutter shows in the recording. Check `uptime`: aim for a load average under 3.
2. Put the real data in `data/raw/` (see [data/README.md](../data/README.md)). Without it the demo runs on synthetic prices and prints a SYNTHETIC banner, which you don't want in a README GIF.
3. Run the demo once without recording, so the Docker image is built and the first-run pause doesn't end up in the GIF:
   ```bash
   DEMO_NO_OPEN=1 ./scripts/demo.sh
   docker compose -p risk-demo --profile app down -v
   ```

## Record

1. Set the browser window to 1600 × 900 (a 16:9 shape that stays readable when GitHub scales it down). Use the browser's dark theme, which Grafana's default matches.
2. Start a screen recorder on that window only:
   - macOS: **Cmd-Shift-5 → "Record Selected Portion"**, drawn round the browser's content area (leave out the tabs and address bar).
   - Or [Kap](https://getkap.co) (free; exports GIF directly).
3. Run the demo. It opens the dashboard in kiosk mode and waits 8 seconds before the market opens, which gives you time to start recording:
   ```bash
   DEMO_PACE_MS=120 ./scripts/demo.sh
   ```
   At 120 ms a minute, the 375 minutes take 45 seconds. With the 8-second lead-in and a few seconds of the final state, that comes to about 60 seconds.
   If the browser opened behind other windows, bring it forward during the 8 seconds. To open it yourself instead, run with `DEMO_NO_OPEN=1` and open the URL the script prints under "Grafana" before the replay line appears.
4. Stop recording when the replay clock shows 15:30 and the "Square-offs" counter has stopped moving.
5. Stop the stack: `docker compose -p risk-demo --profile app down -v`.

## Turn the video into a GIF

macOS's recorder saves a `.mov`. Convert it with ffmpeg (`brew install ffmpeg`), using a palette so Grafana's dark colours don't band:

```bash
ffmpeg -i demo.mov -vf "fps=12,scale=1200:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" -loop 0 docs/demo.gif
```

- Check the size with `ls -lh docs/demo.gif`. Over 10 MB, drop to `fps=10` or `scale=1000:-1`.
- Trim the start or end with `-ss 2 -t 60` before `-i` / after it as needed.

Then add it near the top of the README, under the opening paragraph:

```markdown
![The crash replay on the Grafana dashboard](docs/demo.gif)
```

## What to point at, if you narrate or caption it

- **Replay clock (IST):** this is the market's clock, from the log, not the laptop's.
- **09:16–09:32:** the gap down; warnings at once, margin calls three ticks later, the first square-off wave at 09:32.
- **Around 11:00, and 12:00–12:30:** the next waves, as the Nifty takes each leg down and VIX passes 30. Nothing new is squared off after the 12:29 low.
- **Legs sent vs square-offs:** most accounts need one leg. The broker in compose is the chaos broker, so a few legs are rejected and re-planned.
- The numbers differ from the [crash-day report](crash-day-2024-06-04.md): the demo seeds 20,000 accounts (`DEMO_ACCOUNTS`) and runs with a misbehaving broker; the report uses 100,000 accounts and a perfect one.
