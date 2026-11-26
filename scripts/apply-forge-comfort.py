"""Apply the low-glare FORGE OPS palette and Command Deck signal graphics."""
import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
DESKTOP = ROOT / "desktop-client/src/main"
JAVA = DESKTOP / "java/com/kestrel/desktop"
THEME = DESKTOP / "resources/com/kestrel/desktop/forge-ops.css"
MARKER = "/* FORGE OPS comfort palette and signal graphics */"

PALETTE = {
    "#0b1017": "#11171e",  # Canvas
    "#121c28": "#19222b",  # Cards and tables
    "#182535": "#222d37",  # Interactive controls
    "#26384a": "#394753",  # Borders
    "#edf5fe": "#dbe5ec",  # Primary text
    "#a2b5c9": "#aab8c4",  # Secondary text
    "#72f2d0": "#9bcbb3",  # Sage accent and REST
    "#162330": "#1c2933",
    "#192735": "#222f39",
    "#101925": "#161e27",
    "#253747": "#354451",
    "#24384b": "#303f4a",
    "#193d40": "#283e38",
    "#285055": "#3a524b",
    "#172433": "#202c36",
    "#335d65": "#415e63",
    "#263f52": "#334651",
    "#10253a": "#19272f",
    "#082218": "#15261e",
    "#a2ffe7": "#b0dbc4",
    "#385066": "#4a5b68",
    "#58758f": "#667e8b",
    "#202f40": "#263440",
    "#d5e5f6": "#d5e0e7",
    "#152130": "#1d2832",
    "#22384b": "#2a3a46",
    "#265458": "#324e48",
    "#efffff": "#e0ebe7",
    "#101a26": "#141d25",
    "#25374a": "#384551",
    "#ffc077": "#d8b680",  # SOAP and amber signals
    "#c1aeff": "#b8acd5",  # gRPC and violet signals
    "#192634": "#222d38",
    "#a5ffe4": "#acd8c0",
    "#ffd29a": "#e0bf8c",
    "#ffacaa": "#e0a89f",
    "#9be8db": "#a4cecc",
    "#ffb39e": "#e1ad9e",
}

SIGNALS_CSS = r"""
/* The four Command Deck signal cards. */
.forge-shell .signal-trends-title {
    -fx-font-size: 16px;
    -fx-font-weight: bold;
    -fx-text-fill: -forge-text;
}

.forge-shell .signal-trends-window {
    -fx-font-size: 12px;
    -fx-text-fill: -forge-muted;
}

.forge-shell .signal-trends-grid {
    -fx-hgap: 10;
    -fx-vgap: 10;
}

.forge-shell .signal-card {
    -fx-padding: 10 12 9 12;
    -fx-background-color: -forge-surface;
    -fx-background-radius: 11;
    -fx-border-color: -forge-border;
    -fx-border-radius: 11;
}

.forge-shell .signal-metric-name {
    -fx-font-size: 12px;
    -fx-font-weight: bold;
    -fx-text-fill: -forge-muted;
}

.forge-shell .signal-metric-number {
    -fx-font-size: 19px;
    -fx-font-weight: bold;
    -fx-text-fill: -forge-text;
}

.forge-shell .signal-metric-range {
    -fx-font-size: 11px;
    -fx-text-fill: -forge-muted;
}

.forge-shell .signal-chart {
    -fx-background-color: -forge-canvas;
    -fx-background-radius: 6;
}
"""


def patch_screens(source):
    if "SignalTrends.addTo(" in source:
        return source
    # Command Deck and Live Telemetry share the freshness header. Match the deck only.
    pattern = (
        r"(^[ \t]*(?:private\s+)?void commandDeck\(VBox\s+(\w+)\)\s*\{\s*\n"
        r"([ \t]*)TelemetryStatus\.addTo\(\2,\s*selectedMachine,\s*telemetryFreshness\);)[ \t]*$"
    )
    result, count = re.subn(
        pattern,
        lambda match: match[1] + "\n" + match[3]
        + f"SignalTrends.addTo({match[2]}, history, Sample::time, Sample::values);",
        source,
        flags=re.MULTILINE,
    )
    if count != 1:
        raise ValueError(f"expected one Command Deck freshness header, found {count}")
    return result


def patch_theme(source):
    if MARKER in source:
        return source
    if ".forge-topbar" not in source or "-forge-canvas:" not in source:
        raise ValueError("the FORGE OPS top-bar stylesheet is required")
    recolored = re.sub(r"#[0-9a-fA-F]{6}\b", lambda color: PALETTE.get(color[0].lower(), color[0]), source)
    return recolored.rstrip() + "\n\n" + MARKER + "\n" + SIGNALS_CSS.lstrip()


def read(path):
    with path.open(encoding="utf-8", newline="") as file:
        return file.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="check the source without writing changes")
    args = parser.parse_args()
    for source in ("SignalTrends.java", "SignalPlot.java"):
        if not (JAVA / source).is_file():
            raise FileNotFoundError(f"extract the full archive first: missing {source}")

    changes = {}
    for path, update in ((JAVA / "Screens.java", patch_screens), (THEME, patch_theme)):
        original = read(path)
        normalized = original.replace("\r\n", "\n")
        result = update(normalized)
        if result != normalized:
            changes[path] = result.replace("\n", "\r\n") if "\r\n" in original else result

    if args.check:
        print(f"PASS. Comfort upgrade validated. {len(changes)} file(s) to update.")
        return
    for path, body in changes.items():
        with path.open("w", encoding="utf-8", newline="") as file:
            file.write(body)
        print(f"Updated {path.relative_to(ROOT)}")
    print("PASS. FORGE OPS comfort palette and signal graphics applied.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(f"FORGE OPS comfort upgrade stopped: {error}.", file=sys.stderr)
        sys.exit(1)
