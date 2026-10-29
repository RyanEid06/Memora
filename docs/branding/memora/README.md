# Memora artwork and appearance

The owner supplied these final assets on 16 October 2026. The three PNGs in `originals/` are byte-identical copies from Downloads, retained unchanged for future branding work:

- `launcher-original.png`: Glossy Layered Search Icon.png; text-free full-color launcher for both appearances.
- `wordmark-dark-original.png`: Memora Archive Logo.png; light text for dark mode.
- `wordmark-light-original.png`: Memora Personal Archive Logo.png; dark text for light mode.

Earlier draft logos and the combined showcase are not active resources. Downloads originals are retained. `scripts/prepare_memora_assets.py` uses Pillow to trim the archived wordmarks' export margins and prepare launcher resources without recoloring or redrawing the artwork. Launcher generation preserves the entire original canvas, including its black export background; it never crops to visible artwork. The owner subsequently chose a compact Library header: current item count and date basis appear directly alongside the grid/list and Settings controls, with no Memora logo or name in the row. The original wordmarks remain archived for provenance. Launcher ID, Room database, preference names and backup format are unchanged to preserve existing data. User-facing app name is Memora.

Launcher layout uses separate legacy and adaptive canvases:

| Resource | Canvas | Whole original placement |
| --- | --- | --- |
| `drawable-nodpi/memora_launcher.png` | 512 × 512, navy `#1E293B` | 358 × 358, centered; density icons derive from this master |
| `drawable-nodpi/memora_launcher_foreground.png` | 1080 × 1080, transparent | 660 × 660, centered; represents the 66dp artwork area in a 108dp adaptive layer |

The adaptive foreground fills its drawable bounds; it does not add the legacy master's gutter again. Android masks expose the central 72dp viewport. The immutable launcher source SHA-256 is `E8B9DEDBFCF568FB2F32B3A0593330D3E5BEB2625120A0D8D985650E9E3C825E`. `scripts/test_prepare_memora_assets.py` regenerates resources in a disposable workspace and checks that source, whole-artwork placement, density derivation and the meaningful artwork's 66dp circular safe zone. Run with `python -m unittest discover -s scripts -p test_prepare_memora_assets.py` using the existing Pillow environment. Representative masks and emulator renders supplement the owner's physical-launcher acceptance.

Settings -> Appearance offers Light, Dark and System; System is the initial/default choice. Changes apply immediately and persist across Activity/app recreation. System follows the phone configuration. Header surface/text and system-bar icon contrast follow the resolved mode; launcher artwork is shared across modes.

The existing fixed app palette is now the default instead of wallpaper-derived dynamic colors, so artwork has stable surroundings. The archived wordmarks retain PNG transparency; launcher canvases preserve the owner's original background as described above.

| Role | Light RGB / hex | Dark RGB / hex |
| --- | --- | --- |
| Page background | 248, 250, 252 / #F8FAFC | 15, 23, 42 / #0F172A |
| Header/card surface | 255, 255, 255 / #FFFFFF | 30, 41, 59 / #1E293B |
| Main text | 15, 23, 42 / #0F172A | 248, 250, 252 / #F8FAFC |
| Primary | 30, 41, 59 / #1E293B | 245, 158, 11 / #F59E0B |
| Secondary | 217, 119, 6 / #D97706 | 20, 184, 166 / #14B8A6 |

Verification: pure JVM theme regression first reproduced the prior system-only behavior (3 tests, 2 failures), then all 3 passed. Android preference tests cover immediate updates/recreation without changing other settings. API 35 UI instrumentation selects all three modes, checks the visible information/actions row, absence of header branding and actual light/dark surface colors, recreates the Activity, and saves light/dark screenshots. Hosted CI runs the appearance assertions against debug and nondebuggable optimized verification builds. Exact final source results and the permanent signing outcome are recorded in ignored `artifacts/release-v1/FINAL_VERIFICATION.md`. Physical-phone visual/launcher acceptance remains owner work.
