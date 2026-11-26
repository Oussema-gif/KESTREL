"""Move the Kestrel desktop navigation into the FORGE OPS top bar."""
import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "desktop-client/src/main/java/com/kestrel/desktop"
CSS = ROOT / "desktop-client/src/main/resources/com/kestrel/desktop/forge-ops.css"


def replace(source, pattern, change, description):
    result, count = re.subn(pattern, change, source, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"expected one {description}, found {count}")
    return result


def patch_desktop(source):
    if "ForgeTopBar.build(" in source:
        return source

    source = replace(
        source,
        r"^([ \t]*private\s+(?:final\s+)?)VBox\s+navigation\s*=\s*new\s+VBox\s*\(\s*(?:\d+(?:\.\d+)?)?\s*\)\s*;",
        r"\1HBox navigation = new HBox(5);", "navigation container field",
    )
    source = replace(
        source, r"^[ \t]*shell\.setLeft\(\s*navigation\s*\);[ \t]*\n",
        "", "sidebar mount",
    )
    source = replace(
        source, r"^([ \t]*)shell\.setTop\(\s*header\(\)\s*\);[ \t]*$",
        lambda m: m[1] + 'shell.getStyleClass().add("forge-shell");\n'
        + m[1] + "shell.setTop(ForgeTopBar.build(header(), navigation, screens.motionPreference()));",
        "shell header",
    )
    source = replace(
        source, r"^([ \t]*)shell\.setBottom\(\s*protocolRail\(\)\s*\);[ \t]*$",
        r"\1shell.setBottom(ForgeSignals.style(protocolRail()));",
        "protocol rail",
    )
    return replace(
        source, r"^([ \t]*)stage\.setScene\(\s*scene\s*\);[ \t]*$",
        lambda m: m[1] + 'scene.getStylesheets().add(KestrelDesktop.class.getResource("forge-ops.css").toExternalForm());\n'
        + m[0],
        "stage scene mount",
    )


def patch_screens(source):
    if "motionPreference()" in source:
        return source
    return replace(
        source, r"^([ \t]*(?:(?:public|final)\s+)*class Screens\s*\{)",
        lambda m: m[1] + "\n"
        + "\n    javafx.beans.value.ObservableBooleanValue motionPreference() {\n"
        + "        return reduceMotion;\n"
        + "    }\n",
        "Screens class declaration",
    )


def patch_motion(source):
    marker = "private static final Interpolator NAVIGATION_EASE"
    if marker in source:
        return source
    source = replace(
        source, r"^(    private static final Duration PAGE_DURATION = Duration\.millis\()220(\);)$",
        r"\g<1>240\2" + "\n    " + marker + " = Interpolator.SPLINE(0.22, 0.75, 0.24, 1);",
        "Phase 10 page transition duration",
    )
    source = replace(source, r"fade\.setFromValue\(0\.85\);", "fade.setFromValue(0.92);",
                     "Phase 10 fade start")
    source = replace(source, r"slide\.setFromY\(8\);", "slide.setFromY(6);",
                     "Phase 10 slide distance")
    source = replace(source, r"^([ \t]*)(fade\.setInterpolator\()Interpolator\.EASE_OUT(\);)$",
                     r"\1\2NAVIGATION_EASE\3", "Phase 10 fade easing")
    source = replace(source, r"slide\.setInterpolator\(Interpolator\.EASE_OUT\);",
                     "slide.setInterpolator(NAVIGATION_EASE);", "Phase 10 slide easing")
    source = replace(
        source, r"^(        private final ParallelTransition animation;)$",
        r"\1" + "\n        private final java.util.List<ForgeMotion.Panel> panels;",
        "page animation field",
    )
    source = replace(
        source, r"^(            animation = new ParallelTransition\(fade, slide\);)$",
        r"\1" + "\n            panels = ForgeMotion.addPanelEntrances(page, animation);",
        "page animation assembly",
    )
    return replace(
        source, r"^(            animation\.stop\(\);)$",
        r"\1" + "\n            ForgeMotion.restorePanels(panels);",
        "page animation cleanup",
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate the source anchors without editing")
    args = parser.parse_args()
    for path in (JAVA / "ForgeTopBar.java", JAVA / "ForgeSignals.java", JAVA / "ForgeMotion.java", CSS):
        if not path.is_file():
            raise FileNotFoundError(f"extract the full upgrade archive first: missing {path}")

    changes = {}
    for file, update in (
        ("KestrelDesktop.java", patch_desktop),
        ("Screens.java", patch_screens),
        ("Motion.java", patch_motion),
    ):
        path = JAVA / file
        with path.open(encoding="utf-8", newline="") as source_file:
            raw = source_file.read()
        normalized = raw.replace("\r\n", "\n")
        patched = update(normalized)
        if normalized != patched:
            changes[path] = patched.replace("\n", "\r\n") if "\r\n" in raw else patched

    if args.check:
        print(f"PASS. FORGE OPS upgrade validated. {len(changes)} source file(s) to update.")
        return
    for path, body in changes.items():
        with path.open("w", encoding="utf-8", newline="") as destination:
            destination.write(body)
        print(f"Updated {path.relative_to(ROOT)}")
    print("PASS. FORGE OPS top bar and theme installed.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(f"FORGE OPS upgrade stopped: {error}.", file=sys.stderr)
        sys.exit(1)
