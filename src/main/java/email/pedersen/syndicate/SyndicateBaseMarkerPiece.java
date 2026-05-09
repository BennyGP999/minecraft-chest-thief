package email.pedersen.syndicate;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.util.RandomSource;

/**
 * Minimals markørpjece der gør SyndicateBaseStructures StructureStart gyldig.
 *
 * Baggrunden: StructureStart.isValid() returnerer false hvis PiecesContainer er tom.
 * /locate bruger StructureCheck.checkStart(), som kalder isValid() — en tom container
 * resulterer i START_NOT_PRESENT og /locate finder ingenting.
 *
 * Denne klasse tilføjer præcis ét styk til containeren, så isValid() returnerer true.
 * Den faktiske base stampes af SyndicateBasePlacer ved chunk-load — postProcess() gør
 * bevidst ingenting, for at undgå konflikter med SyndicateBasePlacer.
 *
 * TYPE registreres i SyndicateMod og refereres fra SyndicateBaseStructure.
 */
public class SyndicateBaseMarkerPiece extends StructurePiece {

    /**
     * Den registrerede pjece-type — sættes af SyndicateMod.onInitialize() via
     * Registry.register(BuiltInRegistries.STRUCTURE_PIECE, ...).
     * Feltet er null indtil registreringen er sket, hvilket sker før verdenen loades.
     */
    public static StructurePieceType TYPE;

    /**
     * Brugt ved oprettelse: i SyndicateBaseStructure.findGenerationPoint() og doPlaceBase().
     *
     * @param box afgrænsningsboks for pjecen — her et 32×128×32 område centreret på chunk-midten
     */
    public SyndicateBaseMarkerPiece(BoundingBox box) {
        super(TYPE, 0, box);
    }

    /**
     * Brugt ved indlæsning fra NBT: Minecraft kalder load() via TYPE-referencen
     * når chunk-NBT'en indlæses fra disk.
     *
     * @param ctx serialiserings-kontekst (ubrugt — vi har ingen registry-afhængige felter)
     * @param nbt komprimeret tag med pjecens serialiserede data (bounding box er gemt af super)
     */
    public SyndicateBaseMarkerPiece(StructurePieceSerializationContext ctx, CompoundTag nbt) {
        super(TYPE, nbt);
    }

    /**
     * Ingen ekstra data at gemme ud over hvad super (bounding box) allerede gemmer.
     */
    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext ctx, CompoundTag nbt) {
        // ingen ekstra felter
    }

    /**
     * Placer ingen blokke — SyndicateBasePlacer stamper basen via StructureTemplate.
     * Worldgen-pipelinen kalder postProcess() under chunk-generering; vi ignorerer kaldet.
     */
    @Override
    public void postProcess(WorldGenLevel level, StructureManager manager, ChunkGenerator generator,
                            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pos) {
        // ingenting at gøre her
    }
}
