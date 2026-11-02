# QingIcon subset

Upstream: https://github.com/asorn/qingicon-open

Pinned revision: `316c8881784d8620fff5a764f84ec7be41ef14c1`.

The `svg/` folder contains unchanged Line SVG originals for the 12 icons selected by `tools/import_qingicon.py`. They use a 24 × 24 viewport, 2-unit strokes, and rounded caps/joins. Generated Android VectorDrawables retain that geometry and use a tintable black stroke. Navigation glyphs render at 24 dp; inline chevrons and status glyphs render at 20 dp. Touch targets are provided by their containing buttons.

`LICENSE` and `NOTICE` are retained unchanged here and bundled in the APK under `assets/third_party/qingicon/`. No upstream name or logo is used as the TiaoTiao brand mark.

Regenerate using a checkout at the pinned revision:

```sh
python3 tools/import_qingicon.py /path/to/qingicon-open
```
