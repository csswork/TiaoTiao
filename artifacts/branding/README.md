# TiaoTiao brand assets

The packaged logo remains the deployed 0.2.5 rising-tt design. The 0.3.0 core update does not ship a new logo design. The double-leap study is shelved as `tiaotiao-logo-shelved-double-leap.svg`.

- `tiaotiao-logo.svg`: current SVG master, copied from `tiaotiao-logo-0.2.5.svg`.
- `tiaotiao-logo.png`: 512 px preview from the packaged Android vector.
- `tiaotiao-brand-board.png`: actual packaged logo, launcher mask, themed mark and QingIcon navigation previews.

Regenerate the selected deployed mark with `python3 tools/generate_brand_assets.py`. Android uses a native standalone vector, adaptive background/foreground layers and an Android 13+ monochrome layer. UI symbols use the separate Apache-2.0 [QingIcon subset](../../third_party/qingicon/README.md).
