# JSMPCore

A Paper 26.3 economy and shop plugin for JSMP.

## Requirements
- Paper 26.3
- Java 25

## Features
- `/shop` category-based GUI: Blocks, Gems, Farming, Food, Nether, Arsenal, Alchemy, Redstone Lab, Workstations and General Supplies.
- `/sell` deposit GUI with a sell-all confirmation button.
- Configurable item prices and sell permissions in `plugins/JSMPCore/worth.yml`.
- Item worth shown in item hover lore when the plugin can safely decorate the stack.
- `/balance [player]`, `/baltop`, `/pay <player> <amount>`.
- Admin economy command: `/jsmpmoney <give|take|set> <player> <amount>`.
- YAML-backed balances, saved by UUID.

## Build
Run `./gradlew build` with JDK 25. The plugin jar is created in `build/libs/`.

## Installation
1. Copy `JSMPCore-1.0.0.jar` into the server's `plugins` folder.
2. Start Paper once to generate the configuration files and the complete material worth catalogue.
3. Edit `plugins/JSMPCore/worth.yml` to set prices or disable selling for individual materials.
4. Restart the server.

## Worth file
On first startup, JSMPCore writes every item material supported by the running Paper API into `worth.yml`. New materials are appended on later starts without overwriting prices you have edited. Materials with a price of `0.0` cannot be sold.

## Commands
| Command | Description |
|---|---|
| `/shop` | Open the shop |
| `/sell` | Open the sell GUI |
| `/balance [player]` | Check a balance |
| `/baltop` | Top balances |
| `/pay <player> <amount>` | Pay a player |
| `/jsmpmoney give <player> <amount>` | Admin: give money |
| `/jsmpmoney take <player> <amount>` | Admin: take money |
| `/jsmpmoney set <player> <amount>` | Admin: set money |

## Permissions
- `jsmp.shop`, `jsmp.sell`, `jsmp.balance`, `jsmp.baltop`, `jsmp.pay`: default true
- `jsmp.admin`: default op

## Notes
- Alchemy lists drinkable potions only. Splash and lingering potions are excluded.
- The plugin uses the Paper API and does not require a client-side mod.
