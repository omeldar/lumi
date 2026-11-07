<div align="center">

<img src="src/main/resources/assets/lumipet/icon.png" width="88" alt="Lumi, a happy little red block with a light on his head">

# Lumi

**Lumi, your AI assistant in Minecraft.**

A happy little block who answers your questions, helps you build, and runs commands with one click.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-62B47A?style=flat-square)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21+-F89820?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-C8200F?style=flat-square)](LICENSE)

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
> Not made, endorsed or supported by Anthropic. Claude and Claude Code are trademarks of Anthropic.

</div>

---

## Overview

Lumi is a cheerful little redstone-red block who lives in your Minecraft client. Ask him something
in chat and he answers in chat. Ask him to build and he suggests commands you can review and run
without leaving the game. He always knows where you are standing and what is around you, so you
don't have to explain.

### What Lumi runs on

Lumi is a character, not his own AI. His replies are written by **Claude**, through the
**Claude Code CLI already installed on your machine**. The mod launches the `claude` program you
installed as a subprocess, so usage runs through your own Anthropic account, under your own
agreement with Anthropic. It bundles no Anthropic code or credentials.

Read [Your Anthropic account](#your-anthropic-account) and
[What leaves your computer](#what-leaves-your-computer) before you start. Conversations stay alive
between prompts and across restarts.

## From one prompt to a finished build

Describe what you want. The prompt screen takes multi-line text, and can attach a screenshot or
a scan of the surrounding blocks so the answer fits the place you are standing in.

> Build a japanese style hut here with lots of details, flowers, and some water for decoration
> outside. Style it based on the biome.

That prompt, with a 64-block scan attached, produced this:

<img src="docs/images/result.png" width="100%" alt="A Japanese-style hut among cherry blossom trees at sunset, built from the prompt above">

<img src="docs/images/result-night.png" width="100%" alt="The same hut at night, lanterns lit, surrounded by flowers and cherry blossom">

Nothing about the biome, the palette or the terrain was specified beyond "style it based on the
biome" — the scan is what made that possible.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 |
| Loader | [Fabric](https://fabricmc.net/use/) + [Fabric API](https://modrinth.com/mod/fabric-api) |
| Java | 21 or newer |
| CLI | [Claude Code](https://claude.com/claude-code), signed in — `claude --version` must work |
| Optional | WorldEdit (strongly recommended), [Mod Menu](https://modrinth.com/mod/modmenu) for the settings button |

## Install

1. Install Claude Code and sign in.
2. Install Fabric Loader for 1.21.11 and Fabric API.
3. Download `lumi-*.jar` from [Releases](../../releases) and place it in your `mods` folder.
4. Launch the game, join a world, and run `/lumi hello`.

### Upgrading from ClaudeMC

Lumi used to be called ClaudeMC. Delete the old `claudemc-*.jar` from your `mods` folder first, or
both will run. On first launch he moves `config/claudemc.json` and the
`claudemc` game folder, including your world notes, to the new names, without overwriting anything.
The old conversation can't be resumed, so you start a fresh session. Custom key binds go back to
the defaults, and `/claude` is now `/lumi`.

## Usage

Press <kbd>H</kbd> for the prompt screen, or type a command in chat:

| Command | Description |
|---|---|
| `/lumi <prompt>` | Ask, continuing the current session |
| `/lumi new <prompt>` | Start a fresh session |
| `/lumi shot <prompt>` | Attach your most recent screenshot (press <kbd>F2</kbd> first) |
| `/lumi scan <r> <prompt>` | Attach a block scan of radius `r` (1–256) |
| `/lumi shot scan <r> <prompt>` | Attach both; either order works |
| `/lumi open` | Open the prompt screen |
| `/lumi last` | Reopen the approval menu |
| `/lumi undo` | Queue `//undo` |
| `/lumi cancel` | Discard anything queued and abort the request in flight |
| `/lumi model <id>` | Switch model — an alias or an exact id |
| `/lumi effort <level>` | `low`, `medium`, `high`, or `default` |
| `/lumi settings` | Open the settings screen |
| `/lumi keys` | Show bindings and conflicts, and rebind |
| `/lumi usage` | Quota windows and this session's token use |
| `/lumi notes` | Path to the current world's notes file |
| `/lumi status` | Session, model, permissions, and billing source |

`/ai` is accepted as a shorthand for all of the above.

### Reviewing commands

Proposed commands arrive in chat as clickable `[Run]` and `[Copy]` entries, with `[Run all]`
and a paged approval menu alongside. The menu renders each command as an editable field, so a
radius or a block can be adjusted before it is sent. Commands are queued a few ticks apart to
stay clear of server chat-rate limits.

### Context

Every prompt is accompanied by a freshly written snapshot of your position, facing, dimension,
biome, light level, the block you are looking at, and your installed mods, so answers do not
depend on you describing your surroundings.

Scans extend to a 256-block radius. Air is never written: each layer is emitted as run-length
spans of non-air blocks with exact coordinates, and anything absent is air. On servers where you
have no command permission, scans are more limited: see [Safety](#safety).

### Notes

Lumi keeps a notes file per world — `notes/<world>.md` in the mod's working directory —
holding coordinates, palettes, and decisions. Singleplayer worlds are keyed by level name and
servers by address, so work on one never appears in another.

## Asking in chat, and watching the commands run

The prompt screen is convenient, not required. A `/lumi` line in chat does the same job, and
the chat input stretches past vanilla's 256-character limit while you are typing one:

```
/lumi use this island to build a big old medieval statue with a sword
```

Lumi does not edit the world behind your back. Every command he issues is printed in chat as
it goes out, in order, under your own name — so a build is something you watch happen rather
than a diff you discover afterwards.

He selects with `//pos1` and `//pos2`, fills with
`//set stone_bricks`, carves back with `//set air`, and says when he is done. Each line is a
command you could have typed yourself.

When commands are proposed rather than run directly — outside creative, or whenever you prefer
to approve them — they arrive as clickable `[Run]` entries instead. See
[Reviewing commands](#reviewing-commands).

## Settings

Reachable from `/lumi settings`, or from the cog beside Lumi in Mod Menu. All options
apply to the next message; nothing requires editing files.

### Mod Menu

[Mod Menu](https://modrinth.com/mod/modmenu) collects every installed mod's settings behind one
tab, and Lumi registers itself there so it lives alongside the rest rather than hiding
behind a chat command.

It is entirely optional. The integration is compiled against the Mod Menu API but not bundled
with it, so the mod loads normally whether or not Mod Menu is present, and the settings screen
is built from plain vanilla widgets — no configuration library is pulled in either way.

### Keybinds

Rebindable under **Options → Controls → Key Binds → Lumi**, or via the **Key binds…**
button in the settings screen.

| Default | Action |
|---|---|
| <kbd>H</kbd> | Open the prompt screen |
| <kbd>U</kbd> | Reopen the last proposal |
| *unbound* | Undo last WorldEdit |

Defaults are chosen to avoid both vanilla and common build mods. Because that can only ever be
true of one particular mod list, Lumi checks its bindings against every installed mod when
you join a world and reports any clash in chat with a rebinding link. `/lumi keys` runs the
same check on demand.

## Safety

Every command Lumi runs is sent as an ordinary chat command under your own name and your
own permissions. There is no elevation and no server-side component; nothing can be done that
you could not have typed yourself.

- **Permission is checked before Lumi is asked.** The mod inspects the command tree the
  server sent you. A world without cheats provides a minimal tree, so Lumi knows it is
  read-only, tells Lumi not to propose commands, and answers with advice.
- **Nothing runs unprompted.** Commands enter the queue only when you click, except for direct
  world tools, which additionally require creative mode.
- **Deny list.** `/op`, `/ban`, `/kick`, `/whitelist`, `/stop` and similar are refused in the
  queue regardless of what is returned. Configurable.
- **Singleplayer survival** can be enabled the usual way: pause → **Open to LAN** →
  *Allow Cheats: ON* → **Start LAN World**.

**No X-ray on servers.** Your client holds more of the world than you can see, including buried
ores. If you are in survival or adventure on a server without command permission, Lumi only gets
blocks you could see from where you stand, and scans reach 64 blocks at most. In singleplayer, with
command permission, or in creative or spectator mode, he can read every loaded block. Other
server rules may still apply to using an AI helper, so check the rules of the server you are on.

## What leaves your computer

Lumi starts the Claude Code CLI you installed, and that program sends data to Anthropic under
your own account and Anthropic's terms. The mod's author receives nothing.

When you send a prompt, Claude can receive:

- your prompt and the replies in that session
- your position and facing, dimension, biome, light level, time of day, game mode, held item and
  the block you are looking at
- the world name, or the server's name and address
- your installed mod list
- block scans and screenshots, if you attach them or Claude requests a scan with the world tools
- your per-world notes file

A screenshot shows whatever was on screen, which can include chat and other players' names. How
long Anthropic keeps this data, and whether it trains models on it, depends on your account type
and settings: see [Anthropic's data usage page](https://code.claude.com/docs/en/data-usage).
Claude Code also keeps local session transcripts of its own.

On your machine:

- The mod writes context, scans, copied screenshots and notes to `<gameDir>/lumi/`.
- It runs a local server on `127.0.0.1` (port 25599 by default) so Claude Code can talk to the
  game. The server accepts no outside connections. Turn it off with `bridgeEnabled`, or
  **World tools** in the settings; Claude then loses the direct world tools.

## Your Anthropic account

You need your own Claude Code install, signed in to your own account. The mod does not read,
store or relay your credentials.

Your use of Claude Code stays under your own agreement with Anthropic. Everyone runs Lumi on their
own account: the mod never shares one person's account with another.

### Billing

Lumi bills however your `claude` CLI is authenticated. The CLI's credential order places
`ANTHROPIC_API_KEY` above a claude.ai login, so a key set for an unrelated project would
otherwise turn every prompt into a per-token charge.

API key billing is therefore **disabled by default**, to prevent surprise charges: the credential
variables are removed from the environment handed to the CLI, which falls back to your
subscription login if you have one. If a key was present and ignored, the mod says so once.

It can be enabled in **Settings → API key billing**. While enabled, every reply, the action bar
and `/lumi status` carry a warning glyph naming the credential that was charged.

## World tools

Each session is given an MCP server pointing back at the running client:

| Tool | Description |
|---|---|
| `get_context` | Position, facing, biome, look target, installed mods, `can_build` |
| `get_block` | One coordinate, with full block state |
| `scan_area` | Cylinder scan to radius 256, as run-length spans of non-air blocks |
| `find_blocks` | Where a block type occurs nearby, with a bounding box |
| `run_command`, `run_commands` | Execute commands — creative and command permission only |
| `say` | Write a line to the player's chat |

Reads are always available, limited to what you can see on servers where you have no command
permission (see [Safety](#safety)). Writes are refused outside creative, refused without command
permission, filtered by the deny list, paced by the queue, and interruptible with
`/lumi cancel`. Refusals explain themselves, so Lumi falls back to proposing commands for
manual approval rather than retrying.

## Companion mods: Lumi Link

Lumi can see the world and talk about it. It cannot walk anywhere or press a key. Other mods
can, and Lumi hands their tools to your session alongside its own, so there is one MCP server
to register rather than one per mod.

A companion writes a descriptor into `config/lumi-link/` on startup, saying what it is called,
where its own loopback MCP endpoint is and what token to send. Lumi reads that directory,
health-checks what it finds, and merges those tools in under the descriptor's prefix — so
Autopilot's `farm_start` arrives as `ap_farm_start`. `/lumi companions` shows what was found
and whether it is answering.

It is a file and an HTTP call rather than a shared Java interface, on purpose. An interface
would make every companion compile against a particular Lumi version, and the first time either
side updated, the other would fail to load — and a hard load failure in a mod pack is worse
than a missing feature. With a descriptor, a mismatch is at worst a stale file that fails a
health check and is skipped. Lumi's own tools always win a name collision, and a descriptor
pointing anywhere but loopback is refused outright: these endpoints can hold the player's keys
down, and the one thing they must never be is reachable.

Known companions:

| Mod | Prefix | What it adds |
|---|---|---|
| [Autopilot](https://github.com/omeldar/goh-autopilot) | `ap_` | Record and replay the player's key presses, travel to coordinates, run the farms in their farm book |

The protocol is written up in
[Autopilot's docs/lumi-link.md](https://github.com/omeldar/goh-autopilot/blob/main/docs/lumi-link.md),
including the rules both sides are expected to keep.

## Configuration

`config/lumi.json` is created on first launch:

```jsonc
{
  "claudePath": "",            // empty = auto-detect
  "model": "opus",             // alias or exact id
  "effort": "",                // low | medium | high
  "timeoutSeconds": 240,
  "streaming": true,           // show progress while the reply arrives
  "commandDelayTicks": 5,      // gap between queued commands
  "chatPromptLimit": 2000,     // chat input cap while typing a /lumi line
  "allowApiKey": false,        // see Billing
  "bridgeEnabled": true,
  "bridgePort": 25599,
  "defaultScanRadius": 24,
  "maxCommands": 24,
  "blockedPrefixes": ["/stop", "/op", "/deop", "/ban", "/kick", "/whitelist"]
}
```

## Architecture

```
  Minecraft client
        │  /lumi <prompt>
        ▼
  ┌───────────┐    writes    ┌──────────────────────────┐
  │    Lumi   │ ───────────► │  <gameDir>/lumi/         │
  │    mod    │              │    CLAUDE.md             │
  └─────┬─────┘              │    context.json          │
        │ spawns             │    notes/<world>.md      │
        │                    │    shots/  scans/        │
        ▼                    └──────────────────────────┘
  claude -p --output-format stream-json --resume <session>
        │                              ▲
        │  {"summary":…,"commands":[…]} │ reads
        ▼                              │
  approval menu ──► click ──► queue ──► chat, as the player
```

`CLAUDE.md` is auto-discovered by Claude Code at session start, so a new session knows it is
driving a Minecraft client before reading the first prompt.

### Bridge

A loopback-only HTTP server (default `127.0.0.1:25599`) lets a Claude Code session you started
yourself read game state and push proposals into the approval menu. The token is written to
`lumi/bridge.json`.

```bash
curl -s -H "X-Lumi-Token: $TOKEN" http://127.0.0.1:25599/context

curl -s -X POST -H "X-Lumi-Token: $TOKEN" \
  -d '{"summary":"example","commands":[{"cmd":"//limit -1","note":"lift the block cap"}]}' \
  http://127.0.0.1:25599/propose
```

## Building

```bash
git clone https://github.com/omeldar/lumi.git
cd lumi
./gradlew build              # jar is written to build/libs/
python tools/make_icons.py   # regenerate the font sheet and mod icon
```

Gradle provisions a matching JDK automatically. Note that Fabric Loom requires a Java 25 JVM to
*run* Gradle, while the mod itself targets Java 21.

## Roadmap

- Render proposals as a HUD list without opening a screen
- Litematica schematic generation from a description

## License

MIT — see [LICENSE](LICENSE).

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
Not made, endorsed or supported by Anthropic. Claude and Claude Code are trademarks of Anthropic.

This project is published and maintained by [omeldar](https://github.com/omeldar), who alone is
responsible for it. Contact: [GitHub Issues](https://github.com/omeldar/lumi/issues).
