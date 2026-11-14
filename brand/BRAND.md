# DNote brand kit

The mark is a note page with a blue folded corner. The last line of text is blue, showing the line you're writing now.

## Colours

| Token | Hex | Use |
|---|---|---|
| Ink | `#0A0C10` | App tile, dark backgrounds, body text |
| Paper | `#F5F7FA` | The note page, light backgrounds |
| Blue 600 | `#2563EB` | The blue line on light surfaces; "D" in the wordmark on light backgrounds |
| Blue 500 | `#3B82F6` | The fold; "D" in the wordmark on dark backgrounds |
| Slate 400 | `#94A3B8` | Secondary text and taglines on dark backgrounds |

## Typeface

The wordmark is **Inter Bold** with −3% letter-spacing. In the SVG files the letters are already converted to outlines, so they display correctly without Inter installed. Inter is under the SIL Open Font License, so you can use it in the product UI too.

## Which file to use

| You need | File |
|---|---|
| App icon (everywhere) | `svg/icon.svg` |
| Icon at 16–32 px | `svg/icon-small.svg` (thicker lines, two instead of three) |
| Website header, light bg | `svg/lockup-horizontal-light.svg` |
| Website header, dark bg | `svg/lockup-horizontal-dark.svg` |
| Login / splash screen | `svg/lockup-stacked-*.svg` |
| Mark without the tile | `svg/mark.svg` (light bg) · `svg/mark-reversed.svg` (dark bg) |
| One-colour use (print, embossing, watermarks) | `svg/mark-mono-black.svg` · `svg/mark-mono-white.svg` |
| Web favicons, PWA, social preview | `web/` + `web/head.html` |
| iOS | `ios/AppIcon.appiconset` → drop into `Assets.xcassets` |
| Android | `android/res` → merge into `app/src/main/res` (adaptive + themed icon) |
| macOS | `macos/DNote.icns` (Electron/Tauri: `icon.icns`) |
| Windows | `windows/DNote.ico` (16–256 px) |
| Linux / Electron / Tauri PNGs | `png/icon-*.png` |

## Clear space and minimum size

- Leave a clear margin around the logo at least as wide as the fold (about ¼ of the page's width).
- Minimum sizes: 16 px for the icon (use `icon-small`), 20 px tall for the horizontal lockup.

## Don'ts

- Don't recolour the fold or the blue line. Don't add gradients, outlines or effects.
- Don't stretch the logo or rearrange the lockup.
- Don't use Cloudflare's orange cloud or other Cloudflare branding in the logo. If you want to mention Cloudflare, write "Deploy on Cloudflare" as plain text, or use Cloudflare's official badge where their guidelines allow it.

## Regenerating

To change the logo, edit `source/generate.js` (needs `opentype.js` and `@fontsource/inter`), run `node generate.js <out-dir>`, then re-export the PNGs with `resvg`.
