#!/usr/bin/env python3
"""Regenerate ``app/src/main/assets/usb_ids.json`` from wifi4's chip catalogue.

The app asks Android for USB permission only for adapters wifi4 can drive (and the ZeroCD
stubs they start as), so the list must come from wifi4 itself rather than a second hand-kept
copy. Run this whenever the pinned wifi4 source adds a chip:

    python3 tools/gen-usb-ids.py
    python3 tools/gen-usb-ids.py --wifi4-src ../references/opx-wifi4 --out app/src/main/assets/usb_ids.json

It imports ``wifi4.device.manager``, which needs pyusb available in the environment running
the script (the app's runtime ships it already).
"""

import argparse
import json
import pathlib
import sys


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--wifi4-src", type=pathlib.Path,
                        default=root.parent / "references" / "opx-wifi4" / "src",
                        help="the wifi4 checkout's src directory")
    parser.add_argument("--out", type=pathlib.Path,
                        default=root / "app" / "src" / "main" / "assets" / "usb_ids.json")
    args = parser.parse_args()

    source = args.wifi4_src.resolve()
    if not (source / "wifi4").is_dir():
        print(f"not a wifi4 source tree: {source}", file=sys.stderr)
        return 1
    sys.path.insert(0, str(source))
    from wifi4.device.manager import supported_ids, zerocd_ids  # noqa: E402

    supported = sorted(f"{vid:04x}:{pid:04x}" for vid, pid in supported_ids())
    zerocd = sorted(f"{vid:04x}:{pid:04x}" for vid, pid in zerocd_ids())
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"supported": supported, "zerocd": zerocd},
                                   indent=2) + "\n", encoding="utf-8")
    print(f"{args.out}: {len(supported)} adapters, {len(zerocd)} ZeroCD stubs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
