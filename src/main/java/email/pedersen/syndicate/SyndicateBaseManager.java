package email.pedersen.syndicate;

import email.pedersen.chestthief.ChestTracker;
import email.pedersen.syndicate.config.SyndicateConfig;
import email.pedersen.syndicate.entity.SyndicateGuardEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Statisk manager der holder styr på alle aktive syndikats-baser per dimension.
 *
 * Følger præcis samme mønster som ChestTracker: et ConcurrentHashMap ydre niveau
 * per dimension, og synchronized blok ved iteration på de indre lister.
 *
 * Denne klasse er ansvarlig for:
 *   - At registrere nye baser (tilføjes af SyndicateBasePlacer efter stamping, S07)
 *   - At finde nærmeste base til en given position (bruges af S08: loot-aflevering)
 *   - At markere baser som raidede (S10)
 *   - At svare på "er der allerede en base i denne celle?" (S06: valideringscheck)
 *
 * Trådsikkerhed:
 *   BASES_BY_DIMENSION er et ConcurrentHashMap — det ydre map er trådsikkert.
 *   De indre lister er synkroniserede via Collections.synchronizedList() og kræver
 *   synchronized-blok ved iteration, da chunk-load events kan komme fra worker-tråde.
 *
 * Status: stub — fuldt implementeret i TASK-S03.
 * hasBaseInCell() er allerede implementeret da S06 kræver den.
 */
public class SyndicateBaseManager {

    /**
     * Baser grupperet efter dimension.
     * Nøgle: ResourceKey<Level> for dimensionen (f.eks. Level.OVERWORLD)
     * Værdi: synkroniseret liste af SyndicateBase-objekter i den dimension
     *
     * ConcurrentHashMap bruges fordi der kan komme opdateringer fra chunk-load events
     * på worker-tråde. De indre lister er yderligere beskyttet med synchronized.
     */
    private static final Map<ResourceKey<Level>, List<SyndicateBase>> BASES_BY_DIMENSION =
            new ConcurrentHashMap<>();

    /**
     * Registrerer en ny base og markerer dens kister som beskyttede mod tyveri.
     * Kaldes af SyndicateBasePlacer (S07) umiddelbart efter at strukturen er stampet,
     * og af SyndicateMod.SERVER_STARTED ved genindlæsning fra disk.
     * ChestTracker.markSyndicateChest() ekskluderer kiste-positionerne fra
     * FindAndStealFromChestGoal's søgeresultater — en tyv kan aldrig stjæle fra basen.
     *
     * @param base den nyoprettede eller genindlæste base
     */
    public static void addBase(SyndicateBase base) {
        BASES_BY_DIMENSION
                .computeIfAbsent(base.getDimension(), k -> Collections.synchronizedList(new java.util.ArrayList<>()))
                .add(base);

        // Ekskludér alle base-kister fra Chest Thief's søgeliste.
        // Markering registreres også ved SERVER_STARTED (genindlæsning fra disk),
        // da eksklusionssættet i ChestTracker ryddes ved hvert server-stop.
        for (BlockPos chestPos : base.getChestPositions()) {
            ChestTracker.markSyndicateChest(base.getDimension(), chestPos);
        }
    }

    /**
     * Tjekker om der allerede eksisterer en aktiv (ikke-raidet) base i en given gitter-celle.
     *
     * Bruges som den første og billigste check i S06-valideringen (O(n) men n er typisk < 10).
     * Returnerer true hvis cellen allerede har en base — så validering af nye kandidater
     * i samme celle stoppes straks uden yderligere beregning.
     *
     * Hvad er en "celle"? Verdenen opdeles i et gitter af spacing×spacing chunks.
     * cellX = Math.floorDiv(chunkX, spacing), cellZ = Math.floorDiv(chunkZ, spacing).
     * Se SyndicateBasePlacer.computeCell() for detaljerne.
     *
     * @param cellX      gitter-cellens X-koordinat
     * @param cellZ      gitter-cellens Z-koordinat
     * @param dimension  dimensionen der søges i
     * @param spacing    cellestørrelse i chunks (fra SyndicateConfig)
     * @return true hvis cellen allerede indeholder en aktiv base
     */
    public static boolean hasBaseInCell(int cellX, int cellZ, ResourceKey<Level> dimension, int spacing) {
        List<SyndicateBase> bases = BASES_BY_DIMENSION.get(dimension);
        if (bases == null || bases.isEmpty()) return false;

        synchronized (bases) {
            for (SyndicateBase base : bases) {
                if (base.isRaided()) continue; // raidede baser tæller ikke som aktive

                // Beregn hvilken celle denne base er i
                BlockPos pos = base.getPosition();
                int baseCellX = Math.floorDiv(pos.getX() >> 4, spacing);
                int baseCellZ = Math.floorDiv(pos.getZ() >> 4, spacing);

                if (baseCellX == cellX && baseCellZ == cellZ) return true;
            }
        }
        return false;
    }

    /**
     * Finder den nærmeste aktive base til en given position i en given dimension.
     * Bruges af S08 (loot-aflevering) og S11 (kortgenerering).
     *
     * @param level   serververdenen der søges i (bruges til dimension-key)
     * @param origin  udgangspunktet for søgningen (typisk tyvens position)
     * @return den nærmeste base, eller null hvis ingen aktive baser eksisterer
     */
    public static SyndicateBase findNearest(ServerLevel level, BlockPos origin) {
        List<SyndicateBase> bases = BASES_BY_DIMENSION.get(level.dimension());
        if (bases == null || bases.isEmpty()) return null;

        SyndicateBase nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        synchronized (bases) {
            for (SyndicateBase base : bases) {
                if (base.isRaided()) continue;
                double distSq = origin.distSqr(base.getPosition());
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearest = base;
                }
            }
        }
        return nearest;
    }

    /**
     * Afleverer ét item til basen ved at lægge det i den første kiste med en ledig slot.
     *
     * Itererer over alle kiste-positioner i basen. For hver kiste:
     *   1. Tjek at chunkens er loaded — ingen force-loading (base-chunken kan være langt væk)
     *   2. Hent ChestBlockEntity og find første tomme slot
     *   3. Læg en kopi af stacken i slottet
     *
     * Hvis ingen kiste har ledige slots (alle 324 slots er fulde), returneres false
     * og itemet tabes — tyven "solgte lokalt". Dette er et designvalg: loot forsvinder
     * fremfor at tvinge en kiste-scan eller et komplekst kø-system.
     *
     * Kaldes fra SyndicateMod's ChestThiefDepartedEvent-lytter.
     *
     * @param level  serververdenen — bruges til chunk-check og block entity-opslag
     * @param base   basen der modtager looten
     * @param stack  den item-stack der skal afleveres
     * @return true hvis itemet blev lagt i en kiste, false hvis alle kister er fulde
     *         eller base-chunken ikke er loaded
     */
    public static boolean addLoot(ServerLevel level, SyndicateBase base, ItemStack stack) {
        for (BlockPos chestPos : base.getChestPositions()) {
            // Undgå force-loading — base-chunken behøver ikke være loaded for at tyven
            // kan aflevere. Tyven "solgte lokalt" hvis chunken ikke er klar.
            if (!level.hasChunk(chestPos.getX() >> 4, chestPos.getZ() >> 4)) continue;

            if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) continue;

            // Find første ledige slot. Dobbelt-kister har 54 slots (to enkelt-kiste-blokke
            // deler én container), enkelt-kister har 27 — getContainerSize() håndterer begge.
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).isEmpty()) {
                    // Kopi af stacken: det originale objekt er ejet af event-snapshot-listen
                    // og må ikke modificeres
                    chest.setItem(slot, stack.copy());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tæller det samlede antal ikke-tomme slots på tværs af alle kister i basen.
     *
     * Bruges til at opdatere base.peakLootCount efter loot-aflevering (S08)
     * og til at evaluere raid-tærsklen i ChestChangedMixin (S10).
     *
     * Springer over kister i uloadede chunks — de tæller ikke med i øjebliksbilledet.
     * Dette kan betyde at peakLootCount underestimeres en smule hvis basen er langt væk,
     * men det er acceptabelt: loot-mønsteret er asymptotisk stigende, ikke præcist.
     *
     * @param level  serververdenen
     * @param base   basen der optælles
     * @return samlet antal items (slots med en non-empty ItemStack) i alle loadede kister
     */
    public static int countTotalLoot(ServerLevel level, SyndicateBase base) {
        int count = 0;
        for (BlockPos chestPos : base.getChestPositions()) {
            if (!level.hasChunk(chestPos.getX() >> 4, chestPos.getZ() >> 4)) continue;
            if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) continue;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (!chest.getItem(slot).isEmpty()) count++;
            }
        }
        return count;
    }

    /**
     * Spawner vagter i basen hvis den aktuelt har færre end det ønskede antal.
     *
     * Ønsket antal beregnes som:
     *   targetGuards = clamp(floor(lootCount × guardsPerItem), minGuards, maxGuards)
     *
     * Tæller aktive vagter via guardUUIDs-listen:
     *   - UUID løses via level.getEntity() — returnerer null hvis chunk ikke er loaded
     *   - Uloadede vagter antages at være i live og tæller med (forhindrer duplikation)
     *   - Confirmeret døde vagter (entity != null men isAlive() == false) fjernes fra listen
     *
     * Vagter spawnes på tilfældige spawn-positioner fra base.getSpawnPositions().
     * Kun positioner i loadede chunks bruges — ingen force-loading.
     *
     * @param level serverniveauet vagterne spawnes i
     * @param base  basen der evalueres
     */
    public static void spawnGuardsIfNeeded(ServerLevel level, SyndicateBase base) {
        // Antallet af vagter er fast: én pr. spawn-position (emerald_block-markør i .nbt).
        // Spawner aldrig mere end det der er plads til i strukturen.
        int targetGuards = base.getSpawnPositions().size();

        // Opdater UUID-listen: bekræftet-døde fjernes, uloadede beholdes (antages i live)
        List<UUID> verified = new ArrayList<>();
        for (UUID uuid : new ArrayList<>(base.getGuardUUIDs())) {
            var entity = level.getEntity(uuid);
            if (entity == null) {
                // Chunk ikke loaded — antag vagten er i live
                verified.add(uuid);
            } else if (entity instanceof SyndicateGuardEntity guard && guard.isAlive()) {
                verified.add(uuid);
            }
            // entity != null men død → ikke tilføjet → implicit fjernet
        }
        base.getGuardUUIDs().clear();
        base.getGuardUUIDs().addAll(verified);

        int toSpawn = targetGuards - verified.size();
        if (toSpawn <= 0) return;

        List<BlockPos> spawnPositions = base.getSpawnPositions();
        if (spawnPositions.isEmpty()) {
            SyndicateMod.LOGGER.warn("Base at {} has no spawn positions — cannot spawn guards", base.getPosition());
            return;
        }

        // Spawn de manglende vagter på tilfældige (loadede) spawn-positioner
        var rng = level.getRandom();
        int spawned = 0;
        for (int i = 0; i < toSpawn; i++) {
            BlockPos pos = spawnPositions.get(rng.nextInt(spawnPositions.size()));
            if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            SyndicateGuardEntity guard = SyndicateMod.GUARD_ENTITY_TYPE.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (guard == null) continue;

            // Centrer på blokken, bevar Y (spawn-positionen er allerede inde i strukturen).
            // setPos + setYRot bruges fremfor moveTo(x,y,z,yaw,pitch) da 5-argument-formen
            // ikke eksisterer i MC 26.1.1 Mojmaps.
            guard.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            guard.setYRot(rng.nextFloat() * 360f);
            level.addFreshEntity(guard);
            base.getGuardUUIDs().add(guard.getUUID());
            spawned++;
        }

        if (spawned > 0) {
            SyndicateMod.LOGGER.debug(
                    "Spawned {} guard(s) at base {} (target={}, active={})",
                    spawned, base.getPosition(), targetGuards,
                    base.getGuardUUIDs().size());
        }
    }

    /**
     * Fjerner en vagt-UUID fra basen.
     * Kaldes af SyndicateMod's AFTER_DEATH-lytter når en vagt dør.
     *
     * @param base basen vagten tilhørte
     * @param uuid den døde vagts UUID
     */
    public static void removeGuard(SyndicateBase base, UUID uuid) {
        base.getGuardUUIDs().remove(uuid);
    }

    /**
     * Finder den base i en given dimension der indeholder et bestemt vagt-UUID.
     * Bruges af AFTER_DEATH-eventet til at identificere hvilken base vagten tilhørte.
     *
     * O(n × m) — n baser, m vagter pr. base. Begge tal er typisk < 20, så cost er lav.
     *
     * @param dimension dimensionen der søges i
     * @param guardUUID den søgte vagt-UUID
     * @return basen der indeholder UUID'en, eller null hvis ikke fundet
     */
    public static SyndicateBase findBaseByGuardUUID(ResourceKey<Level> dimension, UUID guardUUID) {
        List<SyndicateBase> bases = BASES_BY_DIMENSION.get(dimension);
        if (bases == null || bases.isEmpty()) return null;

        synchronized (bases) {
            for (SyndicateBase base : bases) {
                if (base.getGuardUUIDs().contains(guardUUID)) return base;
            }
        }
        return null;
    }

    /**
     * Fjerner en base fra den in-memory manager.
     * Bruges når basen er ødelagt og skal afregistreres, så /locate ikke peger derhen.
     *
     * bases.remove() bruger objekt-identitet — korrekt fordi kalderen sender præcis
     * den instans der blev fundet under iteration af den samme liste.
     *
     * @param base basen der fjernes
     */
    public static void removeBase(SyndicateBase base) {
        List<SyndicateBase> bases = BASES_BY_DIMENSION.get(base.getDimension());
        if (bases == null) return;
        synchronized (bases) {
            bases.remove(base);
        }
    }

    /**
     * Markerer en base som raidet og forbereder den til at blive erstattet.
     * Implementeres fuldt ud i TASK-S10.
     *
     * @param base basen der er blevet raidet
     */
    public static void markRaided(SyndicateBase base) {
        // TODO S10: despawn vagter, log, og trigger ny base ved næste chunk-load
        base.setRaided(true);
    }

    /**
     * Returnerer den interne liste af baser for en given dimension.
     * Bruges af SyndicateMod's respawn-tick til at iterere over alle aktive baser.
     * Returnerer null hvis ingen baser eksisterer i dimensionen.
     *
     * VIGTIGT: Listerne er Collections.synchronizedList — brug synchronized(list) ved iteration.
     *
     * @param dimension dimensionens resource key
     * @return den synkroniserede base-liste, eller null
     */
    public static List<SyndicateBase> getBases(ResourceKey<Level> dimension) {
        return BASES_BY_DIMENSION.get(dimension);
    }

    /**
     * Rydder al data fra manageren.
     * Bør kaldes ved server-stop for at undgå gammelt data ved næste start.
     */
    public static void clearAll() {
        BASES_BY_DIMENSION.clear();
    }
}
