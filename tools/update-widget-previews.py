#!/usr/bin/env python3
"""Copy the synthetic WidgetDeviceTest default-size renders into public widget preview resources.

Run WidgetDeviceTest first, pull its widget-tests directory, then pass that directory here.
No real calendar data is rendered by that test. The PNGs are copied without image modification.
"""
import argparse
from pathlib import Path
import shutil
import struct

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("renders", type=Path)
args = parser.parse_args()
output = Path(__file__).resolve().parents[1] / "app/src/main/res/drawable-nodpi"
output.mkdir(parents=True, exist_ok=True)
shutil.copyfile(output.parent / "mipmap-xxxhdpi/ic_launcher.webp", output / "widget_app_icon.webp")
for index, name in enumerate(("quick", "double", "day")):
    for theme in ("light", "dark"):
        source = args.renders / f"widget-{index}-{theme}-default.png"
        data = source.read_bytes()
        assert data[:8] == b"\x89PNG\r\n\x1a\n", f"Not a PNG: {source}"
        width, height = struct.unpack(">II", data[16:24])
        assert 128 <= width <= 2048 and 128 <= height <= 2048
        shutil.copyfile(source, output / f"widget_preview_{name}_{theme}.png")
