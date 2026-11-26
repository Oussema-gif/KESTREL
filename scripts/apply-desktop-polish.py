"""Apply the Phase 10 UI changes to a Kestrel Phase 9 checkout."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "desktop-client/src/main/java/com/kestrel/desktop/Screens.java"
THEME = ROOT / "desktop-client/src/main/resources/com/kestrel/desktop/theme.css"
STYLE = """
/* Phase 10 screen feedback */
.label.kestrel-feedback:loading {
    -fx-text-fill: #8ce3d5;
    -fx-font-weight: bold;
}
.label.kestrel-feedback:empty {
    -fx-text-fill: #a3afba;
    -fx-font-style: italic;
}
.label.kestrel-feedback:failed {
    -fx-text-fill: #ffb093;
    -fx-font-weight: bold;
}
"""


def require_once(pattern, source, name):
    matches = list(re.finditer(pattern, source, re.MULTILINE))
    if len(matches) != 1:
        raise ValueError(f"expected one {name} in Screens.java, found {len(matches)}")
    return matches[0]


def patch_screens(source):
    # Replace the existing Phase 6 page fade with one entrance animation.
    # The graphic motion on the Pulse Core is controlled by a different block.
    if "Motion.enter(content, reduceMotion);" not in source:
        fade = require_once(
            r"^([ \t]*)if\s*\(\s*!reduceMotion\.get\(\)\s*\)\s*\{"
            r"([^{}]*\b(?:new\s+)?FadeTransition\s*\([^{}]*,\s*content\s*\)"
            r"[^{}]*?)\}[ \t]*(?=\r?$)",
            source, "page entrance fade",
        )
        source = source[:fade.start()] + fade[1] + "Motion.enter(content, reduceMotion);" + source[fade.end():]

    if "return ScreenFeedback.track(label);" not in source:
        bound = require_once(
            r"(private\s+static\s+Label\s+bound\s*\(\s*ObservableStringValue\s+\w+\s*\)"
            r"\s*\{[^{}]*?)\breturn\s+label\s*;",
            source, "bound status-label helper",
        )
        source = (source[:bound.start()] + bound[1] + "return ScreenFeedback.track(label);"
                  + source[bound.end():])

    for message in ("Loading the Maintenance registry…", "No records to display"):
        old = f'new Label("{message}")'
        new = f'ScreenFeedback.label("{message}")'
        if new not in source:
            if old not in source:
                raise ValueError(f"could not find the desktop label {message!r}")
            source = source.replace(old, new)
    return source


def main():
    original = SCREEN.read_text(encoding="utf-8")
    css = THEME.read_text(encoding="utf-8")
    patched = patch_screens(original)
    if original != patched:
        SCREEN.write_text(patched, encoding="utf-8")
    if "/* Phase 10 screen feedback */" not in css:
        THEME.write_text(css.rstrip() + "\n" + STYLE, encoding="utf-8")
    print("PASS. Phase 10 motion and screen feedback applied.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(f"Phase 10 UI patch stopped: {error}.", file=sys.stderr)
        sys.exit(1)
