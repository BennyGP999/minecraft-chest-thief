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

### Departure
When a Chest Thief has carried a full inventory for long enough, it departs with its loot:
- After **20 seconds** with a full inventory, it begins walking away
- Walks away for **30 seconds**, then despawns — the loot is sold on the black market and permanently lost
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

| Stat | Normal | Night | Berserker |
|------|--------|-------|-----------|
| Health | 20 HP | 20 HP | 20 HP |
| Attack damage | 3 | 3 | 3 |
| Movement speed | 0.27 | 0.35 (+30%) | 0.405 (+50%) |
| Jump strength | 0.5 | 0.5 | 0.5 |
| Armor | 2 | 2 | 2 |
| Follow range (target) | — | — | 50 blocks |
| Spawn category | MONSTER | MONSTER | MONSTER |

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
| `chestDetectionMaxVerticalDist` | 16 | Maximum vertical distance (up or down) to detect a chest. Low values prevent revealing deep dungeons; high values allow finding hidden underground bases — tune to taste |
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
| `leavingSoundEnabled` | true | Whether the thief plays a sound while sneaking away invisibly — set to false to disable |
| `leavingSoundMinTicks` | 80 | Shortest interval between sounds while leaving (4 seconds) |
| `leavingSoundMaxTicks` | 160 | Longest interval between sounds while leaving (8 seconds) |
| `spawnBiomes` | 6 biomes | Biome IDs where the mob spawns naturally |
| `spawnWeight` | 20 | Spawn frequency (cows = 8 for reference) |
| `spawnMinGroup` | 1 | Minimum group size per spawn |
| `spawnMaxGroup` | 2 | Maximum group size per spawn |
| `spawnMinNearbyChests` | 2 | Minimum number of chests within detection range required for a natural spawn. Higher values concentrate thieves near established bases; 0 disables the check entirely |

### `chest_thief_values.json`

Defines item steal priority. Higher value = stolen first.

With `stealOnlyListedItems: false` (default): any item not listed gets a minimum priority of 1 and will still be stolen. The list controls *which items are preferred*.

With `stealOnlyListedItems: true`: only items explicitly listed here are stolen. Everything else is ignored — useful for servers that only want to protect specific items.

Add modded items using their full resource location.

```json
{
  "minecraft:nether_star": 1000,
  "minecraft:elytra": 900,
  "minecraft:diamond": 500,
  "minecraft:iron_ingot": 200
}
```

---

## Changelog

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
git clone https://github.com/pedersen/chest-thief-mod.git
cd chest-thief-mod
./gradlew build
```

Output JAR: `build/libs/chest-thief-fabric-<mc-version>-<mod-version>.jar`

---

## License

MIT — see [LICENSE](LICENSE)
