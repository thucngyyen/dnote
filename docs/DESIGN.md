# DNote UI design

Built on the brand kit in `brand/` (see `brand/BRAND.md`). One accent colour (blue), ink and paper neutrals, slate for secondary text. Clean, modern, calm; the blue is used sparingly, for the current selection, primary actions and the caret.

## Tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `bg` | `#FFFFFF` | `#0A0C10` (Ink) | Editor / main content |
| `chrome` | `#F5F7FA` (Paper) | `#0E1117` | Sidebar, list backgrounds, bars |
| `surface` | `#FFFFFF` | `#141821` | Cards, sheets, menus, inputs |
| `text` | `#0A0C10` (Ink) | `#F5F7FA` (Paper) | Primary text |
| `text-2` | `#334155` | `#CBD5E1` | Body text in notes |
| `muted` | `#64748B` | `#94A3B8` (Slate 400) | Secondary text, icons |
| `faint` | `#94A3B8` | `#64748B` | Placeholders, counts, chevrons |
| `border` | `#E2E8F0` | `#1E2430` | Hairlines, input borders |
| `hover` | `#EEF2F7` | `#161B24` | Hover / pressed rows |
| `accent` | `#2563EB` (Blue 600) | `#3B82F6` (Blue 500) | Selection, primary buttons, links, caret |
| `accent-soft` | `#2563EB` at 8% | `#3B82F6` at 14% | Selected row / chip background |
| `success` | `#16A34A` | `#22C55E` | "Synced" dot |

Tag colours (dots and chips): amber `#F59E0B`, blue `#3B82F6`, green `#22C55E`, red `#EF4444`, violet `#8B5CF6`, pink `#EC4899`, slate `#94A3B8`. Favorite is amber with a star.

Type: Inter on the web (bundled). System font on iOS (SF Pro) and Android (Roboto); they sit close to Inter and stay native. Titles semibold with slightly tight tracking (−1 to −2%). Wordmark: "DNote" with the **D in accent blue**, next to the brand mark (`mark.svg` light / `mark-reversed.svg` dark).

Shape: 8 px controls and rows, 12 px cards and sheets, pills fully rounded. Soft shadows only on floating things (menus, sheets, FAB).

## Information architecture

- **Pinned**: notes and folders the user pinned. Shown first.
- **Tags**: Favorite (always exists, star, amber) plus user tags with a colour. A note or folder can have many tags. Tags are created from the tag picker ("New tag…" with name + colour) and managed in Settings / sidebar (rename, recolour, delete; Favorite can't be deleted).
- **Folders**: the real file tree. Images live in `Attachments/` (shown as a normal folder; image files show as thumbnails).

Pins and tags are stored in the synced `.dnote/workspace.json` and follow renames/moves/deletes automatically (handled by the Rust core).

## Mobile (iOS & Android)

Home screen, top to bottom:
1. Header: brand mark + wordmark, settings button. Sync status line under it ("● Synced 10:42").
2. Search field.
3. **Pinned**: horizontally scrolling cards (icon, title, subtitle = note preview or "N notes").
4. **Scope chips**: `All` · folders (top level) · tags (colour dot / star). A blue **pill slides** between chips with a spring when the selection changes, and the list underneath **slides in from the side of the newly selected chip** (left/right depending on direction). This is the "moving" folder selection.
5. List for the selected scope: subfolders first (tap = drill in), then notes (title, one-line preview, relative date, tag dots, pin glyph), then images as thumbnails.
6. Primary "New note" button (blue), creates in the selected folder.

Row actions (long-press / context menu / swipe): Pin/Unpin, Tags…, Rename, Delete.

**Folder → note transition**: opening a note zooms/morphs from its row into the editor (iOS 18 `navigationTransition(.zoom)`; Android shared-element bounds transform), and reverses on back. Drilling into a subfolder slides.

Note screen: back, title, Write/Read toggle, pin toggle, tag button (sheet/menu with check marks + "New tag…"), more (share, rename, delete). Write mode has the formatting bar above the keyboard including an **image** button (photo picker → saved to `Attachments/`, Markdown inserted at the cursor). Read mode renders images.

## Web

- Sidebar: mark + wordmark, ⌘K search, sync line; **Pinned**; **Tags** (with counts, "+" to create, right-click/⋯ to rename/recolour/delete); **Folders** tree with animated expand/collapse.
- Clicking a folder or tag opens a **list view** in the main area (cards with title, preview, date, tags). Opening a note from it uses a **view transition**: the card morphs into the editor; going back via the breadcrumb reverses it.
- Note toolbar: breadcrumb (folder crumbs clickable), pin toggle, tags popover, image button, Write/Split/Read.
- Images: paste or drop into the editor, or the image button. Stored in `Attachments/`, rendered in Read/Split.
- Theme: System / Light / Dark toggle.
