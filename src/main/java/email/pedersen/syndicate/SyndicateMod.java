package email.pedersen.syndicate;

import email.pedersen.chestthief.events.ChestThiefDepartedEvent;
import email.pedersen.syndicate.block.SyndicateChestBlock;
import email.pedersen.syndicate.block.entity.SyndicateChestBlockEntity;
import email.pedersen.syndicate.config.SyndicateConfig;
import email.pedersen.syndicate.entity.CarrotArrowEntity;
import email.pedersen.syndicate.entity.SyndicateGuardEntity;
import email.pedersen.syndicate.item.SyndicateMapItem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hoved-indgangspunktet for Syndicate-modulet.
 * Registreres som selvstændigt entrypoint i fabric.mod.json ved siden af ChestThiefMod,
 * så syndikat-koden bootstrapper sig selv uden afhængighed til chestthief-pakken.
 */
public class SyndicateMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("syndicate");

    /**
     * Intern registry-nøgle for vagt-entiteten — bruges kun under registrering.
     * Format: syndicate:guard
     */
    private static final ResourceKey<EntityType<?>> GUARD_KEY = ResourceKey.create(
            Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("syndicate", "guard")
    );

    private static final ResourceKey<EntityType<?>> CARROT_KEY = ResourceKey.create(
            Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("syndicate", "carrot_projectile")
    );

    /**
     * Registry-nøgle til syndikatskort-itemet — brugt under registrering og i WanderingTraderMixin.
     * Public så mixinen kan referere til item-typen uden at kende til registreringsdetaljerne.
     */
    public static final ResourceKey<Item> MAP_ITEM_KEY = ResourceKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath("syndicate", "map")
    );

    /**
     * Det registrerede SyndicateMapItem.
     * maxStackSize(1) — kortet er single-use og bør ikke stable (hvert kort er unikt efter use).
     */
    public static final SyndicateMapItem SYNDICATE_MAP = Registry.register(
            BuiltInRegistries.ITEM,
            MAP_ITEM_KEY,
            new SyndicateMapItem(
                    new Item.Properties()
                            .setId(MAP_ITEM_KEY)
                            .stacksTo(1)
            )
    );

    /**
     * Den registrerede entitets-type for SyndicateGuardEntity.
     * Initialiseres statisk så andre klasser (SyndicateBaseManager, SyndicateModClient)
     * kan referere til den uden at bekymre sig om initialiseringsrækkefølge.
     * Bredde 0.6 × højde 1.95 blokke — standard zombie-størrelse.
     */
    public static final EntityType<SyndicateGuardEntity> GUARD_ENTITY_TYPE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            GUARD_KEY,
            EntityType.Builder.of(SyndicateGuardEntity::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.95f)
                    .clientTrackingRange(8)
                    .build(GUARD_KEY)
    );

    /**
     * Lyd der afspilles når en vagt affyrer en gulerod-pil.
     * Lydfilen ligger i assets/syndicate/sounds/guard_shoot.ogg og er registreret i
     * assets/syndicate/sounds.json under nøglen "entity.syndicate_guard.shoot".
     * Udskift guard_shoot.ogg for at ændre lyden uden at røre koden.
     */
    public static final SoundEvent GUARD_SHOOT_SOUND = Registry.register(
            BuiltInRegistries.SOUND_EVENT,
            Identifier.fromNamespaceAndPath("syndicate", "entity.syndicate_guard.shoot"),
            SoundEvent.createVariableRangeEvent(
                    Identifier.fromNamespaceAndPath("syndicate", "entity.syndicate_guard.shoot"))
    );

    /**
     * Lyd der afspilles når en gulerod-pil rammer en blok.
     * Erstatter Minecrafts standard ARROW_HIT-lyd (pil borer sig ind i træ).
     * Lydfilen ligger i assets/syndicate/sounds/carrot_hit.ogg og er registreret i
     * assets/syndicate/sounds.json under nøglen "entity.carrot_arrow.hit".
     * Udskift carrot_hit.ogg for at ændre lyden uden at røre koden.
     */
    public static final SoundEvent CARROT_HIT_SOUND = Registry.register(
            BuiltInRegistries.SOUND_EVENT,
            Identifier.fromNamespaceAndPath("syndicate", "entity.carrot_arrow.hit"),
            SoundEvent.createVariableRangeEvent(
                    Identifier.fromNamespaceAndPath("syndicate", "entity.carrot_arrow.hit"))
    );

    /**
     * Registry-nøgle til guard spawn egg-itemet.
     * Private: ekstern kode refererer til GUARD_SPAWN_EGG direkte.
     */
    private static final ResourceKey<Item> GUARD_SPAWN_EGG_KEY = ResourceKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath("syndicate", "guard_spawn_egg")
    );

    /**
     * Spawn egg der spawner en SyndicateGuardEntity når det bruges på en blok i kreativ tilstand.
     * .spawnEgg(GUARD_ENTITY_TYPE) knytter itemet til entitetstypen — Minecraft håndterer spawn-logikken.
     * Tekstur: assets/syndicate/textures/item/guard_spawn_egg.png (mørk antracit + rød).
     */
    public static final SpawnEggItem GUARD_SPAWN_EGG = Registry.register(
            BuiltInRegistries.ITEM,
            GUARD_SPAWN_EGG_KEY,
            new SpawnEggItem(
                    new Item.Properties()
                            .setId(GUARD_SPAWN_EGG_KEY)
                            .spawnEgg(GUARD_ENTITY_TYPE)
            )
    );

    public static final EntityType<CarrotArrowEntity> CARROT_ARROW = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            CARROT_KEY,
            EntityType.Builder.<CarrotArrowEntity>of(CarrotArrowEntity::new, MobCategory.MISC)
                    .sized(0.25f, 0.25f)
                    .clientTrackingRange(8)
                    .updateInterval(10)
                    .build(CARROT_KEY)
    );

    // -------------------------------------------------------------------------
    // Struktur-type og pjece-type — bruges af /locate structure syndicate:syndicate_base
    // -------------------------------------------------------------------------

    /**
     * Registrerer SyndicateBaseMarkerPiece som StructurePieceType.
     *
     * StructurePieceType er en funktion (CompoundTag → StructurePiece) der bruges af MC
     * til at deserialisere stykker fra chunk-NBT. Uden registrering kan Minecraft ikke
     * genindlæse vores markørpjece fra disk, og StructureStart'et bliver ugyldigt efter genstart.
     *
     * Feltet SyndicateBaseMarkerPiece.TYPE sættes som en sideeffekt, fordi registeringen
     * sker via lambdaen der kalder constructoren — TYPE skal kendes inden nogen kode
     * forsøger at oprette en SyndicateBaseMarkerPiece-instans.
     */
    public static final StructurePieceType SYNDICATE_BASE_MARKER_PIECE_TYPE =
            Registry.register(
                    BuiltInRegistries.STRUCTURE_PIECE,
                    Identifier.fromNamespaceAndPath("syndicate", "base_marker"),
                    (StructurePieceType) (ctx, nbt) -> new SyndicateBaseMarkerPiece(ctx, nbt)
            );

    /**
     * Registrerer SyndicateBaseStructure's codec i BuiltInRegistries.STRUCTURE_TYPE.
     *
     * StructureType er codec-registret: det fortæller MC hvordan den JSON-baserede
     * Structure-instans (fra data/syndicate/worldgen/structure/syndicate_base.json)
     * skal deserialiseres til et SyndicateBaseStructure-objekt.
     *
     * Public fordi SyndicateBaseStructure.type() skal returnere denne konstant.
     */
    public static final StructureType<SyndicateBaseStructure> SYNDICATE_BASE_STRUCTURE_TYPE =
            Registry.register(
                    BuiltInRegistries.STRUCTURE_TYPE,
                    Identifier.fromNamespaceAndPath("syndicate", "syndicate_base"),
                    () -> SyndicateBaseStructure.CODEC
            );

    // -------------------------------------------------------------------------
    // Syndicate Chest — blok, block entity-type og item
    // -------------------------------------------------------------------------

    /**
     * Registry-nøgle til syndicate-kiste-blokken.
     * Private: ekstern kode refererer til SYNDICATE_CHEST_BLOCK direkte.
     */
    private static final ResourceKey<net.minecraft.world.level.block.Block> SYNDICATE_CHEST_BLOCK_KEY =
            ResourceKey.create(Registries.BLOCK,
                    Identifier.fromNamespaceAndPath("syndicate", "syndicate_chest"));

    /**
     * Den registrerede SyndicateChestBlock.
     * Styrke 2.5 og træ-lyd matcher vanilla-kisten.
     */
    public static final SyndicateChestBlock SYNDICATE_CHEST_BLOCK = Registry.register(
            BuiltInRegistries.BLOCK,
            SYNDICATE_CHEST_BLOCK_KEY,
            new SyndicateChestBlock(
                    BlockBehaviour.Properties.of()
                            .setId(SYNDICATE_CHEST_BLOCK_KEY)
                            .strength(2.5f)
                            .sound(SoundType.WOOD)
            )
    );

    /**
     * Block entity-type for SyndicateChestBlockEntity.
     * Registreres med Fabric's FabricBlockEntityTypeBuilder da vanilla's Builder er fjernet i MC 26.1.1.
     */
    public static final BlockEntityType<SyndicateChestBlockEntity> SYNDICATE_CHEST_BLOCK_ENTITY_TYPE =
            Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    Identifier.fromNamespaceAndPath("syndicate", "syndicate_chest"),
                    FabricBlockEntityTypeBuilder.create(SyndicateChestBlockEntity::new, SYNDICATE_CHEST_BLOCK).build()
            );

    /**
     * Registry-nøgle til syndicate-kiste-itemet.
     * Private: ekstern kode refererer til SYNDICATE_CHEST_ITEM direkte.
     */
    private static final ResourceKey<Item> SYNDICATE_CHEST_ITEM_KEY =
            ResourceKey.create(Registries.ITEM,
                    Identifier.fromNamespaceAndPath("syndicate", "syndicate_chest"));

    /**
     * BlockItem der repræsenterer SYNDICATE_CHEST_BLOCK i spillerens inventory.
     * stacksTo(64) — kister er normale items der kan stables.
     */
    public static final BlockItem SYNDICATE_CHEST_ITEM = Registry.register(
            BuiltInRegistries.ITEM,
            SYNDICATE_CHEST_ITEM_KEY,
            new BlockItem(SYNDICATE_CHEST_BLOCK, new Item.Properties().setId(SYNDICATE_CHEST_ITEM_KEY))
    );

    /**
     * Ventende base-valideringer: én post pr. region-celle der har en loaded kandidat-chunk.
     *
     * Nøgle: "dimension:cellX,cellZ" — unik pr. celle pr. dimension.
     * Værdi: PendingCandidate med world-reference, kandidat-ChunkPos og celle-koordinater.
     *
     * Hvorfor ikke køre direkte i CHUNK_LOAD?
     * CHUNK_LOAD fyres mens Fabric's chunk-loader stadig holder interne låse på den
     * nyloadede chunk. Kalder vi getHeightmapPos() eller getBlockState() herfra, forsøger
     * main-thread at erhverve de samme låse → deadlock og serverfrysen.
     *
     * Løsning: CHUNK_LOAD registrerer kun at kandidaten er klar. END_SERVER_TICK kører
     * valideringen ét tick senere, når alle chunk-låse er frigivet.
     *
     * Retry: bløde afvisninger (nabochunks ikke klar, hule) fjernes ikke fra sættet —
     * de forsøges automatisk igen næste gang betingelserne muligvis er opfyldt.
     * Rate-limiting via LAST_ATTEMPT_TICK sikrer at dyre blok-scanninger (check 4)
     * højst kører én gang pr. sekund pr. kandidat.
     */
    private static final Map<String, PendingCandidate> PENDING_VALIDATIONS = new ConcurrentHashMap<>();

    /**
     * Sidste tick en given celle sidst blev forsøgt valideret.
     * Nøgle: samme "dimension:cellX,cellZ" som PENDING_VALIDATIONS.
     * Bruges til at rate-limite forsøg så dyre blok-scanninger ikke kører 20×/sekund.
     */
    private static final Map<String, Long> LAST_ATTEMPT_TICK = new ConcurrentHashMap<>();

    /**
     * Antal syndikats-baser placeret siden serveropstart (nulstilles ved SERVER_STOPPED).
     * Bruges udelukkende til statistik-loglinjen der kører hvert 5. minut.
     */
    private static int basesPlacedThisSession = 0;

    /**
     * Dataklasse der holder den information vi skal bruge for at (gen)forsøge en validering.
     *
     * @param world      serverniveauet — bruges til alle blok- og struktur-opslag
     * @param candidate  den deterministiske kandidat-chunk for cellen
     * @param cellX      gitter-cellens X-koordinat — bruges til hard-rejection-check
     * @param cellZ      gitter-cellens Z-koordinat — bruges til hard-rejection-check
     */
    private record PendingCandidate(ServerLevel world, ChunkPos candidate, int cellX, int cellZ) {}

    @Override
    public void onInitialize() {
        LOGGER.info("Syndicate module initializing...");

        // TYPE-feltet skal sættes FØR nogen SyndicateBaseMarkerPiece-instans oprettes.
        // Statiske felter i SyndicateMod er initialiseret inden onInitialize() kaldes,
        // så SYNDICATE_BASE_MARKER_PIECE_TYPE er garanteret ikke-null her.
        SyndicateBaseMarkerPiece.TYPE = SYNDICATE_BASE_MARKER_PIECE_TYPE;

        SyndicateConfig.load();

        // Tilføj guard spawn egg til creative-menuen under "Spawn Eggs".
        net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(
                net.minecraft.world.item.CreativeModeTabs.SPAWN_EGGS
        ).register(output -> output.accept(GUARD_SPAWN_EGG));

        // Registrer syndicate-kisten i creative-menuen under "Functional Blocks".
        // Nøglen konstrueres manuelt da CreativeModeTabs.FUNCTIONAL_BLOCKS er private.
        net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(
                ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                        Identifier.withDefaultNamespace("functional_blocks"))
        ).register(output -> output.accept(
                new ItemStack(SYNDICATE_CHEST_ITEM),
                CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS
        ));

        // Overstyr /locate structure syndicate:syndicate_base med et direkte cache-opslag.
        //
        // Vanilla's /locate scanner i en spiral og loader chunks undervejs — det fryser
        // main-tråden i flere sekunder. I stedet registrerer vi en Brigadier-literal-node
        // med præcis vores struktur-ID. I Brigadier vinder literals over arguments under
        // parsing, så vores execute-funktion kører i stedet for vanilla's spiral-søgning.
        // Resultatet er et O(n)-opslag i SyndicateBaseManager (n = antal baser) — instant.
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            dispatcher.register(
                Commands.literal("locate")
                    .then(Commands.literal("structure")
                        .then(Commands.literal("syndicate:syndicate_base")
                            .executes(ctx -> {
                                var source = ctx.getSource();
                                BlockPos origin = BlockPos.containing(source.getPosition());
                                ServerLevel level = source.getLevel();

                                SyndicateBase nearest = SyndicateBaseManager.findNearest(level, origin);

                                // Bestem display-position og label afhængigt af om basen er
                                // placeret (koordinater til indgang) eller teoretisk (chunk-centrum).
                                final BlockPos pos;
                                final boolean isTheoretical;
                                if (nearest != null) {
                                    pos = nearest.getPosition();
                                    isTheoretical = false;
                                } else {
                                    // Ingen base placeret endnu — beregn nærmeste kandidat-chunks centrum.
                                    // Spilleren skal flyve dertil for at trigge placeringen.
                                    ChunkPos candidate = findNearestTheoreticalCandidate(level, origin);
                                    if (candidate == null) {
                                        source.sendFailure(Component.literal(
                                            "Ingen Syndicate Base fundet og ingen kandidat-celle tilgængelig"));
                                        return 0;
                                    }
                                    // Y=64 er en rimelig approksimation af terrænoverfladen — den præcise
                                    // Y kendes ikke før chunken er loaded og basen er placeret.
                                    pos = candidate.getMiddleBlockPosition(64);
                                    isTheoretical = true;
                                }

                                // 2D-afstand (ignorerer Y) — samme konvention som vanilla /locate
                                int dist = (int) Math.sqrt(
                                    (double)(pos.getX() - origin.getX()) * (pos.getX() - origin.getX()) +
                                    (double)(pos.getZ() - origin.getZ()) * (pos.getZ() - origin.getZ())
                                );

                                // Klikbar koordinat-blok med /tp-forslag ved klik
                                var coords = ComponentUtils.wrapInSquareBrackets(
                                    Component.translatable("chat.coordinates",
                                            pos.getX(), pos.getY(), pos.getZ())
                                        .withStyle(s -> s
                                            .withColor(isTheoretical ? ChatFormatting.YELLOW : ChatFormatting.GREEN)
                                            .withClickEvent(new ClickEvent.SuggestCommand(
                                                "/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                                            .withHoverEvent(new HoverEvent.ShowText(
                                                Component.translatable("chat.coordinates.tooltip")))
                                        )
                                );

                                final int distFinal = dist;
                                if (isTheoretical) {
                                    source.sendSuccess(() ->
                                        Component.literal("Nærmeste syndicate:syndicate_base er endnu ikke genereret — flyt til ")
                                            .append(coords)
                                            .append(Component.literal(" (" + distFinal + " blokke væk) for at generere basen")),
                                        false);
                                } else {
                                    source.sendSuccess(() ->
                                        Component.literal("Nærmeste syndicate:syndicate_base er ved ")
                                            .append(coords)
                                            .append(Component.literal(" (" + distFinal + " blokke væk)")),
                                        false);

                                    // Opret waypoint i Xaero's Minimap hvis installeret OG spilleren
                                    // har slået det til i config (standard: fra — baserne er hemmelige).
                                    // EnvType.CLIENT-tjekket forhindrer eksekvering på dedicated server.
                                    // Minecraft.getInstance().execute() skifter til klient-tråden — Xaero's
                                    // waypoint-API må kun tilgås fra klient-tråden, ikke server-tråden.
                                    // Lambda-syntaks (ikke method reference) sikrer at JVM'en ikke forsøger
                                    // at loade XaeroWaypointBridge ved opstart — kun når lambda'en kaldes.
                                    if (SyndicateConfig.getInstance().isCreateXaeroWaypoints()
                                            && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                                            && FabricLoader.getInstance().isModLoaded("xaerominimap")) {
                                        net.minecraft.client.Minecraft.getInstance().execute(
                                            () -> email.pedersen.syndicate.client.XaeroWaypointBridge
                                                .addWaypoint(pos, "Syndicate Base"));
                                    }
                                }
                                return 1;
                            })
                        )
                    )
            );
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SyndicateBaseManager.clearAll();
            SyndicateChestTracker.clearAll();
            PENDING_VALIDATIONS.clear();
            LAST_ATTEMPT_TICK.clear();
            basesPlacedThisSession = 0;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerLevel overworld = server.overworld();
            SyndicateSavedData savedData = SyndicateSavedData.getOrCreate(overworld);
            for (SyndicateBase base : savedData.getBases()) {
                SyndicateBaseManager.addBase(base);
            }
            LOGGER.info("Loaded {} syndicate base(s) from disk", savedData.getBases().size());
        });

        // CHUNK_LOAD: registrér kandidat-chunks til validering næste tick.
        // Ingen blok-opslag her — kun O(1) beregning af celle og kandidat.
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, isNew) -> {
            SyndicateConfig config = SyndicateConfig.getInstance();
            int[] cell = SyndicateBasePlacer.computeCell(chunk.getPos(), config.getBaseSpacingChunks());
            int cellX = cell[0];
            int cellZ = cell[1];
            ChunkPos candidate = SyndicateBasePlacer.computeCandidate(
                    cellX, cellZ, world.getSeed(),
                    config.getBaseSpacingChunks(), config.getBaseSeparationChunks());
            if (!candidate.equals(chunk.getPos())) return; // ikke kandidaten for cellen

            String key = cellKey(world.dimension(), cellX, cellZ);
            PENDING_VALIDATIONS.putIfAbsent(key, new PendingCandidate(world, candidate, cellX, cellZ));
        });

        // END_SERVER_TICK: forsøg ventende valideringer, én gang pr. sekund pr. kandidat.
        // Chunk-låsene fra CHUNK_LOAD er nu frigivet — blok-opslag er sikre.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long currentTick = server.getTickCount();
            SyndicateConfig config = SyndicateConfig.getInstance();

            // Forsøg første base-placering i de første 100 ticks (5 sek) uden force-loading.
            // Minecraft loader spawn-chunks automatisk — de er typisk klar inden tick 5-10.
            // Hvis det ikke lykkes inden 100 ticks, overtager CHUNK_LOAD-pathen naturligt
            // når spilleren flyver mod de gule koordinater fra /locate.
            if (currentTick <= 100 && currentTick % 20 == 0) {
                ServerLevel overworld = server.overworld();
                List<SyndicateBase> existingBases = SyndicateBaseManager.getBases(overworld.dimension());
                if (existingBases == null || existingBases.isEmpty()) {
                    tryFirstBasePlacement(overworld);
                }
            }

            if (PENDING_VALIDATIONS.isEmpty()) return;

            long tTick = System.nanoTime();
            int sizeBefore = PENDING_VALIDATIONS.size();

            PENDING_VALIDATIONS.entrySet().removeIf(entry -> {
                String key = entry.getKey();
                PendingCandidate pc = entry.getValue();

                // Rate-limit: forsøg højst én gang pr. sekund (20 ticks).
                // Forhindrer at dyre blok-scanninger (check 4: 17k+ getBlockState-kald)
                // kører 20 gange i sekundet for kandidater med bløde afvisninger.
                long lastTick = LAST_ATTEMPT_TICK.getOrDefault(key, currentTick - 20L);
                if (currentTick - lastTick < 20) return false;
                LAST_ATTEMPT_TICK.put(key, currentTick);

                boolean placed = SyndicateBasePlacer.tryPlaceBase(pc.world(), pc.candidate());
                if (placed) {
                    basesPlacedThisSession++;
                    LAST_ATTEMPT_TICK.remove(key);
                    return true; // fjern: base placeret ✓
                }

                // Tjek om cellen er permanent afgjort — så er der ingen grund til at prøve igen.
                ResourceKey<Level> dimension = pc.world().dimension();
                SyndicateSavedData savedData = SyndicateSavedData.getOrCreate(pc.world());
                if (savedData.isHardRejected(pc.cellX(), pc.cellZ(), dimension)) {
                    LAST_ATTEMPT_TICK.remove(key);
                    return true; // fjern: permanent hard-rejected ✓
                }
                if (SyndicateBaseManager.hasBaseInCell(pc.cellX(), pc.cellZ(), dimension, config.getBaseSpacingChunks())) {
                    LAST_ATTEMPT_TICK.remove(key);
                    return true; // fjern: base allerede aktiv (f.eks. indlæst fra disk) ✓
                }

                return false; // behold: blød afvisning — prøv igen om ~1 sekund
            });

            long elapsed = (System.nanoTime() - tTick) / 1_000_000;
            LOGGER.debug("Processed {} base candidate(s) in {}ms ({} remaining in queue)",
                    sizeBefore, elapsed, PENDING_VALIDATIONS.size());

            // Periodisk statistik-log — kører hvert 5. minut (6000 ticks).
            // Viser samlet antal aktive baser i overworld og antal placeret denne session,
            // så man kan følge med i hvor hurtigt baser dukker op efterhånden som verden udforskes.
            if (currentTick % 6000 == 0 && currentTick > 0) {
                ServerLevel overworld = server.overworld();
                List<SyndicateBase> activeBases = SyndicateBaseManager.getBases(overworld.dimension());
                int activeCount = activeBases == null ? 0 : activeBases.size();
                LOGGER.info("[Syndicate] Aktive baser: {} | placeret denne session: {}",
                        activeCount, basesPlacedThisSession);
            }

            // Periodisk ødelæggelsescheck — kører hvert 200. tick (10 sek).
            // Fjerner baser hvis alle kister er destruerede (f.eks. af TNT-fælder).
            // Kun loadede baser tjekkes — hasChunk() og getBlockEntity() er begge O(1).
            if (currentTick % 200 == 0) {
                for (ServerLevel serverLevel : server.getAllLevels()) {
                    checkDestroyedBases(serverLevel);
                }
            }

            // Periodisk vagt-respawn — kører én gang pr. guardRespawnIntervalTicks.
            // Springer over baser hvor en spiller er i nærheden (givet spilleren ro til at udforske).
            if (currentTick % config.getGuardRespawnIntervalTicks() == 0) {
                for (ServerLevel serverLevel : server.getAllLevels()) {
                    List<SyndicateBase> bases = SyndicateBaseManager.getBases(serverLevel.dimension());
                    if (bases == null) continue;
                    synchronized (bases) {
                        for (SyndicateBase base : bases) {
                            if (base.isRaided()) continue;
                            // Tjek om en spiller er inden for basen + buffer — respawn forhindres
                            // for at undgå at nye vagter materialiserer sig midt i kamp.
                            var aabb = base.getBounds().inflate(config.getGuardRespawnPlayerBuffer());
                            boolean playerNearby = !serverLevel.getEntitiesOfClass(
                                    net.minecraft.world.entity.player.Player.class, aabb).isEmpty();
                            if (!playerNearby) {
                                SyndicateBaseManager.spawnGuardsIfNeeded(serverLevel, base);
                            }
                        }
                    }
                }
            }
        });

        ChestThiefDepartedEvent.EVENT.register((level, pos, loot) -> {
            SyndicateConfig cfg = SyndicateConfig.getInstance();

            // Find nærmeste syndicate-kiste inden for loot-leveringsradius.
            // Tyvene afleverer til en konkret kiste-position, ikke til en base.
            BlockPos chestPos = SyndicateChestTracker.findNearest(level, pos, cfg.getLootDeliveryRadius());
            if (chestPos == null) {
                LOGGER.debug("Thief departed from {} but no syndicate chest within range — loot lost", pos);
                return;
            }

            if (!level.hasChunk(chestPos.getX() >> 4, chestPos.getZ() >> 4)) {
                LOGGER.debug("Syndicate chest at {} is in an unloaded chunk — loot lost", chestPos);
                return;
            }

            if (!(level.getBlockEntity(chestPos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) {
                LOGGER.debug("No chest block entity at syndicate chest position {} — loot lost", chestPos);
                return;
            }

            int addedCount = 0;
            for (var stack : loot) {
                boolean placed = false;
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    if (chest.getItem(slot).isEmpty()) {
                        chest.setItem(slot, stack.copy());
                        placed = true;
                        addedCount++;
                        break;
                    }
                }
                if (!placed) {
                    LOGGER.debug("Syndicate chest at {} is full — {} item(s) could not be stored",
                            chestPos, loot.size() - addedCount);
                    break;
                }
            }

            if (addedCount > 0) {
                LOGGER.debug("Thief delivered {} item(s) to syndicate chest at {}", addedCount, chestPos);
            }
        });

        // Registrer vagters basis-attributter (liv, hastighed, angrebsskade, follow_range).
        // Uden denne linje crasher Minecraft når en vagt spawnes — attribut-mappet er tomt.
        FabricDefaultAttributeRegistry.register(GUARD_ENTITY_TYPE, SyndicateGuardEntity.createAttributes());

        // Fjern vagtens UUID fra basen når den dør, så spawnGuardsIfNeeded()
        // korrekt registrerer at der er plads til en ny vagt.
        // ServerLevel-cast er sikker: AFTER_DEATH fyres kun server-side.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof SyndicateGuardEntity guard)) return;
            ServerLevel guardLevel = (ServerLevel) guard.level();
            SyndicateBase base = SyndicateBaseManager.findBaseByGuardUUID(
                    guardLevel.dimension(), guard.getUUID());
            if (base != null) {
                SyndicateBaseManager.removeGuard(base, guard.getUUID());
                SyndicateSavedData.getOrCreate(guardLevel).markDirty();
                LOGGER.debug("Guard {} died — removed from base at {}", guard.getUUID(), base.getPosition());
            }
        });

        LOGGER.info("Syndicate module initialized!");
    }

    /**
     * Beregner en unik streng-nøgle for en (dimension, cellX, cellZ)-kombination.
     * Bruges som nøgle i PENDING_VALIDATIONS og LAST_ATTEMPT_TICK.
     */
    private static String cellKey(ResourceKey<Level> dimension, int cellX, int cellZ) {
        return dimension.identifier() + ":" + cellX + "," + cellZ;
    }

    /**
     * Forsøger at placere den første base nær spawn uden force-loading.
     *
     * Kaldes fra END_SERVER_TICK de første 100 ticks (5 sek) når ingen baser er placerede.
     * Minecraft loader spawn-chunks automatisk — de er typisk klar inden tick 5-10, og
     * tryPlaceBase() returnerer false hurtigt (via hasChunk()-guard) hvis chunken endnu
     * ikke er loaded. Ingen synkron blokering af main-tråden.
     *
     * Spiral-søgning fra celle (0,0) udad — nærmest spawn forsøges altid først.
     *
     * @param level overworld — baser spawner kun her
     */
    private static void tryFirstBasePlacement(ServerLevel level) {
        SyndicateConfig config = SyndicateConfig.getInstance();
        long seed = level.getSeed();
        int spacing = config.getBaseSpacingChunks();
        int separation = config.getBaseSeparationChunks();
        SyndicateSavedData savedData = SyndicateSavedData.getOrCreate(level);
        ResourceKey<Level> dimension = level.dimension();

        for (int radius = 0; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    if (savedData.isHardRejected(dx, dz, dimension)) continue;
                    ChunkPos candidate = SyndicateBasePlacer.computeCandidate(dx, dz, seed, spacing, separation);
                    // Ingen force-loading — tryPlaceBase returnerer false med det samme
                    // hvis de nødvendige chunks ikke er loaded endnu.
                    if (SyndicateBasePlacer.tryPlaceBase(level, candidate)) {
                        basesPlacedThisSession++;
                        LOGGER.info("Første syndikats-base placeret ved celle ({},{}) efter {} ticks — /locate klar",
                                dx, dz, level.getServer().getTickCount());
                        return;
                    }
                }
            }
        }
    }

    /**
     * Finder nærmeste kandidat-chunk der endnu ikke har en placeret eller hard-rejected base.
     *
     * Bruges af /locate når ingen base er placeret endnu (ny verden, creative-mode-test).
     * Returnerer chunk-centrum så spilleren ved præcis hvilken retning de skal flyve.
     *
     * Spiral-søgning fra spillerens celle udad: radius 0 = spillerens egen celle,
     * radius 1 = de 8 naboer, osv. Stopper ved første radius med én gyldig kandidat
     * (typisk radius 0, dvs. O(1) i praksis).
     *
     * @param level   serverniveauet — bruges til seed og dimension-key
     * @param origin  spillerens position — udgangspunkt for spiral-søgningen
     * @return den nærmeste ubesøgte kandidat-chunk, eller null hvis alle celler er afvist
     */
    @org.jetbrains.annotations.Nullable
    private static ChunkPos findNearestTheoreticalCandidate(ServerLevel level, BlockPos origin) {
        SyndicateConfig config = SyndicateConfig.getInstance();
        long seed = level.getSeed();
        int spacing = config.getBaseSpacingChunks();
        int separation = config.getBaseSeparationChunks();

        ChunkPos originChunk = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);
        int[] originCell = SyndicateBasePlacer.computeCell(originChunk, spacing);
        int ocx = originCell[0];
        int ocz = originCell[1];

        SyndicateSavedData savedData = SyndicateSavedData.getOrCreate(level);
        ResourceKey<Level> dimension = level.dimension();

        // Spiral-søg: radius 0 (egen celle), 1, 2, … maks 4 celler ud
        for (int radius = 0; radius <= 4; radius++) {
            ChunkPos best = null;
            double bestDist = Double.MAX_VALUE;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Kun kanten af kvadratet (ikke det indre — det er dækket af mindre radii)
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                    int cellX = ocx + dx;
                    int cellZ = ocz + dz;

                    // Spring over celler der allerede har en base eller er permanent afvist
                    if (savedData.isHardRejected(cellX, cellZ, dimension)) continue;
                    if (SyndicateBaseManager.hasBaseInCell(cellX, cellZ, dimension, spacing)) continue;

                    ChunkPos candidate = SyndicateBasePlacer.computeCandidate(cellX, cellZ, seed, spacing, separation);
                    BlockPos center = candidate.getMiddleBlockPosition(64);
                    double dist = origin.distSqr(center);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = candidate;
                    }
                }
            }

            // Første radius med en gyldig kandidat — returner straks (ingen grund til at søge videre)
            if (best != null) return best;
        }
        return null;
    }

    /**
     * Fjerner baser i den givne dimension hvis alle deres kister er ødelagte.
     *
     * Køres hvert 200. tick fra END_SERVER_TICK. Algoritmen er designet til at være
     * så billig som muligt:
     *
     *   1. Spring over baser med uloadede chunk-positioner — vi kan ikke afgøre om
     *      kisterne er der eller ej uden at loade chunken. hasChunk() er O(1).
     *
     *   2. Tidlig exit så snart én ChestBlockEntity stadig eksisterer — basen er intakt.
     *      getBlockEntity() er O(1) (HashMap-opslag i LevelChunk).
     *
     *   3. Kun baser med nul-kister tilføjes til toRemove-listen.
     *
     * Fjernelse sker EFTER iterationen for at undgå ConcurrentModificationException
     * på den synchronized liste.
     *
     * @param level dimensionen der tjekkes
     */
    private static void checkDestroyedBases(ServerLevel level) {
        List<SyndicateBase> bases = SyndicateBaseManager.getBases(level.dimension());
        if (bases == null || bases.isEmpty()) return;

        List<SyndicateBase> toRemove = new ArrayList<>();
        synchronized (bases) {
            for (SyndicateBase base : bases) {
                if (base.getChestPositions().isEmpty()) continue;

                // Tjek at alle kiste-chunks er loadede — ellers er vi ikke i stand
                // til at konstatere om kisterne er destruerede eller blot uloadede.
                boolean allChunksLoaded = true;
                for (BlockPos cp : base.getChestPositions()) {
                    if (!level.hasChunk(cp.getX() >> 4, cp.getZ() >> 4)) {
                        allChunksLoaded = false;
                        break;
                    }
                }
                if (!allChunksLoaded) continue;

                // Er mindst én kiste stadig intakt? Tidlig exit ved første fund.
                boolean hasChest = false;
                for (BlockPos cp : base.getChestPositions()) {
                    if (level.getBlockEntity(cp) instanceof ChestBlockEntity) {
                        hasChest = true;
                        break;
                    }
                }
                if (!hasChest) toRemove.add(base);
            }
        }

        if (toRemove.isEmpty()) return;

        SyndicateSavedData savedData = SyndicateSavedData.getOrCreate(level);
        for (SyndicateBase base : toRemove) {
            SyndicateBaseManager.removeBase(base);
            savedData.removeBase(base);
            LOGGER.info("Base ved {} er ødelagt (alle kister væk) — fjernet fra registret",
                    base.getPosition());

            // Fjern det tilhørende Xaero-waypoint hvis spilleren havde det slået til.
            // Samme EnvType/isModLoaded/lambda-mønster som ved oprettelse.
            if (SyndicateConfig.getInstance().isCreateXaeroWaypoints()
                    && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                    && FabricLoader.getInstance().isModLoaded("xaerominimap")) {
                final BlockPos destroyedPos = base.getPosition();
                net.minecraft.client.Minecraft.getInstance().execute(
                    () -> email.pedersen.syndicate.client.XaeroWaypointBridge
                        .removeWaypoint(destroyedPos));
            }
        }
    }
}
