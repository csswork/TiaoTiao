#!/usr/bin/env python3
"""Import the small QingIcon subset used by TiaoTiao; no runtime SVG parser is needed."""
import argparse
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import quoteattr

REVISION = "316c8881784d8620fff5a764f84ec7be41ef14c1"
ICONS = {
    "home": "Building/home.svg",
    "apps": "Abstract/apps-square-circle.svg",
    "rules": "Editor/list-check-3.svg",
    "history": "Time/clock-refresh-cw.svg",
    "settings": "System/gear-3.svg",
    "chevron_left": "Arrow/chevron-left.svg",
    "chevron_right": "Arrow/chevron-right.svg",
    "chevron_down": "Arrow/chevron-down.svg",
    "check_circle": "System/circle-check.svg",
    "pending_circle": "System/circle-minus.svg",
    "search": "System/search.svg",
    "play": "Media/play.svg",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Checkout of asorn/qingicon-open at the pinned revision")
    args = parser.parse_args()
    revision = subprocess.check_output(["git", "-C", str(args.source), "rev-parse", "HEAD"], text=True).strip()
    if revision != REVISION:
        raise SystemExit(f"Expected QingIcon {REVISION}, got {revision}")
    project = Path(__file__).resolve().parents[1]
    vendor = project / "third_party/qingicon"
    assets = project / "app/src/main/assets/third_party/qingicon"
    drawables = project / "app/src/main/res/drawable"
    for directory in [vendor / "svg", assets, drawables]:
        directory.mkdir(parents=True, exist_ok=True)
    for filename in ["LICENSE", "NOTICE"]:
        shutil.copyfile(args.source / filename, vendor / filename)
        shutil.copyfile(args.source / filename, assets / filename)
    for name, relative in ICONS.items():
        source = args.source / "icons-source/line" / relative
        svg = ET.fromstring(source.read_text())
        if svg.attrib.get("viewBox") != "0 0 24 24":
            raise ValueError(f"Unexpected SVG viewport: {relative}")
        lines = [f'<!-- QingIcon {relative}; Apache-2.0; upstream {REVISION}. Converted to VectorDrawable. -->',
                 '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
                 '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
        for path in svg:
            if path.tag.split("}")[-1] != "path" or "transform" in path.attrib:
                raise ValueError(f"Unsupported SVG element: {relative}")
            if path.attrib.get("stroke") != "black" or path.attrib.get("stroke-width") != "2":
                raise ValueError(f"Unexpected icon weight: {relative}")
            attributes = {
                "fillColor": "#00000000",
                "strokeColor": "#FF000000",
                "strokeWidth": "2",
                "strokeLineCap": path.attrib.get("stroke-linecap", "butt"),
                "strokeLineJoin": path.attrib.get("stroke-linejoin", "miter"),
                "pathData": path.attrib["d"],
            }
            lines.append("    <path " + " ".join(f"android:{key}={quoteattr(value)}" for key, value in attributes.items()) + " />")
        lines.append("</vector>")
        (drawables / f"ic_qing_{name}.xml").write_text("\n".join(lines) + "\n")
        shutil.copyfile(source, vendor / "svg" / f"{name}.svg")
    print(f"Imported {len(ICONS)} QingIcon vectors at {REVISION}")


if __name__ == "__main__":
    main()
