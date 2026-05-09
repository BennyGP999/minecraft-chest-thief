# Chest Thief Mod

Not all Wandering Traders make it. Some are cast out — blacklisted by the guild for shady dealings, failed debts, or one too many suspicious trades. With no caravan to follow and no llama to carry their goods, they turn to the only skill they ever truly mastered: knowing what things are worth. Now they wander the land alone, breaking into homes, raiding villages, and vanishing into the night with your most valuable possessions — selling everything on the black market to whoever will pay.

This mod introduces these outcasts into your world. They are known as **Chest Thieves**.

## Behaviour

### Daytime
- Roams configured biomes searching for chests within its detection radius
- Opens chests and steals the most valuable item inside
- Replaces stolen items with carrots as "payment"
- Can open **doors, fence gates and trapdoors** — it will push past simple barriers and navigate labyrinths to reach a chest. Gates and trapdoors are opened as needed and closed again behind it
- Climbs **ladders, vines and scaffolding** to reach chests on upper floors or in basements
- **Swims across rivers and lakes** — water is not a barrier
- Steps over 1-block-tall obstacles through normal movement, and can **leap across gaps up to 2 blocks wide** in the terrain
- Navigates complex and winding paths — including labyrinths — that would stop most mobs
- If a chest turns out to be unreachable, the thief moves on and finds a different one after approximately 10 seconds
- When no chest is available, it wanders naturally rather than standing still
- Multiple Chest Thieves spread out and target different chests rather than swarming the same one
- Always faces toward its target chest

### Nighttime
The thief does **not** become hostile at night. Instead it uses the darkness to steal more boldly:
- Continues stealing from chests through the night
- Moves slightly faster
- **Temporarily turns invisible** while approaching a chest — becoming visible again the moment it starts stealing
- Invisibility activates randomly throughout the night with a configurable chance and cooldown

### Carry Inventory
- Each Chest Thief carries up to **5 item slots** of stolen loot (configurable)
- Once all slots are full, it walks away from the crime scene
- All carried items **drop on death** — kill the thief to recover what it stole

### The Syndicate

Chest Thieves are not lone operators — they belong to an underground criminal network known as **The Syndicate**. When a Chest Thief departs with stolen loot, it does not simply vanish. It delivers everything to a hidden base buried beneath the surface.

**Bases**
- Syndicate bases generate automatically underground, spaced roughly every 64 chunks (configurable)
- Each base is a fortified bunker stamped from a hand-designed `.nbt` structure and placed flush with the natural terrain surface
- The shaft entrance on the surface is marked by a square of mossy cobblestone with a ladder descending into the dark
- Bases can be located with the vanilla command `/locate structure syndicate:syndicate_base`, which returns the exact XYZ coordinates of the shaft entrance
- Bases persist across server restarts — their positions, loot totals, and guard lists are saved to the world's `level.dat`

**Starter loot**
- When a base is first created, up to **10 items** are distributed across its chests (configurable via `starterLootTotal`)
- Of those, **3 items** are valuable goods drawn from the ChestThief priority list (medium priority 100–400: iron tools, food, base materials) — configurable via `starterValuableCount`
- The remaining items are common filler: bread, coal, planks, sticks, bones, leather, arrows, torches, and similar supplies that give the base a realistic "stocked hideout" feel
- Items are shuffled and spread evenly across all chests in the base

**Loot accumulation**
- Every Chest Thief that completes a delivery drops its stolen items into the nearest base's chests (within `lootDeliveryRadius` blocks, default 200)
- If no base is close enough or the chests are full, the items are sold locally and lost permanently
- The more thieves operate in a region, the richer the base becomes

**Finding a base**
- **Wandering Traders** occasionally carry a **Syndicate Map** (25% chance by default, price: 32 emeralds)
- Right-clicking the map generates a filled Minecraft map centred on the nearest active base, marked with a target X
- The map behaves like a treasure map — your position is shown as a moving marker even when you are far outside the map's bounds, making navigation to the base straightforward
- You can also use `/locate structure syndicate:syndicate_base` to get the precise coordinates of the shaft entrance

**The Syndicate Chest**
- A custom block (craftable, also found in creative mode under Functional Blocks) that acts as the delivery point for Chest Thieves
- Visually distinct from vanilla chests — dark red texture with the same geometry
- Thieves search for the nearest Syndicate Chest within the delivery radius when departing; if found, stolen loot is placed inside

**Raiding a base**
- Bases are guarded by **Syndicate Guards** — ranged fighters who never leave the bunker
- Guards fire **carrot arrows** (the arrows are visually indistinguishable from thrown carrots, and drop a carrot on impact — collect them as supplies)
- Find the shaft entrance on the surface (mossy cobblestone patch), descend the ladder, and fight your way through
- Guard numbers scale with the total loot inside the base — a rich, long-established base will be heavily defended
- Guards respawn periodically when no player is nearby (every 5 minutes by default)
- Clear out the chests to reclaim what the thieves stole

### Departure
When a Chest Thief has carried a full inventory for long enough, it departs with its loot:
- After **20 seconds** with a full inventory, it begins walking away
- Walks away for **30 seconds**, then despawns — the loot is delivered to the nearest Syndicate base or sold on the black market
- Attacking it during departure triggers the normal panic or berserker response; the departure timer continues regardless
- A Chest Thief that never fills its inventory will still depart after **2 Minecraft days**
- Chest Thieves never despawn randomly based on distance — only through departure or death

### When Attacked
Two possible outcomes chosen at random (configurable chance), applies both day and night:

- **Panic (60% default):** Sprints away from the attacker, **drops one random carried item** on the ground as it flees. Resumes normal behaviour once the panic period ends.
- **Berserker (40% default):** Enters an aggressive state for a configurable duration (default: 15 seconds):
  - **Movement speed increased by 50%** — can outrun most players
  - Aggressively scans for targets every second — if the current target dies or escapes, automatically picks a new one (priority: last attacker → nearest player → iron golem → villager)
  - Being hit again **resets the timer**, extending the aggression

### Spawn Attraction
Chest Thieves are drawn to places where there is something worth stealing. They require a minimum number of chests nearby before they will spawn naturally — so a player who builds in open wilderness with no storage is unlikely to see one. Once chests accumulate, thieves start appearing. The more you store, the more you attract.

Protect your base with walls and lighting to prevent spawns entirely.

### Leashable
- Attach a lead and use the Chest Thief as a chest-detection hound
- While leashed it detects and faces chests but does not steal

---

## Mob Stats

### Chest Thief

| Stat | Normal | Night | Berserker |
|------|--------|-------|-----------|
| Health | 20 HP | 20 HP | 20 HP |
| Attack damage | 3 | 3 | 3 |
| Movement speed | 0.27 | 0.35 (+30%) | 0.405 (+50%) |
| Jump strength | 0.5 | 0.5 | 0.5 |
| Armor | 2 | 2 | 2 |
| Follow range (target) | — | — | 50 blocks |
| Spawn category | MONSTER | MONSTER | MONSTER |

### Syndicate Guard

| Stat | Value |
|------|-------|
| Health | 20 HP |
| Attack type | Ranged only — fires carrot arrows |
| Arrow base damage | 2.3 (configurable via `guardArrowDamage`) |
| Movement speed | 0.25 |
| Follow range | 20 blocks |
| Preferred shooting range | 8–15 blocks |
| Spawn category | MONSTER |
| Despawn | Never — guards only die in combat or when the base is cleared |
| Containment | Cannot leave the base — the vertical shaft is the only exit, and guards cannot pathfind up ladders |

Guards strafe sideways while shooting and back away if a player closes within 8 blocks. They will navigate toward a player who is within follow range but lacks a clear line of sight. Line-of-sight is checked every 3 ticks to avoid excessive raycasting with many guards.

---

## Configuration

Two config files are created on first run in the Minecraft `config/` directory.

### `chest_thief_config.json`

```json
{
  "chestInteractionIntervalTicks": 60,
  "chestDetectionRadius": 100,
  "chestDetectionMaxVerticalDist": 16,
  "stealOnlyListedItems": false,
  "panicChance": 0.6,
  "panicDurationTicks": 80,
  "leaveDurationTicks": 200,
  "maxCarrySlots": 5,
  "berserkDurationTicks": 300,
  "berserkSpeedMultiplier": 1.5,
  "berserkFollowRange": 50.0,
  "nightSpeedBonus": 0.08,
  "stealthChance": 0.75,
  "stealthMinTicks": 60,
  "stealthMaxTicks": 140,
  "stealthCooldownMinTicks": 200,
  "stealthCooldownMaxTicks": 400,
  "departDelayTicks": 400,
  "departDurationTicks": 600,
  "maxAgeTicks": 48000,
  "leavingSoundEnabled": true,
  "leavingSoundMinTicks": 80,
  "leavingSoundMaxTicks": 160,
  "spawnBiomes": [
    "minecraft:plains",
    "minecraft:forest",
    "minecraft:birch_forest",
    "minecraft:dark_forest",
    "minecraft:taiga",
    "minecraft:savanna"
  ],
  "spawnWeight": 20,
  "spawnMinGroup": 1,
  "spawnMaxGroup": 2,
  "spawnMinNearbyChests": 2
}
```

| Field | Default | Description |
|-------|---------|-------------|
| `chestInteractionIntervalTicks` | 60 | Ticks between steal attempts (20 = 1 second) |
| `chestDetectionRadius` | 100 | Chest detection radius in blocks |
| `chestDetectionMaxVerticalDist` | 16 | Maximum vertical distance (up or down) to detect a chest |
| `stealOnlyListedItems` | false | If true, only items listed in `chest_thief_values.json` are stolen |
| `panicChance` | 0.6 | Probability (0.0–1.0) of panicking vs. going berserk when hit |
| `panicDurationTicks` | 80 | How long the mob sprints away during panic (4 seconds) |
| `leaveDurationTicks` | 200 | How long the mob walks away from a chest after filling up (10 seconds) |
| `maxCarrySlots` | 5 | Maximum number of item stacks the mob can carry at once |
| `berserkDurationTicks` | 300 | How long berserker mode lasts without new provocations (15 seconds) |
| `berserkSpeedMultiplier` | 1.5 | Speed multiplier during berserker mode (1.5 = +50% of base speed) |
| `berserkFollowRange` | 50.0 | Radius in blocks within which the berserker scans for new targets |
| `nightSpeedBonus` | 0.08 | Flat speed bonus at night added to base speed |
| `stealthChance` | 0.75 | Probability (0.0–1.0) that invisibility activates each time the cooldown expires at night |
| `stealthMinTicks` | 60 | Shortest invisibility duration (3 seconds) |
| `stealthMaxTicks` | 140 | Longest invisibility duration (7 seconds) |
| `stealthCooldownMinTicks` | 200 | Shortest cooldown between invisibility periods (10 seconds) |
| `stealthCooldownMaxTicks` | 400 | Longest cooldown between invisibility periods (20 seconds) |
| `departDelayTicks` | 400 | How long with a full inventory before departure begins (20 seconds) |
| `departDurationTicks` | 600 | How long the mob visibly walks away before despawning (30 seconds) |
| `maxAgeTicks` | 48000 | Maximum lifetime before forced departure regardless of inventory (2 Minecraft days) |
| `leavingSoundEnabled` | true | Whether the thief plays a sound while sneaking away — set to false to disable |
| `leavingSoundMinTicks` | 80 | Shortest interval between sounds while leaving (4 seconds) |
| `leavingSoundMaxTicks` | 160 | Longest interval between sounds while leaving (8 seconds) |
| `spawnBiomes` | 6 biomes | Biome IDs where the mob spawns naturally |
| `spawnWeight` | 20 | Spawn frequency (cows = 8 for reference) |
| `spawnMinGroup` | 1 | Minimum group size per spawn |
| `spawnMaxGroup` | 2 | Maximum group size per spawn |
| `spawnMinNearbyChests` | 2 | Minimum number of chests within detection range required for a natural spawn |

### `syndicate_config.json`

```json
{
  "guardArrowDamage": 2.3,
  "baseSpacingChunks": 64,
  "baseSeparationChunks": 24,
  "guardsPerItem": 0.5,
  "minGuards": 2,
  "maxGuards": 16,
  "starterLootTotal": 10,
  "starterValuableCount": 3,
  "raidThreshold": 0.3,
  "mapTraderChance": 0.50,
  "lootDeliveryRadius": 200.0,
  "guardRespawnIntervalTicks": 6000,
  "guardRespawnPlayerBuffer": 8
}
```

| Field | Default | Description |
|-------|---------|-------------|
| `guardArrowDamage` | 2.3     | Base damage of each carrot arrow fired by a guard. Vanilla skeleton arrow = 2.0. Minimum: 0.5 |
| `baseSpacingChunks` | 64      | Distance in chunks between base region centres (~1024 blocks). Higher = rarer bases |
| `baseSeparationChunks` | 24      | Minimum distance in chunks from a cell boundary to the base centre. Must be less than half of `baseSpacingChunks` |
| `guardsPerItem` | 0.5     | Guards spawned per item in the base (`floor(lootCount × guardsPerItem)`), clamped to `[minGuards, maxGuards]` |
| `minGuards` | 2       | Minimum guards even when the base is empty |
| `maxGuards` | 16      | Maximum guards regardless of loot count |
| `starterLootTotal` | 10      | Total number of items placed across all chests when the base is first created (max 27) |
| `starterValuableCount` | 3       | Of `starterLootTotal`, how many are valuable items from the ChestThief priority list (medium priority 100–400). The rest are common filler. Must be ≤ `starterLootTotal` |
| `raidThreshold` | 0.3     | Fraction of peak loot that must remain for the base not to count as raided (0.3 = raided when under 30% remains) |
| `mapTraderChance` | 0.50     | Probability (0.0–1.0) that a Wandering Trader carries a Syndicate Map |
| `lootDeliveryRadius` | 200.0   | Maximum distance in blocks within which a departing thief searches for a Syndicate Chest to deliver loot into. If none is found, loot is lost |
| `guardRespawnIntervalTicks` | 6000    | Ticks between each guard respawn check (6000 = 5 minutes). Respawn only happens when no player is within the base bounds plus `guardRespawnPlayerBuffer` |
| `guardRespawnPlayerBuffer` | 8       | Extra blocks around the base AABB used for the player proximity check during guard respawn. Prevents guards materialising mid-fight |

### `chest_thief_values.json`

Defines item steal priority. Higher value = stolen first.

With `stealOnlyListedItems: false` (default): any item not listed gets a minimum priority of 1 and will still be stolen. The list controls *which items are preferred*.

With `stealOnlyListedItems: true`: only items explicitly listed here are stolen. Everything else is ignored — useful for servers that only want to protect specific items.

Items with priority 100–400 are also used as the "valuable" portion of base starter loot. Add modded items using their full resource location.

```json
{
  "minecraft:nether_star": 1000,
  "minecraft:elytra": 900,
  "minecraft:diamond": 500,
  "minecraft:iron_ingot": 200
}
```

---

## Customisation

### Guard shoot sound

The sound played when a guard fires a carrot arrow is loaded from:

```
assets/syndicate/sounds/guard_shoot.ogg
```

Replace this file with any mono OGG Vorbis file to change the sound. The file is registered under the sound event ID `syndicate:entity.syndicate_guard.shoot` in `assets/syndicate/sounds.json`. No code changes are required.

---

## Changelog

### 1.2.0

**Guards, maps and loot**

- Syndicate Guards now fire **carrot arrows** instead of standard arrows — each arrow visually resembles a flying carrot and drops a carrot on the ground when it hits a block
- Guard arrow damage is now configurable via `guardArrowDamage` in `syndicate_config.json` (default 2.3, ~15% above vanilla skeleton)
- Guard shooting sound replaced with a dedicated sound event (`syndicate:entity.syndicate_guard.shoot`) loaded from `assets/syndicate/sounds/guard_shoot.ogg` — replace the OGG file to customise the sound without touching code
- **Syndicate Map** now behaves like a treasure map: the player's position is shown as a moving marker even when far outside the map bounds, making navigation to the base much easier
- `/locate structure syndicate:syndicate_base` now works correctly and returns the precise XYZ coordinates of the shaft entrance
- Starter loot split into valuable and filler categories: bases now start with up to 10 items total, of which 3 are valuable goods from the ChestThief priority list and the rest are common supplies
- New config fields: `starterValuableCount`, `lootDeliveryRadius`, `guardRespawnIntervalTicks`, `guardRespawnPlayerBuffer`, `guardArrowDamage`
- Guard follow range corrected to 20 blocks (was 16 in documentation)
- **Syndicate Chest** block added — custom dark-red chest used as the loot delivery target for departing thieves; available in creative mode under Functional Blocks

### 1.1.0

**The Syndicate update**

- Chest Thieves now belong to an underground criminal network — stolen loot is delivered to a hidden base instead of disappearing permanently
- Syndicate bases generate automatically under the terrain, spaced across the world (configurable frequency)
- Bases contain six double chests pre-filled with starter loot
- Bases are guarded by **Syndicate Guards** — their numbers scale with the amount of accumulated loot
- **Syndicate Map** item: buy from Wandering Traders (25% chance, 32 emeralds) to reveal the nearest base on a map
- New config file `syndicate_config.json` with tunable parameters for base spacing, guard counts, loot scaling, and map availability

### 1.0.1

- Climbs ladders, vines and scaffolding to reach chests on upper floors and in basements
- Opens fence gates and trapdoors to get past simple barriers — and closes them again on the way out
- Leaps over small gaps in the terrain
- Moves on to a different chest if one turns out to be unreachable
- Various movement and navigation improvements

---

## Dependencies

- **Minecraft**: 26.1.x
- **Fabric Loader**: ≥ 0.18.6
- **Fabric API**: required
- **Java**: 25

---

## Building from Source

Requirements: Java 25, Git

```bash
git clone https://github.com/BennyGP999/minecraft-chest-thief.git
cd minecraft-chest-thief
./gradlew build
```

Output JAR: `build/libs/chest-thief-fabric-<mc-version>-<mod-version>.jar`

---

## License

MIT — see [LICENSE](LICENSE)
