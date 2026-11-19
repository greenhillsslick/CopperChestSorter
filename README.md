# Copper Chest Sorter

Fabric mod project targeting **Minecraft 26.3**. It adds a special-purpose sorter by using the vanilla copper-golem appearance and AI navigation.

## Build requirements

- Java 25
- Gradle 9.8.1 or newer (the Fabric Loom 1.19.3 toolchain)
- Fabric Loader 0.19.5+
- Fabric API 0.165.0+26.3

Build the mod jar with:

```sh
gradle build
```

The jar is written to `build/libs/`. A compiled jar is also provided alongside this source project.

## How to use in-game

1. Put a **copper block** on the bottom, a **hopper** on it, and a **carved pumpkin** on top.
2. The three ingredients are consumed and a Copper Chest Sorter appears.
3. Rename a chest in an anvil (for example, `Cobblestone`). Put it within 12 blocks horizontally and 5 blocks vertically of the sorter.
4. It removes non-matching items from the named chest into nearby *unnamed* chests, then fetches matching items from nearby unnamed chests. To sort several item types in one chest, separate their displayed names with commas, for example `Cobblestone, Dirt, Oak Log`. Names are matched as complete names/IDs, not suffixes (`Stone` will not match `Redstone`); case, spaces, underscores, and hyphens are ignored. A chest with the vanilla default title (`Chest`, `Trapped Chest`, or their translation key/localized equivalent) is treated as ordinary unnamed storage.
5. Each successful deposit into a chest plays a Minecraft note-block bell. If there is not enough spare room in other nearby chests to empty unwanted items out of the named chest, sorting pauses. A distinct higher-pitched bell alarm rings immediately and then at most once every 30 seconds until there is room.
6. Craft the guide book with one book and one copper ingot in any arrangement. It explains the build, behavior, and item/category sorting.
7. For sign storage, leave the chest unnamed and attach a sign to it. You can write the exact name of any item or block (for example, `Diamond`, `Diamond Block`, `Redstone`, or a modded item's name); matching uses registered item IDs and displayed names, so individual item IDs do not need to be hard-coded. Separate multiple entries with commas. Category aliases such as `wood`, `slabs`, `fences`, `wool`, `sword`, and `axe` are also supported. Wrap an entry in parentheses to require one exact item or block: `(Stone)` matches only Stone, not Stone Hoe or names that merely contain `stone`. Such a chest only pulls matching items from ordinary unnamed, unsigned chests. Double chests inherit the sign/name rule across both halves, so their halves are never mistaken for separate ordinary storage. Renamed exact-item chests and other sign-configured chests are protected from being used as sources.

8. Right-click the Copper Chest Sorter with an empty hand to open the request screen. Enter an exact item/block name or registry ID (such as `Stone` or `minecraft:stone`) and a quantity. It searches nearby chests, takes only that exact item, and throws it toward you. If there is not enough, it delivers what it found, completes the request, and tells you how many items were delivered and how many are missing. Open the screen again to make a new request.

The sorter uses the vanilla copper-golem entity model and navigation; it physically walks to the source and destination chests, triggers their lid open/close animation, and walks at a slightly increased speed (1.3). Its search radius stays anchored to where it was built, and it can route separate item stacks to different chests with free slots. Its special role is marked internally by its hidden custom name. The build structure is checked after a player uses a block, so place the carved pumpkin by hand.

## Notes

- Only chests and trapped chests are searched; barrels and other inventories are intentionally ignored.
- Other named chests are protected as destinations, so one sorting chest will not dump into another labelled chest.
- Vanilla copper-golem oxidation behavior is retained in this initial implementation. Wax the sorter with honeycomb in-game if you want to prevent oxidation.
- The supplied jar was compiled successfully with Java 25 and Gradle 9.8.1. Install Fabric Loader and Fabric API 0.165.0+26.3 on the client/server as well.
