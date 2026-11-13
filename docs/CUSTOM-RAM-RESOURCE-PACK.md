# JSMPCore custom DDR RAM items and resource-pack models

JSMPCore creates three custom hardware items with stable item-model identifiers:

| Item | Give command | Item model |
|---|---|---|
| DDR3 RAM | `/jsmpitem ddr3 [amount]` | `jsmp:ddr3_ram` |
| DDR4 RAM | `/jsmpitem ddr4 [amount]` | `jsmp:ddr4_ram` |
| DDR5 RAM | `/jsmpitem ddr5 [amount]` | `jsmp:ddr5_ram` |

The command requires `jsmp.admin`. The item model is stored on the item, so the resource pack changes its appearance without changing the item's identity or name.

## Resource-pack folder layout

Use this layout inside your resource-pack ZIP:

```
pack.mcmeta
assets/
  jsmp/
    items/
      ddr3_ram.json
      ddr4_ram.json
      ddr5_ram.json
    models/
      item/
        ddr3_ram.json
        ddr4_ram.json
        ddr5_ram.json
    textures/
      item/
        ddr3_ram.png
        ddr4_ram.png
        ddr5_ram.png
```

Each item definition points to its model. For example, `assets/jsmp/items/ddr3_ram.json`:

```json
{
  "model": {
    "type": "minecraft:model",
    "model": "jsmp:item/ddr3_ram"
  }
}
```

The corresponding model file, `assets/jsmp/models/item/ddr3_ram.json`, can be:

```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "jsmp:item/ddr3_ram"
  }
}
```

Repeat those two JSON files for DDR4 and DDR5, changing the identifier and texture name to match. Put your PNG artwork in `assets/jsmp/textures/item/`.

## Installing the pack

1. Finish the resource-pack ZIP. Keep `pack.mcmeta` at the ZIP root, not inside another nested folder.
2. Host the ZIP somewhere players can download it, such as your server's web host or a release attachment.
3. Configure the server resource-pack URL and SHA-1 in `server.properties` (`resource-pack` and `resource-pack-sha1`), then restart the server.
4. Players must accept or enable the pack. Without it, the DDR items still work but appear as their fallback vanilla materials.

The exact `pack.mcmeta` `pack_format` / `supported_formats` values must match the Minecraft client version you are targeting. Use the format values for your actual 26.3 client build rather than copying a random older pack's metadata.
