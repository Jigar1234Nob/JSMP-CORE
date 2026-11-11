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
- The catalogue excludes rare special loot such as Elytra, Dragon Eggs, Totems, Nether Stars, Shulker Boxes, Tridents and Maces.
- Item worth shown in item hover lore, including items newly picked up or added to player inventories.
- `/balance [player]`, `/baltop`, `/pay <player> <amount>`.
- Admin economy commands: `/money <give|take|set> <player> <amount>` (also `/jsmpmoney`).
- `/vanish` toggles admin visibility, including hiding vanished admins from players who join later.
- `/god` and `/ungod` toggle admin invulnerability.
- YAML-backed balances, saved by UUID.
- GitHub Actions build workflow for Paper 26.3.

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
| `/ungod` | Admin: disable invulnerability |

## Permissions
- `jsmp.shop`, `jsmp.sell`, `jsmp.balance`, `jsmp.baltop`, `jsmp.pay`: default true
- `jsmp.admin`: default op
- `jsmp.vanish`, `jsmp.god`: default op (also require `jsmp.admin`)

## Notes
- Alchemy lists drinkable potions only. Splash and lingering potions are excluded from the shop.
- Worth is shown as a per-item value; the sell GUI multiplies it by the stack amount.
- The plugin uses the Paper API and does not require a client-side mod.
