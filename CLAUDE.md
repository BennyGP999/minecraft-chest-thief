# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Code documentation style

All code in this project is documented at a level where a beginner programmer can understand what is happening and why — not just what the code does, but the reasoning behind it. This means:

- **Classes**: explain what the class is responsible for, why it exists, and how it fits into the bigger picture
- **Methods**: explain the purpose, any non-obvious logic, and what edge cases are handled
- **Fields**: explain what the field represents and when/why it changes
- **Non-obvious constructs**: explain data structures, algorithms, and API choices (e.g. why `ConcurrentHashMap` instead of `HashMap`, why `distSqr` instead of `distanceTo`)
- Comments are written in Danish (the project's primary language)

When adding or modifying code, maintain this standard throughout. Err on the side of over-explaining rather than under-explaining.

## Build

```bash
./gradlew build
```

Output JAR: `build/libs/chest-thief-fabric-<mc-version>-<mod-version>.jar`

No lint or test tasks exist. Compilation errors are the primary feedback loop — the build either passes or fails.

## Stack

- **Minecraft 26.1.1** with fully unobfuscated mojmaps (use vanilla class/method names directly — no obfuscation layer)
- **Fabric Loader 0.18.6**, **Fabric API 0.145.4+26.1.1**
- **Java 25**, Gradle with `fabric-loom` plugin
- Package root: `email.pedersen`

## Architecture

### Data flow: how the thief finds chests

Chests are tracked globally, not discovered by scanning blocks each tick.

1. **`ChestBlockEntityMixin`** injects into `BlockEntity.setLevel()` — fires for every chest loaded from disk or placed by a player. It calls `ChestTracker.addChest()`.
2. **`ChestTracker`** (static, per-dimension) holds a `ConcurrentHashMap<String, Set<BlockPos>>` of all known chest positions. The inner sets are `Collections.synchronizedSet` — **always use a `synchronized(set)` block when iterating**, as chunk-load worker threads can call `addChest()` concurrently.
3. **`ChestCoordinator`** (static) uses a `ConcurrentHashMap<UUID, BlockPos>` to prevent multiple thieves from targeting the same chest. Its `values()` view is safe to iterate without copying.
4. **`FindAndStealFromChestGoal`** queries both when searching: build an `avoidSet` from exhausted chests + `ChestCoordinator.getClaimedPositions()`, then call `ChestTracker.findNearest()`.

### Entity AI: goal priorities

All goals are registered in `ChestThiefEntity.registerGoals()`:

| Priority | Goal | Flag | Notes |
|---|---|---|---|
| 0 | `FloatGoal` | JUMP | Always active |
| 1 | `PanicFleeGoal` | MOVE | Activated by `provoke()` at 60% chance |
| 1 | `NightStealthGoal` | *(none)* | Parallel to everything — no blocking |
| 2 | `MeleeAttackGoal` | MOVE | Active when `targetSelector` has a target |
| 3 | `OpenDoorGoal` | — | For village raiding |
| 3 | `OpenGateGoal` | *(none)* | Opens FenceGate + TrapDoor; parallel to all goals |
| 3 | `DepartGoal` | MOVE | Activated by `startDeparture()` |
| 4 | `LeaveAreaGoal` | MOVE | Active when inventory is full |
| 5 | `FindAndStealFromChestGoal` | MOVE | Core stealing logic |
| 6 | `LookAtTargetChestGoal` | LOOK | Visual only |
| 7 | `WaterAvoidingRandomStrollGoal` | MOVE | Idle wandering |

**targetSelector** (who to attack):
- Priority 0: `BerserkTargetGoal` — scans every 20 ticks during berserk
- Priority 1: `HurtByTargetGoal` — reactive retaliation

### Mob state flags (in `ChestThiefEntity`)

`isPanicking`, `isBerserk`, `isDeparting`, `isStealing` — boolean fields checked by goals in `canUse()`/`canContinueToUse()`. State transitions:

- **`provoke(attacker, pos)`** → 60% → `isPanicking = true` + drop item; 40% → `startBerserk()`
- **`startBerserk()`** → applies `BERSERK_SPEED_ID` attribute modifier, `setInvisible(false)`, sets target
- **`stopBerserk()`** → removes modifier, clears target
- **`startDeparture()`** → `isDeparting = true`, begins `departTimer` countdown in `tick()`
- **`isStealing`** is set every tick by `FindAndStealFromChestGoal` (`state == State.STEALING`)

### Speed modifiers

Two `AttributeModifier` objects, both keyed by `Identifier`:
- `NIGHT_SPEED_ID` (`chest_thief:night_speed`) — `ADD_VALUE`, applied every tick when night && !berserk, removed otherwise. Uses `addOrUpdateTransientModifier()` (idempotent).
- `BERSERK_SPEED_ID` (`chest_thief:berserk_speed`) — `ADD_MULTIPLIED_BASE`, applied in `startBerserk()`, removed in `stopBerserk()`.

### NBT serialization

Minecraft 26.1.1 uses `ValueOutput` / `ValueInput` (not `CompoundTag`). Methods: `output.putBoolean(key, val)`, `input.getBooleanOr(key, default)`, `output.putInt(...)`, `input.getIntOr(...)`. Inventory is saved via `ContainerHelper.saveAllItems` / `loadAllItems`.

Always call `setInvisible(false)` in `readAdditionalSaveData()` to prevent stuck-invisible mobs after server restart.

### No random despawn

`requiresCustomPersistence()` returns `true`. Departure despawn is handled via `discard()` in `tick()` after `departTimer` reaches zero.

### Configuration

`ChestThiefConfig` is a singleton loaded from two files in Minecraft's `config/` directory:
- `chest_thief_config.json` — all tunable parameters
- `chest_thief_values.json` — item ID → steal priority integer map

**Auto-upgrade pattern**: config is always written back to disk after loading, so new fields added in code (with defaults) automatically appear in the file on next server start. Add new fields: declare with default value, add getter, add `Math.max/min` clamp in `validate()`.

### Custom sounds

`ChestThiefSounds` registers `SoundEvent` objects. Sound files go in `src/main/resources/assets/chest_thief/sounds/<name>.ogg`, declared in `sounds.json`. Currently placeholders for `open_chest` and `steal_item`.

### Client-side rendering

- `ChestThiefRenderState` — data transferred from server to client for rendering
- `ChestThiefRenderer` — populates render state each frame
- `ChestThiefModel` — animates limbs based on render state (targeting chest → reaching animation; otherwise default idle)

### PathNavigation accuracy pitfall

`PathNavigation.moveTo(x, y, z, speed)` calls `createPath(x, y, z, 1)` internally with **accuracy=1**. This means the pathfinder treats any node within Manhattan distance 1 of the target as a valid endpoint — so the mob stops 1 block short of the intended position. For chests, this puts the mob 2 blocks away instead of adjacent, preventing the distance check from triggering the steal.

**Fix**: use `createPath(BlockPos, 0)` (accuracy=0) directly, then pass the resulting `Path` to `moveTo(path, speed)`:

```java
Path path = mob.getNavigation().createPath(adjacentPos, 0);
if (path != null) {
    mob.getNavigation().moveTo(path, 1.0);
}
```

Always use this pattern when exact arrival at a specific block matters.

### maxUpStep and momentum-driven stepping

`ChestThiefEntity.maxUpStep()` returns `1.0f` — the mob can step onto any block up to 1.0 blocks tall. Chests are 0.875 blocks tall, so a mob approaching with residual forward momentum will step up and stand on top of the chest.

**Fix**: call `mob.getNavigation().stop()` at the moment the mob transitions to STEALING state. This cancels any remaining path momentum before the mob reaches the chest face.

### Mixin constraints: inherited methods

`@Shadow` and `@Inject` in a `@Mixin` can only target methods **defined directly in the target class**, not methods it inherits. Attempting to shadow or inject into an inherited method causes a "No refMap loaded" crash at startup.

**Workaround**: if you need to access a method defined in a superclass of your mixin target, create a separate `@Mixin` targeting the **parent class** and use `@Accessor` (for fields) or `@Invoker` (for methods) there. See `NodeEvaluatorAccessor` for an example.
