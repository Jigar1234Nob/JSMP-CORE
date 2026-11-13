# JSMPCore DDR RAM resource-pack template

This folder is a starter resource pack for the three custom JSMPCore items:

- DDR3 RAM: `jsmp:ddr3_ram`
- DDR4 RAM: `jsmp:ddr4_ram`
- DDR5 RAM: `jsmp:ddr5_ram`

## 1. Add your artwork

Create three PNG files and place them here:

```
assets/jsmp/textures/item/
  ddr3_ram.png
  ddr4_ram.png
  ddr5_ram.png
```

Use square images. 16x16, 32x32, or 64x64 pixels work well for inventory icons. Keep transparent backgrounds if you want the item to have a cut-out shape.

The model JSON files are already configured to use those exact filenames. You do not need to edit them unless you rename a texture.

## 2. Set the Minecraft pack format

Copy `pack.mcmeta.example` to the resource-pack root and rename the copy to `pack.mcmeta`.

**Important:** replace the example `pack_format` value with the resource-pack format required by the exact Minecraft 26.3 client version your players use. The example uses `0` as a deliberate placeholder, not a valid release value. Check the Minecraft version's resource-pack format before distributing the pack.

## 3. Build the ZIP

The ZIP must contain `pack.mcmeta` and `assets/` at its root. Do not zip the outer template folder itself.

Correct:
```
DDR-RAM-Pack.zip
  pack.mcmeta
  assets/
    jsmp/
      items/
      models/
      textures/
```

Incorrect:
```
DDR-RAM-Pack.zip
  DDR-RAM-RESOURCE-PACK-TEMPLATE/
    pack.mcmeta
    assets/
```

## 4. Test in-game

1. Enable the pack in Minecraft.
2. Give yourself the items with `/jsmpitem ddr3`, `/jsmpitem ddr4`, or `/jsmpitem ddr5`.
3. Check that each item displays the matching texture.
4. If an item is missing its texture, check spelling and make sure the PNG path matches the model JSON exactly.

The plugin already assigns the model IDs. The resource pack only supplies their appearance, so no plugin rebuild is needed when you change the PNG artwork.
