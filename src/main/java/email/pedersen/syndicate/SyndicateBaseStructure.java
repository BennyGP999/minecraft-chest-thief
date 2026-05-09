package email.pedersen.syndicate;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.Optional;

/**
 * Minimal Structure-subklasse der gør syndikats-baser søgbare via /locate structure.
 *
 * Minecraft's /locate structure-kommando søger i chunk-data efter StructureStart-objekter
 * der er knyttet til en given Structure-instans. For at kommandoen kan finde vores baser,
 * skal to ting være på plads:
 *
 *   1. En registreret StructureType<SyndicateBaseStructure> i BuiltInRegistries.STRUCTURE_TYPE
 *      — denne klasse leverer CODEC'en.
 *
 *   2. En Structure-instans i den dynamiske worldgen-registry (Registries.STRUCTURE),
 *      populeret fra data/syndicate/worldgen/structure/syndicate_base.json.
 *
 *   3. Et StructureStart-objekt gemt i candidat-chunken i SyndicateBasePlacer.doPlaceBase()
 *      — dette er det selve /locate læser.
 *
 * Selve placeringen håndteres af SyndicateBasePlacer og ikke af MC's worldgen-pipeline.
 * findGenerationPoint() returnerer derfor altid Optional.empty() — vanilla-generatoren
 * forsøger aldrig at placeere strukturen (og der er heller ingen structure_set JSON).
 *
 * Mønsteret er det samme som vanilla bruger: en StructureStart lagres i chunk-NBT ved
 * generering og genindlæses automatisk ved chunk-load, så /locate virker på tværs af
 * serverrestarter uden at vi selv gemmer noget ekstra.
 */
public class SyndicateBaseStructure extends Structure {

    /**
     * Codec til serialisering af SyndicateBaseStructure fra/til JSON.
     * simpleCodec() er en hjælpemetode i Structure der opretter en codec ud fra
     * StructureSettings (biomes, step, spawnOverrides, terrainAdaptation).
     * De fire felter hentes fra data/syndicate/worldgen/structure/syndicate_base.json.
     */
    public static final MapCodec<SyndicateBaseStructure> CODEC =
            simpleCodec(SyndicateBaseStructure::new);

    /**
     * Constructor — kaldes af CODEC via simpleCodec() under JSON-parsing.
     * Settings indeholder de verdensgen-parametre der er angivet i JSON-filen.
     *
     * @param settings biomes, step, spawnOverrides og terrainAdaptation fra JSON
     */
    public SyndicateBaseStructure(StructureSettings settings) {
        super(settings);
    }

    /**
     * Returnerer altid Optional.empty() — vanilla worldgen-pipelinen skal ALDRIG placere
     * denne struktur selv.
     *
     * Baggrunden: worldgen-pipelinen kalder findGenerationPoint() for ALLE kandidat-chunks
     * i random_spread-gitteret, ikke kun de chunks der faktisk indeholder en base.
     * Hvis vi returnerer en gyldig stub her, opretter pipelinen et "phantom" StructureStart
     * i enhver kandidat-chunk — selv chunks der aldrig fik en rigtig base fordi terrænet
     * var uegnet. /locate finder disse phantoms og rapporterer forkerte koordinater.
     *
     * Syndicats-baser placeres udelukkende af SyndicateBasePlacer.doPlaceBase() ved
     * chunk-load. Efter placering kalder doPlaceBase() StructureCheck.onStructureLoad()
     * direkte for at registrere StructureStart'en i MC's in-memory cache. Ved serverrestart
     * genregistrerer SyndicateMod.reregisterStructureStarts() cachen for alle kendte baser
     * straks ved SERVER_STARTED — så /locate virker med det samme.
     *
     * /locate bruger minecraft:random_spread (spacing=32, separation=12, salt=1254353)
     * til at finde kandidat-chunks. SyndicateBasePlacer.computeCandidate() bruger præcis
     * den samme algoritme — se SYNDICATE_BASE_SALT-konstanten.
     *
     * @param ctx worldgen-kontekst (ubrugt)
     * @return Optional.empty() — ingen worldgen-placering ønsket
     */
    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        return Optional.empty();
    }

    /**
     * Returnerer den registrerede StructureType for SyndicateBaseStructure.
     * Bruges af MC's registry-system til at kende codec'en for denne struktur-type.
     *
     * @return den registrerede type fra SyndicateMod
     */
    @Override
    public StructureType<?> type() {
        return SyndicateMod.SYNDICATE_BASE_STRUCTURE_TYPE;
    }
}
