package email.pedersen.syndicate;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Repræsenterer én aktiv syndikats-base i verdenen.
 *
 * En base er den underjordiske borg hvortil tyvene afleverer det stjålne loot.
 * Spilleren kan finde basen via et Syndikatskort og raide den for at få looten tilbage.
 *
 * Denne klasse er en ren dataklasse uden logik — den rummer kun tilstand.
 * Al forretningslogik (find nærmeste base, tilføj loot, marker som raidet) ligger i
 * SyndicateBaseManager. Al persistens (gem/load fra disk) sker via SyndicateSavedData.
 *
 * En instans oprettes af SyndicateBasePlacer når en base stamps i verden (S07),
 * og slettes/erstattes når basen er raidet og en ny base spawner i regionen (S10).
 */
public class SyndicateBase {

    /**
     * Overfladeindgangen til basen — den blok man ser fra terrænniveau.
     * Typisk en stige eller trappesti ned til den underjordiske del.
     * Bruges som referencepunkt til "find nærmeste base" og til kortet (S11).
     */
    private final BlockPos position;

    /**
     * Det fulde tredimensionelle rektangel basen optager under terrænet.
     * Bruges til at tjekke om en kiste tilhører denne base (S10: raid-detektion),
     * og til at holde vagterne indendørs (S09: de kan ikke navigere ud via stigen).
     * Koordinaterne er absolutte blok-koordinater i verdenen.
     */
    private final AABB bounds;

    /**
     * Den dimension (overworld, nether, end osv.) basen befinder sig i.
     * Tyvene opererer kun i overworld, men feltet er her for at fremtidssikre koden
     * uden at ændre datamodellen — en evt. nether-base kan tilføjes uden refaktorering.
     */
    private final ResourceKey<Level> dimension;

    /**
     * Absolutte blok-positioner på alle ChestBlockEntity-blokke fundet efter stamping.
     * Opdages automatisk ved at scanne basens AABB (S07) — ingen hardkodede offsets.
     * En dobbelt-kiste består af to enkelt-kiste-blokke, så 6 dobbelt-kister = 12 positioner.
     * Kapaciteten er dynamisk: ændres antallet af kister i .nbt-filen, registreres de alle.
     */
    private final List<BlockPos> chestPositions;

    /**
     * Absolutte blok-positioner på de spawn-markører (emerald_block) der fandtes ved stamping.
     * Markørerne erstattes med luft umiddelbart efter discovery (S07), så listen er blot
     * en huskeliste over gyldige spawn-steder til vagter (S09).
     * Antal og fordeling bestemmes fuldstændig af .nbt-designeren — ingen kode-konstanter.
     */
    private final List<BlockPos> spawnPositions;

    /**
     * Det højeste samlede antal items kisterne i basen nogensinde har indeholdt.
     * Sættes ved basens oprettelse til starter-loot-antallet, og opdateres opad
     * efterhånden som tyvene afleverer mere loot.
     * Bruges som referencepunkt for raid-tærskelberegningen:
     *   raidet = wasOpened && currentLootCount < peakLootCount * raidThreshold
     * En base der aldrig har haft noget loot vil ikke trigge raid-logikken.
     */
    private int peakLootCount;

    /**
     * Om en spiller har åbnet mindst én kiste i basen.
     * Sættes til true i ChestChangedMixin (S10) første gang en kiste ændres.
     * Kræves at være true (sammen med lavt loot-antal) for at raid trigges —
     * dette forhindrer at basen markeres som raidet blot fordi tyvene endnu
     * ikke har afleveret noget.
     */
    private boolean wasOpened;

    /**
     * Om basen er markeret som raidet og venter på at blive erstattet.
     * Sættes til true af SyndicateBaseManager.markRaided() (S10).
     * Når denne er true, betragtes cellen som ledig og en ny base spawner
     * ved næste chunk-load-event i regionen.
     * Den fysiske struktur forbliver i verdenen som en tom ruin.
     */
    private boolean raided;

    /**
     * UUIDs på de vagter der i øjeblikket aktivt bevogtger basen.
     * Bruges i S09 til at tælle aktive vagter (loadede UUIDs tæller, uloadede tæller ikke)
     * og til at despawne vagter når basen raides (S10).
     * Listen opdateres: tilføj ved spawn, fjern ved død (ServerLivingEntityEvents.AFTER_DEATH).
     */
    private final List<UUID> guardUUIDs;

    /**
     * Opretter en ny SyndicateBase med alle felter.
     * Kaldet udelukkende fra SyndicateBasePlacer (S07) og SyndicateSavedData.load() (S07).
     *
     * @param position       overfladeindgangens position
     * @param bounds         basens fulde AABB under terrænet
     * @param dimension      dimensionen basen befinder sig i
     * @param chestPositions absolutte positioner på alle kiste-blokke
     * @param spawnPositions absolutte positioner på alle spawn-markører
     * @param peakLootCount  starter-loot-antal (opdateres efterhånden af SyndicateBaseManager)
     */
    public SyndicateBase(BlockPos position,
                         AABB bounds,
                         ResourceKey<Level> dimension,
                         List<BlockPos> chestPositions,
                         List<BlockPos> spawnPositions,
                         int peakLootCount) {
        this.position = position.immutable();
        this.bounds = bounds;
        this.dimension = dimension;
        // Lav defensive kopier så kalderen ikke kan ændre listerne bagefter uden at vi ved det
        this.chestPositions = new ArrayList<>(chestPositions);
        this.spawnPositions = new ArrayList<>(spawnPositions);
        this.peakLootCount = peakLootCount;
        this.wasOpened = false;
        this.raided = false;
        this.guardUUIDs = new ArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /** Basens overfladeindgang — bruges som referencepunkt til kortvisning og findNearest(). */
    public BlockPos getPosition() { return position; }

    /** Det fulde AABB basen optager — bruges til raid-detektion og vagt-containment. */
    public AABB getBounds() { return bounds; }

    /** Dimensionen basen befinder sig i — bruges til at filtrere baser per dimension. */
    public ResourceKey<Level> getDimension() { return dimension; }

    /** Mutable liste over kiste-blokkes positioner — tilgås direkte for at tilføje/fjerne. */
    public List<BlockPos> getChestPositions() { return chestPositions; }

    /** Mutable liste over spawn-markørers positioner — tilgås direkte af S09. */
    public List<BlockPos> getSpawnPositions() { return spawnPositions; }

    /** Det hidtil højeste loot-antal — bruges i raid-tærskelberegningen. */
    public int getPeakLootCount() { return peakLootCount; }

    /** Opdaterer peakLootCount hvis newCount er højere end det nuværende højdepunkt. */
    public void updatePeakLootCount(int newCount) {
        if (newCount > peakLootCount) {
            peakLootCount = newCount;
        }
    }

    /** Om en spiller har åbnet mindst én kiste — del af raid-betingelsen. */
    public boolean isWasOpened() { return wasOpened; }

    /** Markerer at en spiller har åbnet en kiste i basen. Kan ikke fortrydes. */
    public void setWasOpened(boolean wasOpened) { this.wasOpened = wasOpened; }

    /** Om basen er raidet og venter på at blive erstattet med en ny base. */
    public boolean isRaided() { return raided; }

    /** Markerer basen som raidet — trigges af SyndicateBaseManager.markRaided(). */
    public void setRaided(boolean raided) { this.raided = raided; }

    /** Mutable liste over aktive vagters UUIDs — tilgås direkte af S09 og S10. */
    public List<UUID> getGuardUUIDs() { return guardUUIDs; }
}
