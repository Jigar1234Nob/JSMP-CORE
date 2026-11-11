# JSMPCore

A Paper 26.3 economy and shop plugin for JSMP.

## Requirements
- Paper 26.3
- Java 25

## Features
- `/shop` category-based GUI: Blocks, Gems, Farming, Food, Nether, Arsenal, Alchemy, Redstone Lab, Workstations and General Supplies.
- `/sell` deposit GUI with a sell-all button. Unsellable items are returned when the menu closes.
- Configurable item prices and sell permissions in `plugins/JSMPCore/worth.yml`.
- Prices are multiplied by `economy.price-multiplier` (default 3.0) for shop purchases, sales and item lore.
- The shop keeps normal blocks plus a curated selection of common resources, farming supplies and food. Netherite, weapons, armour, tools, potions, rare loot and unnecessary items are excluded from the shop and worth catalogue.
- Item worth shown in item hover lore, including items newly picked up or added to player inventories.
- `/balance [player]`, `/baltop`, `/pay <player> <amount>`.
- Admin economy commands: `/money <give|take|set> <player> <amount>` (also `/jsmpmoney`).
- `/vanish` toggles admin visibility, including hiding vanished admins from players who join later.
- `/god` and `/ungod` toggle admin invulnerability.
- `/rtp` teleports players to a random safe location within the configured world border; first-time joiners are automatically sent to a random location.
- `/maintenance [on|off|toggle]` blocks new logins except operators and explicitly whitelisted players, and changes the server-list MOTD to orange `Server under maintenance`.
- Lightweight server-side anti-cheat checks for suspicious reach and unusually fast block breaking, with staff alerts and configurable flag thresholds.
- Paper anti-xray and buried-container obfuscation configuration examples in `docs/`.
- YAML-backed balances, saved by UUID.
- GitHub Actions build workflow for Paper 26.3.

## Anti-cheat and anti-xray

JSMPCore includes basic server-side checks for unusually long melee reach and abnormal block-break rates. Staff with `jsmp.anticheat.alert` receive alerts; `jsmp.anticheat.bypass` bypasses checks. Configure thresholds and whether repeated flags kick players in `plugins/JSMPCore/config.yml`. Movement checking is opt-in because knockback, latency and gameplay effects can cause false positives. This is a lightweight layer, not a complete packet-level anti-cheat.

Paper's native anti-xray is used for ore and buried-container obfuscation. Merge the example in `docs/paper-antixray-overworld.yml` into `config/paper-world-defaults.yml`, and the Nether example into that world's `paper-world.yml`. Do not replace your entire config files. Restart Paper after changing anti-xray settings; `/reload` will not apply them. Paper cannot reliably hide containers that are exposed to air, and no server-side plugin can guarantee detection of every client-side ESP implementation.

## Build
Run `gradle clean build` with JDK 25. The plugin jar is created in `build/libs/`.

## Installation
1. Copy `JSMPCore-1.0.0.jar` into the server's `plugins` folder.
2. Start Paper once to generate the configuration files and the complete material worth catalogue.
3. Edit `plugins/JSMPCore/worth.yml` to set prices or disable selling for individual materials.
4. Restart the server after editing worth prices.

## Worth file
On first startup, JSMPCore writes every item material supported by the running Paper API into `worth.yml`. New materials are appended on later starts without overwriting prices you have edited. Each item has a `price` and `sellable` setting. A price of `0.0` or `sellable: false` prevents the item from being sold or purchased in the shop.

Example:

```yaml
items:
  DIAMOND:
    price: 90.0
    sellable: true
  DIRT:
    price: 0.25
    sellable: true
  BEDROCK:
    price: 0.0
    sellable: false
```

## Commands
| Command | Description |
|---|---|
| `/shop` | Open the shop |
| `/sell` | Open the sell GUI |
| `/balance [player]` | Check a balance |
| `/baltop` | Top balances |
| `/pay <player> <amount>` | Pay an online player |
| `/money give <player> <amount>` | Admin: give money |
| `/money take <player> <amount>` | Admin: take money |
| `/money set <player> <amount>` | Admin: set money |
| `/vanish` | Admin: toggle vanish |
| `/god` | Admin: enable invulnerability |
| `/rtp` | Teleport to a random safe location |
| `/maintenance [on|off|toggle]` | Operator: enable or disable maintenance mode |
| `/ungod` | Admin: disable invulnerability |

## Permissions
- `jsmp.shop`, `jsmp.sell`, `jsmp.balance`, `jsmp.baltop`, `jsmp.pay`: default true
- `jsmp.admin`: default op
- `jsmp.vanish`, `jsmp.god`: default op (also require `jsmp.admin`)
- `jsmp.rtp`: default true
- `jsmp.maintenance`: default op

## Notes
- Alchemy lists drinkable potions only. Splash and lingering potions are excluded from the shop.
- Worth is shown as a per-item value; the sell GUI multiplies it by the stack amount.
- The plugin uses the Paper API and does not require a client-side mod.

## Random teleport and maintenance

Configure `rtp.world`, `rtp.min-radius` and `rtp.max-radius` in `plugins/JSMPCore/config.yml`. RTP checks the world's border and looks for solid ground with two clear blocks above it. The default maximum radius is 10,000 blocks from the world-border centre; raise it to expand the search area, while keeping your world-generation limits in mind.

Maintenance mode is saved in the plugin config. Use `/maintenance on` to close the server to non-operators and non-whitelisted players, and `/maintenance off` to reopen it. The plugin restores the normal MOTD when maintenance is disabled.
