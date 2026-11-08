# Memora artwork and appearance

The owner supplied these final assets on 16 October 2026. The three PNGs in `originals/` are byte-identical copies from Downloads, retained unchanged for future branding work:

- `launcher-original.png`: Glossy Layered Search Icon.png; text-free full-color launcher for both appearances.
- `wordmark-dark-original.png`: Memora Archive Logo.png; light text for dark mode.
- `wordmark-light-original.png`: Memora Personal Archive Logo.png; dark text for light mode.

For 1.0.5, `launcher-fullbleed.png` combines the approved book and magnifier with a darker navy background and gold light at the top right and bottom left. The artwork fills the launcher bounds so Android can supply the icon shape. The three archived originals remain unchanged.

`scripts/prepare_memora_assets.py` packages the artwork with Pillow. Legacy density icons derive from a cropped 512×512 master that matches the scale of the adaptive viewport. The opaque 1080×1080 adaptive layer uses one 900×900 artwork image. Its outermost background pixels extend into the motion gutter, so the central 72dp viewport has a continuous background and room for the magnifier. The asset tests check original preservation, opacity, background continuity, emblem size, and retention under circle and squircle masks. `LauncherAssetTest` renders the packaged adaptive drawable through Android and checks its rim for the former black border. Phone launchers supply their own masks.

The immutable launcher original SHA-256 remains `E8B9DEDBFCF568FB2F32B3A0593330D3E5BEB2625120A0D8D985650E9E3C825E`. The compact Library header still uses item count/date basis and controls without a logo row. Launcher ID, Room database, preference names and backup format are unchanged.

Settings -> Appearance offers Light, Dark and System; System is the initial/default choice. Changes apply immediately and persist across Activity/app recreation. System follows the phone configuration. Header surface/text and system-bar icon contrast follow the resolved mode; launcher artwork is shared across modes.

The existing fixed app palette is now the default instead of wallpaper-derived dynamic colors, so artwork has stable surroundings. The archived wordmarks retain PNG transparency; launcher backgrounds fill the platform's icon mask as described above.

| Role | Light RGB / hex | Dark RGB / hex |
| --- | --- | --- |
| Page background | 248, 250, 252 / #F8FAFC | 15, 23, 42 / #0F172A |
| Header/card surface | 255, 255, 255 / #FFFFFF | 30, 41, 59 / #1E293B |
| Main text | 15, 23, 42 / #0F172A | 248, 250, 252 / #F8FAFC |
| Primary | 30, 41, 59 / #1E293B | 245, 158, 11 / #F59E0B |
| Secondary | 217, 119, 6 / #D97706 | 20, 184, 166 / #14B8A6 |

Verification: pure JVM theme regression first reproduced the prior system-only behavior (3 tests, 2 failures), then all 3 passed. Android preference tests cover immediate updates/recreation without changing other settings. API 35 UI instrumentation selects all three modes, checks the visible information/actions row, absence of header branding and actual light/dark surface colors, recreates the Activity, and saves light/dark screenshots. Hosted CI runs the appearance assertions against debug and nondebuggable optimized verification builds. Exact final source results and the permanent signing outcome are recorded in ignored `artifacts/release-v1/FINAL_VERIFICATION.md`. Physical-phone visual/launcher acceptance remains owner work.
