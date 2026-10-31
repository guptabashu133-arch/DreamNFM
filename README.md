# DreamNFM

Holograms, NPCs and clickable map touchscreens for **Paper and Folia** (1.21.9+ / 26.x).
Everything is vanilla, with no resource pack and no packet library.

| Module | What it is |
|---|---|
| `/holo` | FancyHolograms-style text, item and block holograms, plus **spin**, **bob**, a **proximity pop-up**, per-player text and **live status colours** |
| `/npc` | FancyNpcs-style NPCs on the vanilla **Mannequin** entity: skins, look-at, glow, scale, pose, equipment, click actions, a **spinning icon** above the head and a **pop-up info panel** |
| `/screen` | **Map touchscreens**: walls of maps you click on, with your own PNG/JPG images, pages, buttons, hover effects, a mouse cursor, player heads, progress bars and live graphs |

## Install
1. Put `DreamNFM.jar` in `plugins/`. Installing PlaceholderAPI is optional.
2. Restart the server. Every command needs `dreamnfm.admin` (op by default).

## Touchscreens (`/screen`)
1. Stand in front of a wall and look at the block where the **top-left** corner should go.
2. Run `/screen create lobby 4 3`. That makes 4 maps wide by 3 tall; the screen grows right and down.
3. The screen gets a ready-made template: a title, your name, an online-status line, buttons, a progress bar and an info page with your head and an online graph.
4. Edit `plugins/DreamNFM/screens/lobby.yml`, then run `/screen reload lobby`.

Put your own pictures in `plugins/DreamNFM/images/`. They are scaled and dithered to map colours automatically.

```yaml
start: home
idle-page: sleep          # optional: shown to players further than wake-range
wake-range: 10
click-sound: ui.button.click
pages:
  home:
    background: "#14161F"           # or background-image: bg.png
    refresh: 20                     # ticks between redraws (placeholders)
    next: news                      # optional slideshow...
    seconds: 10                     # ...after 10 s
    elements:
      - type: image
        file: logo.png
        x: center
        y: 8
        w: 64
        h: 64
      - type: text
        x: center
        y: 80
        scale: 2
        text: "&#7AA2FF&lWelcome {player}"
      - type: button
        x: center
        y: 50%
        w: 60%
        h: 26
        text: "&lPLAY"
        icon: sword.png
        color: "#22C55E"
        hover-color: "#4ADE80"
        actions: ["server:duels", "sound:entity.player.levelup"]
      - type: progress
        x: center
        y: -8
        w: 80%
        h: 6
        value: "{online}"
        max: "{max}"
        text: "{percent}%"
        color: "<status:{online};0=#EF4444;default=#22C55E>"
```

**Positions and sizes:** `20` is pixels from the left/top, `-20` counts from the right/bottom, `50%` is a share of the screen, and `center` centres the element.

**Elements:**

| Type | What it draws | Extra options |
|---|---|---|
| `rect` | A coloured box | `round`, `border`, `border-color` |
| `text` | One line of text | `scale`, `align`, `shadow` |
| `lines` | Several lines of text | `lines: [...]`, `spacing` |
| `image` | One of your pictures | `file` (`w`/`h` are optional) |
| `button` | A clickable button | `text`, `icon`, `color`, `hover-color`, `text-color` |
| `head` | A player's face | `player`, `size` |
| `progress` | A progress bar | `value`, `max`, `text` with `{percent}` |
| `graph` | A live graph | `source: online` or `source: tps` |

**Options for any element:**
- `actions` makes the element clickable.
- `permission` shows the element only to players who have that permission.
- `cooldown` sets the click cooldown in ms.

**Actions:**

| Action | What it does |
|---|---|
| `page:<page>` | Opens another page |
| `page:back` | Goes back to the previous page |
| `close` | Returns to the start page |
| `message:<text>` | Sends the player a message |
| `player:<cmd>` | Runs a command as the player |
| `console:<cmd>` | Runs a command from the console |
| `sound:<key>[:vol:pitch]` | Plays a sound |
| `teleport:world,x,y,z[,yaw,pitch]` | Teleports the player |
| `server:<name>` | Sends the player to a BungeeCord/Velocity server |

**How touching works:**
- You can click from up to `touchrange` blocks away (default 8): punch, left-click the air or right-click.
- The screen shows a cursor where you look, and buttons light up when you hover over them.
- Every player sees their own page, hover and placeholders.

**Commands:**

| Command | What it does |
|---|---|
| `/screen create <name> <w> <h>` | Makes a new screen |
| `/screen delete <name>` | Removes a screen |
| `/screen list` | Lists all screens |
| `/screen info <name>` | Shows a screen's details |
| `/screen tp <name>` | Teleports you in front of a screen |
| `/screen reload [name]` | Reloads pages and images |
| `/screen page <screen> <page\|back\|home> [player]` | Opens a page for a player |
| `/screen images` | Lists your image files |
| `/screen set <name> viewrange\|touchrange\|cursor <value>` | Changes a screen setting |
| `/screen respawn <name>` | Places a screen's frames again |

Screens of up to about 6x4 are recommended. The limit is 10x10.

## Holograms (`/holo help`)
- **Text options:** `create`, `line add|set|insert|remove`, `item`, `block`, `scale`, `billboard`, `background`, `shadow`, `seethrough`, `align`, `opacity`, `linewidth`, `glow`, `brightness`.
- **Animation:**
  - `spin <deg/s>` turns item and block holograms smoothly.
  - `bob <blocks>` makes them float up and down.
- **`proximity <radius>`:** the hologram pops open with a scale animation when a player comes near, and closes again when they leave.
- **`personal`:** each player sees their own placeholders.
- **`action add <action>`:** makes the hologram clickable.

**Placeholders:**
- **Built in:** `{player}`, `{online}`, `{max}`, `{world}`, `{ping}`, `{time}`, `{date}`.
- **Countdown:** `{countdown:2026-12-31 20:00}`.
- **PlaceholderAPI:** `%...%` placeholders, if PlaceholderAPI is installed.
- **Live status rules:** `<status:%server_online%;0=&cEmpty;1-10=&eQuiet;default=&aBusy>`. These also work inside screen colours.

## NPCs (`/npc help`)
NPCs use the vanilla Mannequin entity, so they need 1.21.9+.

- **Basics:** `create <name> [skin]`, `skin`, `name`, `lookat`, `glow`, `scale`, `pose`, `equip`, `action`.
- **`icon`:** a spinning item floats above the head.
- **`panel add <line>`:** an info hologram pops open when a player walks up to the NPC.

## Folia notes
- Everything runs on the correct region thread. Entities spawn only near players and are never saved to the world, so a crash can't leave copies behind.
- On Folia the NPC glow is always white, because Folia has no scoreboard teams.
- Bedrock (Geyser) players see the screens. They may not see Mannequin NPCs or display-entity holograms.
