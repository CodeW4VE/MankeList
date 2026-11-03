<div align="center">

# MankeList

<img src="src/main/resources/assets/mankelist/icon.png" alt="MankeList icon" width="220">

**A shared material list for community builds: live HUD, chat checklists, claims, stock tracking and a self-updating Discord board. One jar.**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%20%2F%2026.3-brightgreen?logo=minecraft)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue)](https://fabricmc.net/)
[![Requires Fabric API](https://img.shields.io/badge/Requires-Fabric%20API-blue)](https://modrinth.com/mod/fabric-api)
[![Environment](https://img.shields.io/badge/Environment-Server%20%2B%20optional%20client-orange)](#installation)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

English | [Español](README_es.md)

</div>

A shared material list for big community builds, so everyone can see what is missing, who is
farming what and how far along you are.

<div align="center">
<img src="docs/img/demo.gif" alt="Checking off a material and claiming another, live on the HUD" width="820">
</div>

- Import the list straight from a `.litematic` on the server with `/ml import`.
- Anyone can check off a finished material, and the server announces it.
- Claims: `/ml claim obsidian` tells everyone you are on it.
- Stocking areas: mark the chest room and stored items count as progress (shulkers included).
- Manke's nudge (opt-in with `/ml follow`): a private clickable message when your inventory
  covers a pending material. Works for players without the mod.
- A Discord board that the mod keeps editing in place, from a webhook. No bot needed.
- Optional client HUD with live counts against your own inventory.

## Installation

- Minecraft 1.21 / 1.21.1 or 26.3, with [Fabric API](https://modrinth.com/mod/fabric-api).
  Java 21 for 1.21; Java 25 and Fabric Loader 0.19.5+ for 26.3.
- **Server (required):** put the jar in the server's `mods/`. Everything works with vanilla clients.
- **Client (optional):** the same jar adds the HUD. Vanilla clients are never sent anything.

When updating, keep `config/mankelist/` and the `mankelist-*.json` files; lists, checks, claims,
followers and stocking areas are kept.

## Usage

```
/ml import my_build.litematic              builds and activates the list from the schematic
/ml follow                                 opt into Manke's inventory nudges
/ml stockarea add 100 60 100 120 70 120    the chest room where materials get dropped
/ml check <material>                       check a material off
```

<img src="docs/img/manke_prompt.png" alt="Manke's inventory nudge" width="700">

On the client, `J` toggles the HUD and `Shift+J` opens its settings.

<!-- modrinth:end -->

## Details

### Commands

Everyone:

| Command | What it does |
| --- | --- |
| `/ml` or `/ml status` | List(s) with progress, stock and claims |
| `/ml check <material>` | Check a material off (announced server-wide) |
| `/ml uncheck <material>` | Oops button |
| `/ml claim <material>` | Tell everyone you are farming it |
| `/ml have <amount> <material>` | Report partial progress ("I have 5,000 of the azaleas so far"): claims it for you and counts toward the progress bars |
| `/ml unclaim <material>` | Release your claim (OPs can release anyone's) |
| `/ml follow` / `/ml unfollow` | Opt in/out of Manke's inventory nudges |
| `/ml stock` | Compact report of what is already in the stocking areas |
| `/ml lists` | Active lists, saved lists and available list files |

OPs (permission level 2):

| Command | What it does |
| --- | --- |
| `/ml import <file.litematic>` | Parse a schematic into a list and activate it |
| `/ml load <list>` | (Re)read a list file and activate it (**resets checks**) |
| `/ml switch <list>` | Re-activate a saved list **keeping its progress** |
| `/ml unload <list>` | Deactivate (progress kept for later `/ml switch`) |
| `/ml reset [list]` | Uncheck everything |
| `/ml stockarea add <x1 y1 z1> <x2 y2 z2>` | Add a stocking area (your current dimension) |
| `/ml stockarea list` / `clear` | Inspect / remove stocking areas |

Materials match by short id (`emerald_block`), full id or display name, with
real tab-completion. Several lists can be active at once; when a material
exists in more than one, disambiguate as `list/material` (e.g.
`/ml check xpfarm/hopper`): the autocomplete and Manke's buttons do this for
you.

<div align="center">
<img src="docs/img/claims.gif" alt="Claiming a material and reporting partial progress" width="820">
<img src="docs/img/stocking.gif" alt="Stocking areas counting chest contents as progress" width="820">
</div>

### The HUD (client mod)

<img src="docs/img/hud.png" alt="The HUD with live inventory counts" width="820">

| Key | Action |
| --- | --- |
| `J` | Toggle the HUD |
| `Shift+J` | Open the config screen (also via Mod Menu or a rebindable key) |
| `K` | Cycle the focused list (when several are active) |

All keys are rebindable in **Options → Controls → Key Binds** under the
*MankeList* category, in case `J`/`K` clash with your other mods.

<div align="center">
<img src="docs/img/config.gif" alt="Tuning the HUD in-game with Shift+J" width="700">
</div>

Pending rows show **carrying + stocked / needed** against your own inventory
(shulker and bundle contents included) with red/yellow/green coloring; checked
rows collapse to a ✓ and claims render as `@Name`. Sort by amount or arrange a
fully custom order (grab & drop, searchable). Corner, scale, icons, background
and line count are all configurable in-game.

### Discord board

1. In your Discord channel: **Edit channel → Integrations → Webhooks → New
   webhook**, copy its URL.
2. Put it in `config/mankelist-server.json` → `"discordWebhookUrl"`.
3. Restart. The mod posts the board and from then on **edits it in place**
   whenever the list changes (at most once every 10 s).

<div align="center">
<img src="docs/img/discord.gif" alt="A check in game editing the Discord board live" width="820">
</div>

### Server config

The server reads `config/mankelist-server.json`:

| Field | Default | Meaning |
| --- | --- | --- |
| `statePath` | `config/mankelist/state.json` | Where the shared state lives (auto-created). If a legacy schemlist/MCDR layout (`../config/schemlist/state.json`) already exists, it is reused |
| `importDir` | `schematics` | Folder scanned for `.litematic` files by `/ml import` (auto-created; `syncmatics` is used if that folder already exists) |
| `discordWebhookUrl` | `""` | Discord webhook for the built-in board (empty = off) |
| `pollSeconds` | `5` | How often the state file's mtime is checked |
| `promptsEnabled` | `true` | Master switch for Manke's nudges |
| `scanSeconds` | `30` | Inventory scan interval for the nudges |
| `promptCooldownSeconds` | `600` | Per player+material nudge cooldown |
| `stockScanSeconds` | `60` | Stocking-area container scan interval |

### List file format

Lists are plain JSON next to the state file:

```json
{
  "name": "xpfarm",
  "source": "xpfarm.litematic",
  "blocks": [
    { "id": "minecraft:obsidian", "display": "Obsidian", "count": 189746, "done": false }
  ]
}
```

The state file adds `claim` (who) and `stocked` (how many delivered) per
entry, and an `active` array. External tools can read or write these files;
the server picks changes up within `pollSeconds`.

### Compatibility

- Minecraft **1.21 / 1.21.1** and **26.3**, with the matching Fabric API.
  Build all supported jars with `python3 tools/multiversion.py` (JDK 21 and 25).
- Old clients keep working against newer servers (versioned network channels).
- The state format is compatible with the schemlist MCDR plugin, if you come
  from that ecosystem.

## License

[MIT](LICENSE). HUD layout inspired by Litematica's material list; written
from scratch, no code copied.
