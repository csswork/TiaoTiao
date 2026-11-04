# TiaoTiao brand assets

The packaged logo is the interlocked leap tt, a refinement of the 0.2.5 rising-tt design. It keeps the two rising letters and the green / mint palette, and changes four things:

- **Interlock**: the white t's crossbar passes behind the mint t's stem through a 3.4-unit knockout gap. The pair reads as one monogram while keeping enough room to stay legible at small sizes, and the gap is real geometry, so it also shows on the Android 13+ themed icon.
- **Forward cut**: both stem tops are cut on a rising angle that follows the jump from the first t to the second. This is the one sharp detail, and it is the signature. Every other terminal stays round.
- **Safe-zone fit**: the adaptive mark stays within a 27.6-unit radius, well inside the 33-unit (66dp) safe zone, so launchers that enlarge the foreground (such as Xiaomi HyperOS) still leave room around it. The 0.2.5 mark was clipped by circular launcher masks.
- **Finish**: crossbars are slightly lighter than stems (10.5 vs 12). The background is a deeper emerald gradient with a soft top-left highlight, and the mint is more saturated (`#9EEDB0`) to stand apart from the off-white (`#FBFAF2`).

Files:

- `tiaotiao-logo-interlock.svg`: the selected master. Mark paths are positioned for the adaptive foreground; the standalone tile scales the mark group by 1.28.
- `tiaotiao-logo.svg`: copy of the selected master.
- `tiaotiao-logo-0.2.5.svg`: previous deployed rising-tt design, kept for reference.
- `tiaotiao-logo-shelved-double-leap.svg`: shelved study.
- `tiaotiao-logo.png`: 512 px preview of the standalone tile.
- `tiaotiao-brand-board.png`: standalone tile, circular launcher mask, themed mark, small sizes and QingIcon navigation previews.

Regenerate the Android resources with `python3 tools/generate_brand_assets.py`. Android uses a native standalone vector, adaptive background (gradient plus highlight) and foreground layers, and an Android 13+ monochrome layer. UI symbols use the separate Apache-2.0 [QingIcon subset](../../third_party/qingicon/README.md).
