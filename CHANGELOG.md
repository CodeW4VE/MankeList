# Changelog

## 0.5.0 (unreleased)

The "ready for the world" release: everything a server needs, no external
scripts or bots.

- **Multi-list**: several lists can be active at once. `/ml load` adds,
  `/ml unload` deactivates keeping progress, `/ml switch` re-activates a saved
  list without resetting checks. Ambiguous materials use `list/material`
  (autocomplete handles it). The HUD gets a `2/3` indicator and the `K` key
  cycles the focused list.
- **Claims**: `/ml claim <material>` / `/ml unclaim`. Pending shows *who is
  farming it*; checking off records *who got it*. Visible in the HUD, in
  `/ml status` and on the Discord board.
- **Partial progress**: `/ml have <amount> <material>` reports how much the
  farmer has so far ("5,000 of the 20,000 azaleas"). Claims the material,
  shows next to the claim everywhere and counts toward the fine progress.
  Cleared automatically on check/uncheck/unclaim.
- **Stocking areas**: `/ml stockarea add <from> <to>` marks the drop-off
  containers; the server scans them (nested shulkers included) and progress
  becomes fine-grained (`carrying + stocked / needed` in the HUD, 📦 on the
  board). `/ml stock` prints a compact report of what is already delivered.
- **`/ml import <file.litematic>`**: parses the schematic on the server
  (pure Java NBT) and builds + activates the list. Validated against a
  1.1M-block schematic: identical counts to external parsers, sub-second.
- **Built-in Discord board**: set `discordWebhookUrl` in the server config
  and the mod posts the board through a webhook and edits it in place. No bot
  needed.
- Network channel v2 (`mankelist:material_list_v2`) carrying all lists +
  claim/stocked; 0.4.x clients keep working over v1.
- Fix: `/ml stockarea clear` (or removing every area) now zeroes the
  `stocked` counts on the next scan instead of leaving stale values behind.
- `Shift+J` opens the MankeList settings screen in-game, so the config is
  reachable without Mod Menu (plain `J` still toggles the HUD; a separate
  rebindable "Open MankeList settings" key stays available).

## 0.4.3

- Manke's inventory prompt is now **opt-in**: `/ml follow` / `/ml unfollow`
  (persisted per player). Nobody gets nudged unless they asked to.

## 0.4.x

- `/ml` as a native Brigadier command (status/lists/check/uncheck/load/reset)
  with real autocomplete.
- Manke's prompt: private clickable "[✓ Yes]" when you carry enough of a
  pending material (works for vanilla clients).
- HUD: block-weighted progress, custom ordering (grab & drop arrange screen
  with search), free line count, right-click cycles backwards in the config.

## 0.1.0 - 0.3.x

- First releases: server watches the shared state file and streams it to
  modded clients; Litematica-style HUD with own-inventory counts.
