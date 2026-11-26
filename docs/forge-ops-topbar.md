# Kestrel · FORGE OPS desktop refresh

The command header keeps the machine selector and session actions in view. The second row holds all seven navigation tabs. At narrower widths, the tab strip can scroll horizontally and follows keyboard navigation. The protocol rail stays at the bottom across screens.

| Area | UI change |
|---|---|
| Navigation | Horizontal tabs, animated selected indicator, visible keyboard focus |
| Content | Layered graphite surfaces, larger page headings, higher contrast data and input controls |
| Tables and charts | Clearer column headers, hover and selection states, subdued chart grid |
| Status | Separate presentation for connection states, loading, empty results, and failures |
| Motion | A short page entrance, a staggered reveal for the first panels, and a sliding tab indicator. Settings → Reduce motion disables these effects. |

## Apply on Fedora

From the verified Phase 10 checkout, save the ZIP to Downloads and run:

```bash
cd ~/Desktop/Projects/KESTREL
unzip -n ~/Downloads/kestrel-forge-ops-topbar.zip -d . &&
python3 -B scripts/apply-forge-topbar.py --check &&
python3 -B scripts/apply-forge-topbar.py &&
./mvnw -B -pl desktop-client -am install
```

Start the backends with the Phase 9 scripts if they are stopped. Launch the desktop with `./mvnw -pl desktop-client javafx:run` from the repository root. After reviewing the GUI, build the updated Fedora app image with `./scripts/package-desktop.sh` and use the new path printed by the script.

The upgrade adds its own stylesheet over the existing FORGE OPS theme. It edits the shell layout, connects navigation to the existing Reduce motion setting, and adjusts the Phase 10 page entrance easing. Existing route handlers, session rules, service contracts, and workflow logic remain in their original modules.

## Review the upgrade

1. Sign in and open Command Deck. Verify that the machine switcher and sign-out controls are on the first row and that the seven tabs are in the top bar.
2. Click **Alerts**, **Work Orders**, and **Inventory**. Verify that the selected indicator tracks the active screen and follows rapid switches.
3. Focus a tab and press **Left**, **Right**, **Home**, and **End**. Verify that focus stays in the tab strip and that the selected screen opens. Resize the window to its minimum and verify that every tab stays reachable.
4. In **Settings**, enable **Reduce motion**, then navigate again. The page entrance and tab indicator should update immediately. Disable it to restore animation.
5. Open **Live Telemetry** and verify the live and stale labels. Check a table with data, row selection, and an empty result. Sign out and sign in again to verify that the tab strip follows the session.
6. Run `./scripts/phase9-smoke-test.sh --live --machine-code MACHINE-001` with the services running to check the reservation workflow against the demo stack, or use the jury walkthrough in `docs/kestrel-release.md`.
