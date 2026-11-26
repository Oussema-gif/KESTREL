# FORGE OPS comfort palette and signal graphics

The update uses a softer graphite background, warm gray text, sage selection, and muted amber and violet protocol accents. Loading, failures, stale readings, and active tabs retain visible text or indicators. Selectable items and buttons retain keyboard focus markers.

The Command Deck adds temperature, vibration, pressure, and energy cards. Each shows the latest recorded value, the observed range, and a sparkline for the minute ending at the latest plotted sample. Each card uses its own observed range and unit. The cards reuse the selected machine's existing in-memory history. They show **No readings** before the first sample and separate line segments after a gap of five seconds or more. The existing freshness label tells you when the retained readings become stale.

## Apply on Fedora

Use the checkout with the FORGE OPS top bar already applied. Save the ZIP to Downloads and run from Kestrel's repository root:

```bash
cd ~/Desktop/Projects/KESTREL
unzip -n ~/Downloads/kestrel-forge-ops-comfort.zip -d . &&
python3 -B scripts/apply-forge-comfort.py --check &&
python3 -B scripts/apply-forge-comfort.py &&
./mvnw -B -pl desktop-client -am install &&
./mvnw -pl desktop-client javafx:run
```

Keep the existing backends running. Open **Command Deck** and select an online machine. Verify all four plots. Switch machines and verify that the graphics load with the new machine's readings. To check gaps, set the simulator to **OFFLINE** for at least six seconds, return it to **NORMAL**, and open Command Deck. The restored signal should start a separate line segment. Resize to the minimum window width and check that all cards remain readable.

Rebuild the Fedora app image with `./scripts/package-desktop.sh` once the GUI check passes.
