package email.pedersen.syndicate;

import email.pedersen.chestthief.config.ChestThiefConfig;
import email.pedersen.syndicate.config.SyndicateConfig;
import email.pedersen.syndicate.mixin.StructureManagerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Beregner og validerer placeringen af en syndikats-base i verdenen.
 *
 * Klassen er ansvarlig for to ting (begge statiske hjælpemetoder, ingen instans-tilstand):
 *
 *   S05 — Chunk-baseret placeringslogik:
 *     Verdenen opdeles i et gitter af "region-celler" à spacing×spacing chunks.
 *     Inden for hver celle beregnes én deterministisk kandidat-chunk via et seeded RNG.
 *     Determinismen sikrer at samme celle altid giver samme kandidat uanset hvornår
 *     eller på hvilken server den evalueres — vigtig for reproducerbarhed.
 *
 *   S06 — Valideringscheck:
 *     Inden en .nbt-struktur stamps, køres en serie af checks i fail-fast rækkefølge
 *     (billigste først). Afvisninger kategoriseres som "bløde" (midlertidigt) eller
 *     "hårde" (permanent caches i SyndicateSavedData). Se validate() for detaljer.
 *
 * Trigger: Fabric's ServerChunkEvents.CHUNK_LOAD (registreres i TASK-S07).
 * Denne klasse indeholder ingen event-lyttere — kun ren beregning.
 *
 * Performance:
 *   Under normale omstændigheder returnerer 99%+ af kald fra validate() i O(1)
 *   via membership-guard og hard-rejection-cache. De dyrere blok-scanninger
 *   køres kun for nye kandidat-chunks der ikke allerede er evaluerede.
 *   Se syndicate-tasks.md TASK-S06 for detaljeret performance-analyse.
 */
public class SyndicateBasePlacer {

    /**
     * Whitelist af blokke der anses for "naturlige" — bruges af isNaturalBlock().
     *
     * Listen dækker kun terræn- og undergrunds-blokke der ikke er dækket af block-tags.
     * Vegetation, træer og blomster håndteres via tags i isNaturalBlock() — det er mere
     * robust end at opremse hver enkelt blok, da nye biom-blokke (leaf_litter, torchflower
     * m.fl.) automatisk dækkes af de relevante tags uden at listen skal opdateres.
     */
    private static final Set<Block> NATURAL_BLOCKS = Set.of(
            // ── Sten og jord ──────────────────────────────────────────────────
            Blocks.STONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE,
            Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
            Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
            Blocks.GRAVEL, Blocks.SAND, Blocks.SANDSTONE,
            Blocks.RED_SAND, Blocks.RED_SANDSTONE,
            Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
            Blocks.TUFF, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
            Blocks.CLAY, Blocks.BEDROCK, Blocks.OBSIDIAN, Blocks.MAGMA_BLOCK,
            // ── Malme ─────────────────────────────────────────────────────────
            Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE,
            Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE,
            Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.DEEPSLATE_GOLD_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            // ── Sne og is ─────────────────────────────────────────────────────
            Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
            // ── Terracotta (badlands) ─────────────────────────────────────────
            Blocks.TERRACOTTA,
            Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
            Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA,
            Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
            Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.BLUE_TERRACOTTA,
            Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA,
            Blocks.BLACK_TERRACOTTA,
            // ── Mos, mudder og mangrove ───────────────────────────────────────
            Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS,
            Blocks.MANGROVE_ROOTS,
            // ── Amethyst geode ────────────────────────────────────────────────
            Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST,
            Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD,
            Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER,
            // ── Cave-blokke ───────────────────────────────────────────────────
            Blocks.POINTED_DRIPSTONE, Blocks.GLOW_LICHEN, Blocks.SPORE_BLOSSOM,
            Blocks.HANGING_ROOTS, Blocks.SCULK, Blocks.SCULK_VEIN,
            Blocks.SCULK_CATALYST, Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER,
            // ── Svampe (plant og storsvamp-blokke) ───────────────────────────
            // Svampe-planter er IKKE i BlockTags.REPLACEABLE i denne MC-version
            Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM,
            Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM,
            // ── Vegetation der ikke er dækket af REPLACEABLE-tagget ───────────
            Blocks.CACTUS,           // solid, ikke replaceable
            Blocks.VINE,             // klatreplante på træer
            Blocks.BAMBOO, Blocks.BAMBOO_SAPLING,
            Blocks.SUGAR_CANE,
            Blocks.LILY_PAD,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT,
            Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
            Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT,
            // ── Pale Garden (1.21.4) ──────────────────────────────────────────
            // Pale Oak er dækket af LOGS/LEAVES-tags; disse er ikke
            Blocks.PALE_MOSS_BLOCK, Blocks.PALE_HANGING_MOSS,
            // ── Luft og vand ──────────────────────────────────────────────────
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.WATER, Blocks.LAVA
    );

    /**
     * Markørblok der i .nbt-filen angiver skaktens indgang til den underjordiske base.
     *
     * Placering i .nbt'en: én blok, lige over det øverste lag af skakten (det vil sige
     * i det allertopperste lag af strukturfilen). Koden bruger markøren til to ting:
     *   1. Forankre strukturen korrekt: markørens verden-Y sættes til terrænets heightmap-Y,
     *      og strukturens placering beregnes baglæns derfra.
     *   2. Finde skaktens XZ i verden: bruges til flat 3x3-check og til at erstatte
     *      markøren med luft efter stamping (S07).
     *
     * Dead tube coral block er valgt fordi den:
     *   - Forekommer aldrig naturligt under terræn (ingen false positives i NATURAL_BLOCKS-check)
     *   - Er let at spotte visuelt i en struktur-editor
     *   - Ikke bruges af andre markeringskonventioner i projektet
     *     (emerald_block = vagter, chest = loot — intet overlap)
     */
    public static final Block SHAFT_MARKER = Blocks.DEAD_TUBE_CORAL_BLOCK;

    /**
     * Salt-konstant der bruges i seed-beregningen for computeCandidate().
     *
     * Værdien matcher "salt"-feltet i data/syndicate/worldgen/structure_set/syndicate_base.json,
     * som bruger "type": "minecraft:random_spread". De to skal altid være ens, fordi
     * /locate bruger random_spread's getPotentialStructureChunk() og computeCandidate()
     * skal give præcis det samme resultat for at /locate finder de rigtige chunks.
     *
     * Ændr ikke denne konstant uden også at opdatere structure_set-JSON'en — og omvendt.
     */
    public static final int SYNDICATE_BASE_SALT = 1254353;

    /**
     * Cachet strukturskabelon — indlæses første gang fra JAR-ressourcen, derefter
     * genbruges direkte fra hukommelsen. StructureTemplate er immutable efter load(),
     * så deling på tværs af valideringsforsøg er trådsikkert.
     * volatile sikrer at skrivningen fra loadTemplate() er synlig på alle tråde.
     */
    @Nullable
    private static volatile StructureTemplate cachedTemplate = null;

    // Utility-klasse — ingen instanser
    private SyndicateBasePlacer() {}

    // =========================================================================
    // S05 — Chunk-baseret placeringslogik
    // =========================================================================

    /**
     * Beregner gitter-cellens koordinater for en given chunk.
     *
     * Verdenen er opdelt i et gitter af kvadratiske celler à spacingChunks×spacingChunks chunks.
     * floorDiv bruges (ikke heltalsdivision "/") for at håndtere negative chunk-koordinater korrekt:
     *   chunk -1 med spacing 64 → cell -1 (ikke 0), fordi floorDiv(-1, 64) = -1.
     * Standard "/" ville give -1/64 = 0, som er forkert for negative koordinater.
     *
     * @param chunk        den chunk hvis celle skal beregnes
     * @param spacingChunks cellestørrelse i chunks (fra SyndicateConfig)
     * @return {cellX, cellZ} — cellens koordinater i gitteret
     */
    public static int[] computeCell(ChunkPos chunk, int spacingChunks) {
        // chunk.x() og chunk.z() er record-accessors — ChunkPos er et Java record
        int cellX = Math.floorDiv(chunk.x(), spacingChunks);
        int cellZ = Math.floorDiv(chunk.z(), spacingChunks);
        return new int[]{cellX, cellZ};
    }

    /**
     * Beregner en deterministisk kandidat-chunk inden for en gitter-celle.
     *
     * Algoritme:
     * 1. Kombiner verdensfrøet med cellens koordinater til et unikt seed:
     *    Multiplikationskonstanterne er store primtal der giver god spredning
     *    (samme teknik bruger Minecrafts eget chunk-seeding for strukturer).
     * 2. Opret et seeded RNG — samme seed → samme resultat, altid.
     * 3. Beregn et tilfældigt offset inden for cellen, men mindst separationChunks
     *    chunks fra enhver cellegrænse, så baser fra naboceller ikke spawner
     *    for tæt på hinanden på tværs af grænsen.
     *
     * Eksempel med spacing=64, separation=24:
     *   Offset-vindue = [24, 39] (64 - 24 - 1 = 39), dvs. 16 mulige positions.
     *   Kandidaten er garanteret mindst 24 chunks fra alle fire cellegrænser.
     *
     * @param cellX            cellens X-koordinat i gitteret
     * @param cellZ            cellens Z-koordinat i gitteret
     * @param worldSeed        verdensfrøet (fra ServerLevel.getSeed())
     * @param spacingChunks    cellestørrelse i chunks
     * @param separationChunks minimumafstand til cellegrænsen i chunks
     * @return den deterministiske kandidat-chunk for denne celle
     */
    public static ChunkPos computeCandidate(int cellX, int cellZ, long worldSeed,
                                             int spacingChunks, int separationChunks) {
        // Algoritmen matcher præcis minecraft:random_spread's getPotentialStructureChunk().
        //
        // Årsagen til at de to SKAL matche:
        // /locate bruger RandomSpreadStructurePlacement.getPotentialStructureChunk() til at
        // bestemme kandidat-chunks i en spiral. SyndicateBasePlacer.tryPlaceBase() bruger
        // computeCandidate() til at bestemme HVILKEN chunk der er kandidat ved chunk-load.
        // Hvis de to metoder giver forskellige resultater, leder /locate i den forkerte chunk.
        //
        // WorldgenRandom(LegacyRandomSource(0)) og setLargeFeatureWithSalt() er præcis den
        // kombination som random_spread bruger — se RandomSpreadStructurePlacement.java.
        //
        // setLargeFeatureWithSalt(worldSeed, cellX, cellZ, salt) beregner:
        //   seed = cellX*341873128712 + cellZ*132897987541 + worldSeed + salt
        // og kalder setSeed(seed) på LegacyRandomSource.
        //
        // Offset-vinduet er [0, spacing-separation-1] — random_spread bruger nextInt(window)
        // som giver et offset i dette interval (modsat vores tidligere algoritme der brugte
        // [separation, spacing-separation-1] for at håndhæve minimumsafstand til cellegrænsen).

        int window = spacingChunks - separationChunks;

        // Fallback hvis spacing er for lav eller separation for høj — brug centrum.
        if (window <= 0) {
            return new ChunkPos(
                    cellX * spacingChunks + spacingChunks / 2,
                    cellZ * spacingChunks + spacingChunks / 2
            );
        }

        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureWithSalt(worldSeed, cellX, cellZ, SYNDICATE_BASE_SALT);

        int offsetX = rng.nextInt(window);
        int offsetZ = rng.nextInt(window);

        return new ChunkPos(
                cellX * spacingChunks + offsetX,
                cellZ * spacingChunks + offsetZ
        );
    }

    // =========================================================================
    // S06 — Valideringscheck
    // =========================================================================

    /**
     * Validerer om en kandidat-chunk er egnet til at huse en syndikats-base.
     *
     * Køres KUN på server-main-thread, og KUN når chunken alligevel er loadet
     * (via ServerChunkEvents.CHUNK_LOAD — se TASK-S07). Force-loading sker aldrig.
     *
     * Returnerer strukturens placerings-oprindelse (BlockPos) hvis alle checks er bestået,
     * ellers null. Den returnerede BlockPos bruges direkte som origin til
     * StructureTemplate.placeInWorld() i S07.
     *
     * Forankring via SHAFT_MARKER:
     *   Koden finder dead_tube_coral_block i template'en, beregner dens roterede lokale pos,
     *   og sætter strukturen så markøren lander præcis på terrænets heightmap-Y.
     *   Formlen: placeOrigin = surface.subtract(rotatedMarkerLocalPos)
     *
     * Checkene kører i fail-fast rækkefølge — billigst først:
     *
     *   1. Membership-guard — O(1): eksisterer der allerede en base i cellen?
     *   2. Hard-rejection-cache — O(1): er cellen permanent forkastet?
     *   3. Hav-check — O(1): er overfladen under vand? → hard reject (hav er permanent)
     *   Chunk-guard — O(footprint-chunks): er alle chunks footprint+margin dækker loadede?
     *                 KRITISK: skal køre inden ethvert kald der kan ramme nabochunks.
     *                 getHeight()/getBlockState() blokerer main-thread på uloadede chunks.
     *   4. Ikke-naturlige blokke — O(volumen): player-blokke → hard reject
     *
     * Hard vs. soft rejection:
     *   Hard: årsagen er permanent (player-blokke, vanilla-strukturer).
     *         Gemmes i SyndicateSavedData.hardRejectedCells.
     *   Soft: årsagen er midlertidig (nabochunk ikke loadet, hule kan lukke).
     *         Ingen caching — prøv igen ved næste chunk-load event.
     *
     * @param level     serververdenen — bruges til blokopslag og structurmanager
     * @param cellX     cellens X-koordinat (beregnet af computeCell)
     * @param cellZ     cellens Z-koordinat (beregnet af computeCell)
     * @param candidate kandidat-chunken der valideres
     * @param template  strukturskabelonen — bruges til at beregne AABB-størrelse
     * @param rotation  den valgte rotation for strukturen
     * @return placeOrigin — den BlockPos der bruges som origin til placeInWorld(), eller null ved afvisning
     */
    @Nullable
    public static BlockPos validate(ServerLevel level, int cellX, int cellZ,
                                     ChunkPos candidate, StructureTemplate template,
                                     Rotation rotation, SyndicateSavedData savedData) {
        SyndicateConfig config = SyndicateConfig.getInstance();
        ResourceKey<Level> dimension = level.dimension();

        // ── Check 1: Membership-guard ─────────────────────────────────────────
        if (SyndicateBaseManager.hasBaseInCell(cellX, cellZ, dimension, config.getBaseSpacingChunks())) {
            SyndicateMod.LOGGER.debug("VALIDATE CHECK1 AFVIST — celle ({},{}) allerede aktiv", cellX, cellZ);
            return null;
        }

        // ── Check 2: Hard-rejection-cache ─────────────────────────────────────
        if (savedData.isHardRejected(cellX, cellZ, dimension)) {
            SyndicateMod.LOGGER.debug("VALIDATE CHECK2 AFVIST — celle ({},{}) permanent blacklist", cellX, cellZ);
            return null;
        }

        // ── Check 3: Hav-check ────────────────────────────────────────────────
        // findGroundSurface() scanner nedad forbi trætoppe og vegetation
        // og returnerer luftblokken over det første solide terræn-lag.
        BlockPos candidateBlock = candidate.getMiddleBlockPosition(64);
        BlockPos surface = findGroundSurface(level, candidateBlock);

        BlockPos surfaceBelow = surface.below();
        if (level.getFluidState(surfaceBelow).is(FluidTags.WATER)
                || level.getFluidState(surface).is(FluidTags.WATER)) {
            savedData.addHardRejected(cellX, cellZ, dimension);
            SyndicateMod.LOGGER.debug(
                    "VALIDATE CHECK3 AFVIST (permanent) — overflade under vand ved Y={}", surface.getY());
            return null;
        }

        // ── Find SHAFT_MARKER og beregn placeOrigin ───────────────────────────
        BlockPos rotatedMarkerLocalPos = findShaftMarker(template, rotation);
        if (rotatedMarkerLocalPos == null) {
            SyndicateMod.LOGGER.error("syndicate_base.nbt mangler shaft marker (dead_tube_coral_block) — base-placering deaktiveret");
            return null;
        }
        BlockPos placeOrigin = surface.subtract(rotatedMarkerLocalPos);

        // ── Beregn AABB ───────────────────────────────────────────────────────
        AABB footprint = computeFootprintAABB(placeOrigin, template, rotation);

        // ── Chunk-guard ───────────────────────────────────────────────────────
        {
            int minCX = ((int) footprint.minX) >> 4;
            int maxCX = ((int) footprint.maxX) >> 4;
            int minCZ = ((int) footprint.minZ) >> 4;
            int maxCZ = ((int) footprint.maxZ) >> 4;
            for (int cx = minCX - 1; cx <= maxCX + 1; cx++) {
                for (int cz = minCZ - 1; cz <= maxCZ + 1; cz++) {
                    if (!level.hasChunk(cx, cz)) {
                        SyndicateMod.LOGGER.debug(
                                "VALIDATE CHUNK-GUARD AFVIST — nabochunk [{},{}] ikke loaded", cx, cz);
                        return null;
                    }
                }
            }
        }

        // ── Check 4: Ikke-naturlige blokke ────────────────────────────────────
        int minX = (int) footprint.minX;
        int maxX = (int) footprint.maxX;
        int minY = (int) footprint.minY;
        int maxY = (int) footprint.maxY;
        int minZ = (int) footprint.minZ;
        int maxZ = (int) footprint.maxZ;
        int volume = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        int blocksScanned = 0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        // Scanrækkefølge: Y fra max (terrænoverfladen) ned mod min (dybest under jord).
        // Spillerbyggeri og vanilla-strukturer (landsbyer, mineshafts) forekommer oftest
        // på eller nær overfladen — top-down scanning finder disse efter ~550 blokke (ét Y-lag)
        // i stedet for sidst efter ~16.000 blokke med den tidligere bottom-up rækkefølge.
        // For naturligt terræn uden fremmedblokke scannes alle blokke uanset retning.
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocksScanned++;
                    BlockState state = level.getBlockState(mutable.set(x, y, z));
                    if (!isNaturalBlock(state)) {
                        savedData.addHardRejected(cellX, cellZ, dimension);
                        SyndicateMod.LOGGER.debug(
                                "VALIDATE CHECK4 AFVIST (permanent) — ikke-naturlig blok {} ved [{},{},{}] efter {}/{} blokke",
                                state.getBlock(), x, y, z, blocksScanned, volume);
                        return null;
                    }
                    if (!state.getFluidState().isEmpty()) {
                        savedData.addHardRejected(cellX, cellZ, dimension);
                        SyndicateMod.LOGGER.debug(
                                "VALIDATE CHECK4 AFVIST (permanent) — fluid {} ved [{},{},{}] efter {}/{} blokke",
                                state.getFluidState().getType(), x, y, z, blocksScanned, volume);
                        return null;
                    }
                }
            }
        }

        return placeOrigin;
    }

    // =========================================================================
    // Private hjælpemetoder
    // =========================================================================

    /**
     * Finder SHAFT_MARKER-blokken i template'en og returnerer dens roterede lokale position.
     *
     * Bruger filterBlocks() med en StructurePlaceSettings der indeholder den valgte rotation.
     * filterBlocks() returnerer positionerne EFTER rotation er påført — dvs. den returnerede
     * position er relativ til strukturens oprindelse (BlockPos.ZERO) og svarer præcis til
     * det offset der bruges i placeInWorld(). Formlen:
     *
     *   worldPos(markør) = placeOrigin.offset(rotatedMarkerLocalPos)
     *   → placeOrigin = surface.subtract(rotatedMarkerLocalPos)
     *
     * Rotation ændrer markørens XZ-position (og evt. spejler den), men ændrer ikke Y —
     * markøren forbliver i det øverste lag af strukturen uanset rotation.
     *
     * @param template  strukturskabelonen der scannes
     * @param rotation  den valgte rotation
     * @return markørens roterede lokale BlockPos, eller null hvis markøren ikke findes
     */
    @Nullable
    private static BlockPos findShaftMarker(StructureTemplate template, Rotation rotation) {
        // StructurePlaceSettings med NONE som mirror (ingen spejling) og den valgte rotation.
        // BlockPos.ZERO som pivot sikrer at rotationen sker om strukturens nord-vest-hjørne,
        // samme pivot som placeInWorld() bruger som standard.
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        List<StructureTemplate.StructureBlockInfo> markers =
                template.filterBlocks(BlockPos.ZERO, settings, SHAFT_MARKER);

        if (markers.isEmpty()) return null;
        if (markers.size() > 1) {
            SyndicateMod.LOGGER.warn(
                    "syndicate_base2.nbt contains {} shaft markers — expected 1, using first",
                    markers.size());
        }
        return markers.get(0).pos();
    }

    /**
     * Beregner det fulde AABB strukturen vil optage efter stamping, korrekt for alle rotationer.
     *
     * MC's CW_90-rotation bruger new_x = -original_z, new_z = original_x, hvilket betyder at
     * strukturen kan strække sig i NEGATIV X/Z-retning fra placeOrigin afhængigt af rotation.
     * En naiv "placeOrigin + size" beregning giver et forkert AABB og scanner det forkerte område.
     *
     * For hver rotation beregnes de faktiske min/max offsets fra placeOrigin:
     *   NONE:           X [0, sx],    Z [0, sz]
     *   CLOCKWISE_90:   X [-sz, 0],   Z [0, sx]   (CW: new_x = -old_z, new_z = old_x)
     *   CLOCKWISE_180:  X [-sx, 0],   Z [-sz, 0]
     *   COUNTERCLOCKWISE_90: X [0, sz], Z [-sx, 0]
     *
     * @param placeOrigin  strukturens placerings-oprindelse (output fra validate())
     * @param template     strukturskabelonen
     * @param rotation     den valgte rotation
     * @return det absolutte AABB strukturen vil optage i verdenen
     */
    private static AABB computeFootprintAABB(BlockPos placeOrigin, StructureTemplate template,
                                              Rotation rotation) {
        // Brug uroteret størrelse — vi beregner retningen selv ud fra rotationsformlen
        net.minecraft.core.Vec3i size = template.getSize();
        int sx = size.getX();
        int sy = size.getY();
        int sz = size.getZ();

        int offMinX, offMaxX, offMinZ, offMaxZ;
        switch (rotation) {
            case CLOCKWISE_90 ->        { offMinX = -sz; offMaxX = 0;  offMinZ = 0;   offMaxZ = sx; }
            case CLOCKWISE_180 ->       { offMinX = -sx; offMaxX = 0;  offMinZ = -sz; offMaxZ = 0; }
            case COUNTERCLOCKWISE_90 -> { offMinX = 0;   offMaxX = sz; offMinZ = -sx; offMaxZ = 0; }
            default ->                  { offMinX = 0;   offMaxX = sx; offMinZ = 0;   offMaxZ = sz; }
        }

        return new AABB(
                placeOrigin.getX() + offMinX, placeOrigin.getY(),      placeOrigin.getZ() + offMinZ,
                placeOrigin.getX() + offMaxX, placeOrigin.getY() + sy, placeOrigin.getZ() + offMaxZ
        );
    }

    /**
     * Tjekker at alle fem yder-flader (ekskl. top) af det underjordiske AABB
     * grænser op til solide blokke.
     *
     * En "solid" blok er isSolid() == true OG ikke flydende vand.
     * Cave_air og regulær air tæller som ikke-solid.
     *
     * De fem flader: NORTH (z=minZ-1), SOUTH (z=maxZ+1), WEST (x=minX-1),
     * EAST (x=maxX+1), BOTTOM (y=minY-1).
     * TOP (y=maxY+1) springes over — den er altid luft på terrænoverfladen.
     *
     * @return false hvis én eller flere randblokke er ikke-solide (blød afvisning)
     */
    private static boolean isPerimeterSolid(ServerLevel level,
                                             int minX, int maxX, int minY, int maxY,
                                             int minZ, int maxZ) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        // NORTH-flade: z = minZ-1
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                mutable.set(x, y, minZ - 1);
                if (!isSolid(level, mutable)) return false;
            }
        }

        // SOUTH-flade: z = maxZ+1
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                mutable.set(x, y, maxZ + 1);
                if (!isSolid(level, mutable)) return false;
            }
        }

        // WEST-flade: x = minX-1
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                mutable.set(minX - 1, y, z);
                if (!isSolid(level, mutable)) return false;
            }
        }

        // EAST-flade: x = maxX+1
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                mutable.set(maxX + 1, y, z);
                if (!isSolid(level, mutable)) return false;
            }
        }

        // BOTTOM-flade: y = minY-1
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                mutable.set(x, minY - 1, z);
                if (!isSolid(level, mutable)) return false;
            }
        }

        return true;
    }

    /**
     * Returnerer om en blok er solid og ikke flydende vand.
     *
     * Flydende vand rapporteres som "solid" af visse metoder i Minecraft, men
     * en undervandsbase er uønsket — vi ekskluderer det eksplicit.
     *
     * @param level   verdenen
     * @param pos     blok-positionen der tjekkes
     * @return true hvis blokken er solid og ikke vandfyldt
     */
    private static boolean isSolid(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.isSolid() && !state.getFluidState().is(FluidTags.WATER);
    }

    /**
     * Finder den faktiske terrænoverflade under træer og vegetation.
     *
     * WORLD_SURFACE returnerer toppen af det højeste ikke-luft-objekt, som kan være et
     * løvblad eller en træstamme. Vi scanner nedad gennem blade, stammer, planter og
     * andre "naturlige over-jords" blokke, og returnerer luftblokken over det første
     * solide terræn-lag (jord, sten, sand osv.).
     *
     * @param level   serverniveauet
     * @param column  en vilkårlig position i den kolonne der søges (X og Z bruges, Y ignoreres)
     * @return luftblokken direkte over det øverste solide terræn-lag
     */
    private static BlockPos findGroundSurface(ServerLevel level, BlockPos column) {
        // Start ved det højeste ikke-luft-objekt og scan nedad
        BlockPos pos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, column).below();
        while (pos.getY() > level.getMinY()) {
            BlockState state = level.getBlockState(pos);
            // Spring over luft og vegetationsblokke der kun forekommer over terræn
            if (!state.isAir()
                    && !state.is(BlockTags.LEAVES)
                    && !state.is(BlockTags.LOGS)
                    && !state.is(BlockTags.SAPLINGS)
                    && !state.is(BlockTags.FLOWERS)
                    && !state.is(BlockTags.REPLACEABLE)) {
                // Første solide terræn-blok fundet — returnér luftblokken over den
                return pos.above();
            }
            pos = pos.below();
        }
        // Fallback: brug vanilla heightmap hvis scanningen løber tør
        return level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, column);
    }

    /**
     * Returnerer om en blok anses for "naturlig" — dvs. kan forekomme i uberørt terræn.
     *
     * To-trins check:
     *   1. Fast opslagscheck mod NATURAL_BLOCKS-sættet for terræn- og undergrunds-blokke.
     *   2. Tag-baseret check for vegetation og træer — dækker automatisk alle nuværende
     *      og fremtidige blokke med de relevante tags uden at listen skal opdateres.
     *      BlockTags.REPLACEABLE: short_grass, tall_grass, fern, blomster, leaf_litter osv.
     *      BlockTags.LEAVES:      alle løvblokke (oak, birch, pale_oak osv.)
     *      BlockTags.LOGS:        alle stammblokke inkl. stripped-varianter
     *      BlockTags.SAPLINGS:    alle frøplanter
     *      BlockTags.FLOWERS:     alle blomster (subset af REPLACEABLE, men eksplicit for klarhed)
     *      BlockTags.SNOW:        snelags-blokken (snow) og snow_block
     *      BlockTags.ICE:         ice og frosted_ice
     *
     * @param state  block-tilstanden der tjekkes
     * @return true hvis blokken er naturlig (ikke player-placeret)
     */
    private static boolean isNaturalBlock(BlockState state) {
        if (NATURAL_BLOCKS.contains(state.getBlock())) return true;
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.REPLACEABLE)
                || state.is(BlockTags.SNOW)
                || state.is(BlockTags.ICE);
    }

    // =========================================================================
    // S07 — .nbt-placering
    // =========================================================================

    /**
     * Indlæser syndicate_base.nbt fra JAR'ens data-ressourcer som en StructureTemplate.
     *
     * MC 26.1.1 bruger SNBT-format (.snbt) til resource-pack-strukturer og
     * StructureTemplateManager.get() leder kun efter .snbt-filer. Da vores fil er
     * binær NBT (eksporteret via /structure save), bypasser vi manageren og læser
     * filen direkte via ResourceManager + NbtIo.readCompressed().
     *
     * Ressource-stien i JAR'en: data/syndicate/structures/syndicate_base2.nbt
     * Identifier-form:           syndicate:structures/syndicate_base2.nbt
     *
     * @param level serverniveauet — bruges til at hente ResourceManager og StructureTemplateManager
     * @return den indlæste StructureTemplate, eller null hvis filen ikke kan findes/parses
     */
    @Nullable
    private static StructureTemplate loadTemplate(ServerLevel level) {
        if (cachedTemplate != null) return cachedTemplate;

        var resourceManager = level.getServer().getResourceManager();
        Identifier fileId = Identifier.fromNamespaceAndPath("syndicate", "structures/syndicate_base.nbt");

        var optResource = resourceManager.getResource(fileId);
        if (optResource.isEmpty()) {
            SyndicateMod.LOGGER.error(
                    "Could not find {} via ResourceManager — base placement disabled.", fileId);
            return null;
        }
        try (var stream = optResource.get().open()) {
            CompoundTag nbt = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
            HolderGetter<Block> blockGetter = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            StructureTemplate template = new StructureTemplate();
            template.load(blockGetter, nbt);
            SyndicateMod.LOGGER.info("Loaded syndicate_base.nbt — size: {}", template.getSize());
            cachedTemplate = template;
            return template;
        } catch (Exception e) {
            SyndicateMod.LOGGER.error("Failed to load syndicate_base.nbt: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Forsøger at placere en syndikats-base i den region-celle der indeholder triggerChunk.
     *
     * Metoden er designet til at blive kaldt fra CHUNK_LOAD-eventet for HVER chunk der loader.
     * Den afviser straks (O(1)) hvis den loadede chunk ikke er den deterministiske kandidat
     * for sin celle — dvs. 99.9 % af alle chunk-load events returnerer false med minimal cost.
     *
     * Når kandidat-chunken loader, køres S06-validering og strukturen stamps hvis alle
     * checks er bestået. Resultatet (den nye base) gemmes i både SyndicateBaseManager
     * og SyndicateSavedData så den overlever serverrestart.
     *
     * Kalder ikke chunk-loading — al validering sker kun på allerede-loadede chunks.
     *
     * @param level        serververdenen (sendt fra CHUNK_LOAD-eventet)
     * @param triggerChunk den chunk der netop er loadet
     * @return true hvis en base faktisk blev placeret, ellers false
     */
    public static boolean tryPlaceBase(ServerLevel level, ChunkPos triggerChunk) {
        SyndicateConfig config = SyndicateConfig.getInstance();

        // Beregn gitter-cellen for trigger-chunken
        int[] cell = computeCell(triggerChunk, config.getBaseSpacingChunks());
        int cellX = cell[0];
        int cellZ = cell[1];

        // Beregn den deterministiske kandidat-chunk for denne celle
        ChunkPos candidate = computeCandidate(
                cellX, cellZ, level.getSeed(),
                config.getBaseSpacingChunks(), config.getBaseSeparationChunks()
        );

        // Afvis straks hvis den loadede chunk ikke er kandidaten — hurtigste check muligt.
        // Kun én chunk ud af spacing×spacing chunks pr. celle er kandidaten.
        if (!candidate.equals(triggerChunk)) return false;

        SyndicateMod.LOGGER.debug("Candidate chunk {} loaded for cell ({},{})", triggerChunk, cellX, cellZ);

        // Billig pre-check: spring template-indlæsning over hvis cellen allerede er afklaret
        ResourceKey<Level> dimension = level.dimension();
        if (SyndicateBaseManager.hasBaseInCell(cellX, cellZ, dimension, config.getBaseSpacingChunks())) {
            SyndicateMod.LOGGER.debug("Cell ({},{}) already has an active base — skipping", cellX, cellZ);
            return false;
        }
        SyndicateSavedData savedData = SyndicateSavedData.getOrCreate(level);
        if (savedData.isHardRejected(cellX, cellZ, dimension)) {
            SyndicateMod.LOGGER.debug("Cell ({},{}) is permanently blacklisted — skipping", cellX, cellZ);
            return false;
        }

        // Indlæs strukturskabelonen manuelt fra JAR-ressourcer via ResourceManager.
        //
        // MC 26.1.1 har skiftet til SNBT-format (.snbt) for resource-pack-strukturer,
        // og StructureTemplateManager.get() leder kun efter .snbt-filer. Da vores
        // syndicate_base2.nbt er et binært NBT-fil (gemt via /structure save), bypasser
        // vi get() og læser filen direkte med NbtIo.readCompressed().
        //
        // Ressource-stien: data/syndicate/structures/syndicate_base2.nbt
        // → Identifier("syndicate", "structures/syndicate_base2.nbt")
        StructureTemplate template = loadTemplate(level);
        if (template == null) return false;

        try {
            return doPlaceBase(level, cellX, cellZ, candidate, template, savedData);
        } catch (Throwable t) {
            // Fabric's event-system sluger undtagelser i event-handlers uden at logge dem.
            // Denne catch sikrer at alle fejl under placering er synlige i loggen.
            SyndicateMod.LOGGER.error("Unexpected error while placing base for cell ({},{}): {}",
                    cellX, cellZ, t.getMessage(), t);
            return false;
        }
    }

    /**
     * Intern hjælper der udfører selve valideringen og stampingen.
     * Udskilt fra tryPlaceBase() så exceptions kan fanges ét sted uden at rode koden til.
     */
    private static boolean doPlaceBase(ServerLevel level, int cellX, int cellZ,
                                        ChunkPos candidate, StructureTemplate template,
                                        SyndicateSavedData savedData) {
        SyndicateConfig config = SyndicateConfig.getInstance();
        ResourceKey<Level> dimension = level.dimension();

        // Deterministisk rotation baseret på celle-seed — samme rotation ved hvert forsøg
        // for en given celle. Ikke-deterministisk rotation ændrer footprint-AABB'en pr. forsøg
        // og gør chunk-guard-resultatet inkonsistent på tværs af retries.
        long rotSeed = level.getSeed()
                + (long) cellX * 987654321L
                + (long) cellZ * 123456789L;
        Rotation rotation = Rotation.values()[(int) Math.floorMod(rotSeed, 4)];

        // S06-validering: kør alle fail-fast checks og beregn placeringsursprung.
        // savedData sendes videre så validate() ikke kalder getOrCreate() selv —
        // det forhindrer blokering på DataStorage-låsen under autosave.
        BlockPos placeOrigin = validate(level, cellX, cellZ, candidate, template, rotation, savedData);
        if (placeOrigin == null) {
            SyndicateMod.LOGGER.debug("Cell ({},{}) rejected — terrain unsuitable or neighbor chunks not loaded",
                    cellX, cellZ);
            return false;
        }
        SyndicateMod.LOGGER.info("Terrain validation passed — origin={}, rotation={}", placeOrigin, rotation);

        // ── Beregn AABB og skakt-position FØR stamping ───────────────────────
        BlockPos markerLocalPos = findShaftMarker(template, rotation); // ikke null — validate() tjekkede det
        assert markerLocalPos != null;
        BlockPos shaftSurfacePos = placeOrigin.offset(markerLocalPos);

        AABB bounds = computeFootprintAABB(placeOrigin, template, rotation);
        int minX = (int) bounds.minX;
        int maxX = (int) bounds.maxX;
        int minY = (int) bounds.minY;
        int maxY = (int) bounds.maxY;
        int minZ = (int) bounds.minZ;
        int maxZ = (int) bounds.maxZ;

        // ── Stamp strukturen (kun solide blokke) ─────────────────────────────
        // passableNonAirInTemplate: ikke-luft-blokke i templaten der IKKE er solide —
        // f.eks. stiger, trapdoors, torches, skilte. Bruges til flood-fill spredning
        // så skakten kan gennemkrydses selv med en stige på væggen.
        // Disse blokke carves IKKE (kun luftblokke carves), men de tillader flood-fill
        // at nå de rum der er forbundet via skakten.
        Set<BlockPos> passableNonAirInTemplate = new HashSet<>();

        StructureProcessor skipAirProcessor = new StructureProcessor() {
            @Override
            public StructureTemplate.StructureBlockInfo processBlock(
                    LevelReader levelReader, BlockPos offset, BlockPos pos,
                    StructureTemplate.StructureBlockInfo original,
                    StructureTemplate.StructureBlockInfo modified,
                    StructurePlaceSettings placeSettings) {
                if (modified.state().isAir()) {
                    return null; // spring luftblok over
                }
                // Ikke-solid blok (stiger, trapdoors, fakler, TRIPWIRE, TRIPWIRE_HOOK m.fl.) —
                // gem verdensposition til flood-fill traversal og stamp normalt.
                // Snøre-kroger kobles automatisk: den sidst-stampede krog kalder onPlace()
                // → calculateState() finder modkrogen og sætter ATTACHED=true på hele fælden.
                if (!modified.state().isSolid()) {
                    passableNonAirInTemplate.add(modified.pos());
                }
                return modified;
            }
            @Override
            protected StructureProcessorType<?> getType() {
                return StructureProcessorType.NOP;
            }
        };

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setIgnoreEntities(true)
                .addProcessor(skipAirProcessor);

        template.placeInWorld(level, placeOrigin, placeOrigin, settings, level.getRandom(),
                Block.UPDATE_CLIENTS);

        // ── Fjern SHAFT_MARKER ───────────────────────────────────────────────
        level.setBlock(shaftSurfacePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

        // ── Udhul interiøret under overfladen ────────────────────────────────
        StructurePlaceSettings filterSettings = new StructurePlaceSettings().setRotation(rotation);
        int surfaceCutoffY = shaftSurfacePos.getY();

        int carvedCount = 0;

        // Byg opslags-set over luftblokke i templaten (kun disse carves).
        Set<BlockPos> allAirInTemplate = new HashSet<>();
        for (var bi : template.filterBlocks(placeOrigin, filterSettings, Blocks.AIR))
            allAirInTemplate.add(bi.pos());
        for (var bi : template.filterBlocks(placeOrigin, filterSettings, Blocks.CAVE_AIR))
            allAirInTemplate.add(bi.pos());

        // Flood-fill traversal-set: luft + passable ikke-luft (stiger, trapdoors osv.).
        Set<BlockPos> floodPassable = new HashSet<>(allAirInTemplate);
        floodPassable.addAll(passableNonAirInTemplate);

        // Find flood-fill startpunkt: scan nedad fra SHAFT_MARKER til første passable blok.
        BlockPos floodStart = null;
        for (int dy = 1; dy <= template.getSize(rotation).getY(); dy++) {
            BlockPos floodCandidate = shaftSurfacePos.below(dy);
            if (floodPassable.contains(floodCandidate)) {
                floodStart = floodCandidate;
                break;
            }
        }

        Set<BlockPos> toCarve = new HashSet<>();

        if (floodStart != null) {
            Queue<BlockPos> queue = new ArrayDeque<>();
            queue.add(floodStart);
            toCarve.add(floodStart);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (floodPassable.contains(neighbor) && toCarve.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        } else {
            SyndicateMod.LOGGER.warn(
                    "Ingen passable blok under skaktindgang {} — interiør ikke udhulet",
                    shaftSurfacePos);
        }

        // Udhul kun positioner der er: (a) flood-reachable, (b) luft i template, (c) under overfladen
        for (BlockPos worldPos : toCarve) {
            if (!allAirInTemplate.contains(worldPos)) continue;
            if (worldPos.getY() < surfaceCutoffY) {
                level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                carvedCount++;
            }
        }

        // ── Autodiscovery: scan AABB for kister og spawn-markører ─────────────
        List<BlockPos> chestPositions = new ArrayList<>();
        List<BlockPos> spawnPositions = new ArrayList<>();
        BlockPos.MutableBlockPos discMutable = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    discMutable.set(x, y, z);
                    if (level.getBlockEntity(discMutable) instanceof ChestBlockEntity) {
                        chestPositions.add(discMutable.immutable());
                    } else if (level.getBlockState(discMutable).is(Blocks.EMERALD_BLOCK)) {
                        spawnPositions.add(discMutable.immutable());
                        level.setBlock(discMutable, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        SyndicateMod.LOGGER.info(
                "Base ved {} placeret — {} kister, {} spawn-markører, {} blokke udhulet",
                shaftSurfacePos, chestPositions.size(), spawnPositions.size(), carvedCount);

        // ── Tilføj starter-loot ───────────────────────────────────────────────
        int totalStarterItems = fillStarterLoot(level, chestPositions,
                config.getStarterLootTotal(), config.getStarterValuableCount());

        // ── Opret og registrér basen ──────────────────────────────────────────
        SyndicateBase base = new SyndicateBase(
                shaftSurfacePos, bounds, dimension,
                chestPositions, spawnPositions, totalStarterItems
        );

        SyndicateBaseManager.addBase(base);
        savedData.addBase(base);

        SyndicateBaseManager.spawnGuardsIfNeeded(level, base);

        return true;
    }

    /**
     * Almindelige filler-items der bruges til at fylde den ikke-værdifulde del af starter-looten.
     *
     * Listen skal forestille et kriminelt syndicats lager: basale forsyninger man finder
     * overalt — ikke sjældne, men heller ikke unyttige. Til forskel fra de prioriterede
     * ChestThief-items er disse hardcodet fordi de ikke er afhængige af spillerens kiste-indhold.
     */
    private static final List<Item> FILLER_ITEMS = List.of(
            Items.BREAD, Items.COAL, Items.OAK_PLANKS, Items.STICK,
            Items.WHEAT, Items.BONE, Items.LEATHER, Items.STRING,
            Items.GRAVEL, Items.ARROW, Items.TORCH, Items.LADDER
    );

    /**
     * Fylder kisterne i basen med to slags items:
     *   1. valuableCount items fra ChestThiefConfig's prioritetsliste (medium 100–400):
     *      jernredskaber, mad, basismaterialer — attraktivt men ikke game-breaking.
     *   2. (totalItems − valuableCount) filler-items fra FILLER_ITEMS: kul, planker,
     *      brød osv. — giver basen en realistisk "fyldt lager"-fornemmelse.
     *
     * Items placeres ét ad gangen i tilfældigt valgte kister (round-robin med random offset)
     * for at fordele looten jævnt på tværs af alle kiste-slots.
     *
     * @param level          serverniveauet — bruges til random og block entity adgang
     * @param chestPositions absolutte positioner på alle kiste-blokke i basen
     * @param totalItems     samlet antal items der forsøges placeret
     * @param valuableCount  heraf items fra ChestThiefConfig's prioritetsliste
     * @return det samlede antal items der faktisk blev placeret
     */
    private static int fillStarterLoot(ServerLevel level, List<BlockPos> chestPositions,
                                        int totalItems, int valuableCount) {
        if (totalItems <= 0 || chestPositions.isEmpty()) return 0;

        // Byg pool af værdifulde items fra ChestThiefConfig (medium-prioritet 100–400).
        // ChestThiefConfig er initialiseret inden SyndicateMod — getInstance() er ikke-null her.
        Map<String, Integer> allValues = ChestThiefConfig.getInstance().getItemValues();
        List<Item> valuablePool = allValues.entrySet().stream()
                .filter(e -> e.getValue() >= 100 && e.getValue() <= 400)
                .map(e -> {
                    Identifier id = Identifier.tryParse(e.getKey());
                    if (id == null) return null;
                    return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                })
                .filter(item -> item != null)
                .toList();

        if (valuablePool.isEmpty()) {
            SyndicateMod.LOGGER.warn("No medium-priority items found in chest_thief_values.json — using filler items only");
            valuableCount = 0;
        }

        // Byg den samlede sekvens: valuableCount værdifulde + resten filler, i blandet rækkefølge.
        // Vi bruger Collections.shuffle via en lokal liste så rækkefølgen varierer pr. base.
        RandomSource rng = level.getRandom();
        List<Item> sequence = new java.util.ArrayList<>(totalItems);
        for (int i = 0; i < valuableCount; i++) {
            sequence.add(valuablePool.get(rng.nextInt(valuablePool.size())));
        }
        int fillerCount = totalItems - valuableCount;
        for (int i = 0; i < fillerCount; i++) {
            sequence.add(FILLER_ITEMS.get(rng.nextInt(FILLER_ITEMS.size())));
        }
        // Bland sekvensen så værdifulde og filler-items ikke sidder i blokke
        java.util.Collections.shuffle(sequence, new java.util.Random(rng.nextLong()));

        int totalPlaced = 0;
        for (Item item : sequence) {
            // Gennemgå kister startende fra en tilfældig position for at fordele items jævnt.
            // Uden random offset ville alle items lande i den første kiste med plads.
            int start = rng.nextInt(chestPositions.size());
            boolean placed = false;
            for (int j = 0; j < chestPositions.size() && !placed; j++) {
                BlockPos chestPos = chestPositions.get((start + j) % chestPositions.size());
                if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) continue;

                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    if (chest.getItem(slot).isEmpty()) {
                        chest.setItem(slot, new ItemStack(item));
                        totalPlaced++;
                        placed = true;
                        break;
                    }
                }
            }
            // Alle kiste-slots er fyldte — stop tidligt
            if (!placed) break;
        }

        return totalPlaced;
    }
}
