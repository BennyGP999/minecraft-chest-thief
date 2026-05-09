package email.pedersen.syndicate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Persisterer syndikats-data på tværs af servergenstart.
 *
 * I Minecraft 26.1.1 bruger SavedData et Codec-baseret serialiseringssystem.
 * Alt persistens sker automatisk via CODEC-feltet — ingen manuel save()/load() nødvendig.
 * Minecraft kalder Codec.encode() ved gemning og Codec.decode() ved indlæsning.
 *
 * Gemmes i overworld's SavedDataStorage under id "syndicate:syndicate", da data spænder
 * på tværs af alle dimensioner og det er standard-konvention for global server-data.
 *
 * Indeholder:
 *   - hardRejectedCells: gitter-celler der permanent er forkastet som base-kandidater
 *     (f.eks. fordi de indeholder player-byggede blokke eller vanilla-strukturer)
 *   - bases: alle aktive SyndicateBase-instanser med fuldt serialiseret tilstand
 *     Loot-indholdet serialiseres IKKE her — det lever i de fysiske ChestBlockEntity-blokke
 */
public class SyndicateSavedData extends SavedData {

    /**
     * Permanent forkastede gitter-celler per dimension.
     *
     * En celle forkastes hårdt (og caches permanent) når afvisningsårsagen er vedvarende:
     *   - Player-byggede blokke i AABB'en (fjernes ikke af sig selv)
     *   - Overlapning med vanilla-struktur (landsby, mineshaft, stronghold — flyttes ikke)
     *
     * Nøgle: dimensionens id som tekst (f.eks. "minecraft:overworld")
     * Værdi: sæt af pakkede (cellX, cellZ)-par som long-værdier
     *
     * Pakkingen: (long)cellX << 32 | (cellZ & 0xFFFFFFFFL)
     * Giver O(1) opslag og O(1) indsætning.
     */
    private final Map<String, Set<Long>> hardRejectedCells = new HashMap<>();

    /**
     * Alle aktive syndikats-baser der er placeret i verdenen.
     * Indlæses ved serveropstart og overføres til SyndicateBaseManager.
     * Opdateres løbende: en ny base tilføjes når SyndicateBasePlacer stamper strukturen.
     *
     * Baser fjernes IKKE herfra ved raid (raided=true sættes i stedet) —
     * på den måde kan vi stadig serialisere ruinens position og bounds,
     * og SyndicateBaseManager.hasBaseInCell() kan springe over raidede baser.
     */
    private final List<SyndicateBase> bases = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Codec — serialisering til/fra disk i 26.1.1-stilen
    // -------------------------------------------------------------------------

    /**
     * Codec til AABB (aksiaaligned bounding box) — serialiseres som liste af 6 doubles.
     *
     * Rækkefølge: [minX, minY, minZ, maxX, maxY, maxZ].
     * comapFlatMap bruges fordi dekodning kan fejle (forkert antal elementer),
     * mens enkodning altid lykkes. DataResult.error() angiver fejlbeskrivelse
     * som Supplier<String> — evalueres kun hvis fejlen rent faktisk logges.
     */
    private static final Codec<AABB> AABB_CODEC = Codec.DOUBLE.listOf().comapFlatMap(
            list -> list.size() == 6
                    ? DataResult.success(new AABB(list.get(0), list.get(1), list.get(2),
                                                   list.get(3), list.get(4), list.get(5)))
                    : DataResult.error(() -> "AABB kræver 6 doubles, fik " + list.size()),
            aabb -> List.of(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ)
    );

    /**
     * Codec til ResourceKey<Level> — serialiseres som en streng i formatet "namespace:path".
     *
     * Registries.DIMENSION er registry-nøglen for dimension-registret,
     * så ResourceKey.codec(Registries.DIMENSION) giver en Codec<ResourceKey<Level>>
     * der gemmer nøglen som dens identifier (f.eks. "minecraft:overworld").
     */
    private static final Codec<ResourceKey<Level>> DIMENSION_CODEC =
            ResourceKey.codec(Registries.DIMENSION);

    /**
     * Codec til SyndicateBase — beskriver alle felter der skal gemmes til disk.
     *
     * Loot-indholdet gemmes IKKE her — det lever i de fysiske ChestBlockEntity-blokke
     * og serialiseres automatisk af Minecraft. Vi gemmer kun positions-metadata og tilstand.
     *
     * guardUUIDs: UUID.STRING_CODEC serialiserer UUID som streng (f.eks. "550e8400-e29b...")
     * i stedet for det komprimerede int[4]-format. Strenge er lettere at debugge i NBT.
     */
    private static final Codec<SyndicateBase> BASE_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BlockPos.CODEC.fieldOf("position")
                            .forGetter(SyndicateBase::getPosition),
                    AABB_CODEC.fieldOf("bounds")
                            .forGetter(SyndicateBase::getBounds),
                    DIMENSION_CODEC.fieldOf("dimension")
                            .forGetter(SyndicateBase::getDimension),
                    BlockPos.CODEC.listOf().fieldOf("chestPositions")
                            .forGetter(SyndicateBase::getChestPositions),
                    BlockPos.CODEC.listOf().fieldOf("spawnPositions")
                            .forGetter(SyndicateBase::getSpawnPositions),
                    Codec.INT.fieldOf("peakLootCount")
                            .forGetter(SyndicateBase::getPeakLootCount),
                    Codec.BOOL.fieldOf("wasOpened")
                            .forGetter(SyndicateBase::isWasOpened),
                    Codec.BOOL.fieldOf("raided")
                            .forGetter(SyndicateBase::isRaided),
                    UUIDUtil.STRING_CODEC.listOf().fieldOf("guardUUIDs")
                            .forGetter(SyndicateBase::getGuardUUIDs)
            ).apply(instance, (pos, bounds, dim, chests, spawns, peakLoot,
                               wasOpened, raided, guards) -> {
                SyndicateBase base = new SyndicateBase(pos, bounds, dim, chests, spawns, peakLoot);
                base.setWasOpened(wasOpened);
                base.setRaided(raided);
                // guardUUIDs-listen i SyndicateBase starter tom — fyld den med de gemte UUIDs
                base.getGuardUUIDs().addAll(guards);
                return base;
            })
    );

    /**
     * Codec der beskriver hvordan SyndicateSavedData konverteres til/fra persistens-format.
     *
     * Begge felter er valgfrie med tomme defaults — et nyt data-lag starter frisk uden fejl.
     * optionalFieldOf() bruges så ældre save-filer (der kun har hardRejected) stadig loader.
     */
    private static final Codec<SyndicateSavedData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.unboundedMap(
                            Codec.STRING,
                            Codec.LONG.listOf().xmap(
                                    // Fra disk (List<Long>) til hukommelse (Set<Long>).
                                    // Eksplicit returtype Set<Long> (ikke HashSet<Long>) er nødvendig
                                    // så Java's type-inferens kan matche Map<String, Set<Long>>-feltet.
                                    list -> { Set<Long> s = new HashSet<>(list); return s; },
                                    // Fra hukommelse (Set<Long>) til disk (List<Long>)
                                    set -> new ArrayList<>(set)
                            )
                    ).optionalFieldOf("hardRejected", Collections.emptyMap())
                     .forGetter(data -> data.hardRejectedCells),
                    BASE_CODEC.listOf().optionalFieldOf("bases", Collections.emptyList())
                            .forGetter(data -> Collections.unmodifiableList(data.bases))
            ).apply(instance, (hardRejected, bases) -> {
                SyndicateSavedData data = new SyndicateSavedData();
                // putAll er sikker fordi hardRejected enten er Collections.emptyMap() (ingen ændring)
                // eller et nyt map fra Codec.decode() — ingen delt reference
                data.hardRejectedCells.putAll(hardRejected);
                // bases er en umodificerbar liste fra Codec — kopier til vores mutable liste
                data.bases.addAll(bases);
                return data;
            })
    );

    /**
     * Typen der bruges til at identificere og gemme SyndicateSavedData via SavedDataStorage.
     * Den holder: id (filnavn), fabrik til nye instanser, codec til serialisering og DataFix-type.
     *
     * DataFixTypes.SAVED_DATA_COMMAND_STORAGE bruges som den mest generiske type
     * for custom mod-data — der eksisterer ingen mod-specifik DataFix-type.
     */
    public static final SavedDataType<SyndicateSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("syndicate", "syndicate"),
            SyndicateSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    // -------------------------------------------------------------------------
    // Singleton-adgang via DataStorage
    // -------------------------------------------------------------------------

    /**
     * Returnerer eller opretter SyndicateSavedData fra overworld's SavedDataStorage.
     * Minecraft kalder automatisk Codec.decode() hvis filen eksisterer, ellers new().
     *
     * Gemmes i overworld DataStorage fordi data er global — ikke bundet til én dimension.
     * Kaldet er trådsikkert da SavedDataStorage.computeIfAbsent() er synchronized.
     *
     * @param level en vilkårlig ServerLevel — vi tilgår overworld via level.getServer()
     * @return den aktuelle (eller nyoprettede) datasæt-instans
     */
    public static SyndicateSavedData getOrCreate(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // -------------------------------------------------------------------------
    // Hard-rejection API
    // -------------------------------------------------------------------------

    /**
     * Tjekker om en gitter-celle er permanent forkastet i den givne dimension.
     * O(1): ét map-opslag + ét set-opslag.
     *
     * @param cellX      gitter-cellens X-koordinat
     * @param cellZ      gitter-cellens Z-koordinat
     * @param dimension  dimensionen cellen tilhører
     * @return true hvis cellen er permanent forkastet
     */
    public boolean isHardRejected(int cellX, int cellZ, ResourceKey<Level> dimension) {
        Set<Long> rejected = hardRejectedCells.get(dimension.identifier().toString());
        return rejected != null && rejected.contains(packCell(cellX, cellZ));
    }

    /**
     * Markerer en gitter-celle som permanent forkastet i den givne dimension.
     * Kald setDirty() så Minecraft ved at filen skal skrives til disk ved næste flush.
     *
     * Bør kun kaldes ved hård afvisning (player-blokke eller vanilla-strukturer) —
     * ikke ved blød afvisning (hule, nabo-chunk ikke loadet).
     *
     * @param cellX      gitter-cellens X-koordinat
     * @param cellZ      gitter-cellens Z-koordinat
     * @param dimension  dimensionen cellen tilhører
     */
    public void addHardRejected(int cellX, int cellZ, ResourceKey<Level> dimension) {
        hardRejectedCells
                .computeIfAbsent(dimension.identifier().toString(), k -> new HashSet<>())
                .add(packCell(cellX, cellZ));
        setDirty();
    }

    // -------------------------------------------------------------------------
    // Base-persistens API
    // -------------------------------------------------------------------------

    /**
     * Tilføjer en nyoprettet base til persistenslaget.
     * Kaldes af SyndicateBasePlacer umiddelbart efter stamping.
     * setDirty() sikrer at Minecraft gemmer filen til disk ved næste flush.
     *
     * @param base den nyoprettede base
     */
    public void addBase(SyndicateBase base) {
        bases.add(base);
        setDirty();
    }

    /**
     * Returnerer en umodificerbar visning af alle gemte baser.
     * Bruges ved serveropstart til at genopfylde SyndicateBaseManager.
     *
     * Returnerer en umodificerbar visning (ikke kopi) — ændrings-API'et er
     * addBase() og markDirty() nedenfor.
     *
     * @return umodificerbar liste over alle serialiserede baser
     */
    public List<SyndicateBase> getBases() {
        return Collections.unmodifiableList(bases);
    }

    /**
     * Markerer datasættet som ændret uden at tilføje en ny base.
     * Bruges når en eksisterende base ændrer tilstand (f.eks. wasOpened, raided,
     * peakLootCount) og ændringen skal persisteres til disk.
     *
     * Internt kalder vi bare setDirty() — SavedData-systemet gemmer hele objektet
     * via CODEC ved næste flush, så delvise opdateringer er ikke nødvendige.
     */
    public void markDirty() {
        setDirty();
    }

    // -------------------------------------------------------------------------
    // Intern hjælper
    // -------------------------------------------------------------------------

    /**
     * Pakker to 32-bit heltal til én 64-bit long til O(1) sæt-opslag.
     *
     * cellX lagres i de øverste 32 bits, cellZ i de nederste 32 bits.
     * Masken "cellZ & 0xFFFFFFFFL" sikrer at negative cellZ-værdier behandles korrekt
     * som usignerede 32-bit tal — uden masken ville negative Z-koordinater fortolkes
     * forkert pga. sign-extension ved cast fra int til long.
     *
     * @param cellX gitter-cellens X-koordinat
     * @param cellZ gitter-cellens Z-koordinat
     * @return en unik long der repræsenterer dette (cellX, cellZ)-par
     */
    private static long packCell(int cellX, int cellZ) {
        return (long) cellX << 32 | (cellZ & 0xFFFFFFFFL);
    }
}
