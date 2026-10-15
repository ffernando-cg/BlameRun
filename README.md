# BlameRun – Hardcore Speedrun

**Co-op hardcore speedruns for Paper and Purpur 26.3, where everyone knows who to blame.**

If anyone dies, the world resets and a new attempt begins, and the culprit's face shows up on everyone's screen. BlameRun keeps every player's death count next to their skin face, times each attempt with splits, and keeps records between attempts, including the hall of shame for the shortest run ever.

One jar, no dependencies, and no resource pack. English and Spanish messages are included.

## Features

- **Automatic world reset.** Every attempt gets a fresh overworld, Nether and End with the same seed and linked portals. Old worlds are deleted from disk.
- **Per-player death counter with skin faces.** It is shown in the action bar, the tab list and the sidebar, and it survives every reset.
- **Speedrun timer in the boss bar.** It starts on the first step, or with a countdown that freezes everyone until GO.
- **Splits**: Iron → Nether → Fortress → Blaze rod → Ender pearl → Stronghold → The End. Each split is compared to your best time, in green when you're ahead and in red when you're behind.
- **Records**: best time, wins, best splits, and the **hall of shame** (the shortest attempt ever).
- **Death moments**: lightning, the culprit's face on everyone's screen, a random roast line, and a summary of the attempt.
- **Victory**: fireworks and a title when the dragon dies.
- **`/catchup`** for late joiners. It teleports them to you and gives them gear of the same tier as yours, or as the server average, plus food and XP. Admins get a clickable button when someone joins mid-attempt.
- **Two end conditions**: *first death* (classic) or *all dead* (the attempt continues while anyone is alive).
- **Fixed-seed mode** to practise a specific seed.
- **Translatable**: every message is editable, and adding a language means copying a single file.

> Player faces are drawn with the text "object" components added in Minecraft 1.21.9, so clients need 1.21.9 or newer. Paper 26.3 already requires that.

## Requirements

- Paper or Purpur **26.3** (Folia and Spigot are not supported)
- Java **25**

## Installation

1. Drop `BlameRun-<version>.jar` into `plugins/`.
2. Recommended in `server.properties`: `hardcore=true`, so clients show hardcore hearts.
3. Keep the Nether (`allow-nether=true`) and the End (`settings.allow-end: true` in `bukkit.yml`) enabled, or portals won't work.
4. Start the server. The plugin creates the first attempt's worlds and sends everyone there.

The server's main world is only a waiting room, because Paper can't unload it at runtime. Players who join from outside the run are moved into the current attempt as fresh arrivals.

## How an attempt works

1. **Waiting**: everyone sees *ATTEMPT #N*. The timer starts with the first step, or with `/run start`.
2. **Running**: splits are announced as you reach them.
3. **Someone dies**: the attempt ends, a summary is shown, and after a few seconds the next attempt's worlds are generated. Everyone is moved there in survival with an empty inventory, no XP and no advancements.
4. **Dragon killed**: the attempt is won and records are updated. Start the next attempt with `/run reset`, or enable `reset-after-win`.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/deaths [player]` | everyone | Death count and rank. |
| `/deaths top` | everyone | Top 10 with faces. |
| `/deaths hud <faces\|text\|off>` | everyone | What your action bar shows. |
| `/run` · `/run stats` | everyone | Current attempt, splits and records. Alias: `/speedrun`. |
| `/run seed` | everyone after the attempt; `blamerun.seed` during it | Shows the attempt's seed. |
| `/run start` | `blamerun.run.control` | Countdown, then start the timer. |
| `/run reset [seed]` | `blamerun.run.control` | Jump to the next attempt now, optionally with a specific seed. |
| `/run cancel` | `blamerun.run.control` | Cancel a pending automatic reset. |
| `/run new` | `blamerun.run.control` | New attempt in the same world (timer back to 0). |
| `/catchup <player> [admin\|average]` | `blamerun.catchup` | Teleport a player to you and give them comparable gear. |
| `/deaths set <player> <n>` | `blamerun.deaths.edit` | Fix a death count. |
| `/deaths reset confirm` | `blamerun.deaths.edit` | Reset everyone's deaths to 0. |
| `/br reload` · `/br version` | `blamerun.reload` | Reload config and language. |

`blamerun.admin` grants every admin permission and is given to OPs by default. With `blamerun.notify` you receive the clickable *[Catch up]* button when someone joins mid-attempt.

### Undoing a cheap death

If someone dies right after spawning and you don't want it to count:

1. `/run cancel` stops the automatic reset.
2. `/run new` opens a fresh attempt in the same world.
3. `/gamemode survival <player>` revives them.
4. `/deaths set <player> <n>` restores their previous death count.

## Configuration

`plugins/BlameRun/config.yml` is fully commented. The most useful options:

| Option | What it does |
|---|---|
| `language` | `en` or `es`. Messages live in `lang/<language>.yml`. |
| `run.ends-when` | `first-death` or `all-dead`. |
| `run.auto-start` | Start the timer on first movement, or only with `/run start`. |
| `world-reset.seed` | Empty for a random seed every attempt, or set one to always play the same seed. |
| `world-reset.automatic` | `false` = reset only with `/run reset`. |
| `world-reset.delay-seconds` | Time to read the summary before the new world. |
| `world-reset.reset-after-win` | Go to the next attempt automatically after a win. |
| `world-reset.difficulty` / `hardcore` | Difficulty and hardcore mode of the run worlds. |
| `world-reset.clear-players.*` | What players lose on a new attempt: inventory, ender chest, XP, effects, advancements. |
| `world-reset.enabled` | Set to `false` to let another plugin reset the world; BlameRun then only detects the new world and keeps score. |
| `ignored-worlds` | Worlds (lobbies, etc.) the plugin never touches. |
| `hud.*` | Turn each on-screen element on or off. |
| `catchup.*` | Default mode, enchantment copying, minimum food, extra items and newcomer notices. |

When you update the plugin, new options are added to your `config.yml` automatically, and missing message keys fall back to the ones bundled in the jar.

## Translating

1. Copy `plugins/BlameRun/lang/en.yml` to `xx.yml`.
2. Translate the text. Keep the `<tags>`, because they are placeholders.
3. Set `language: xx` and run `/br reload`.

Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format), so colours and formatting are fully customisable.

## Data files

| File | Contents |
|---|---|
| `deaths.yml` | Deaths and HUD preference per player. |
| `run.yml` | Current attempt, its seed and world, and all-time records. |

Both live in the plugin folder, not in any world, so they survive resets.

## Building from source

```
./gradlew build
```

Requires JDK 25. The jar is written to `build/libs/`.
