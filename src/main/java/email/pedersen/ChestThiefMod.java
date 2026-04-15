package email.pedersen;

import email.pedersen.config.ChestThiefConfig;
import email.pedersen.entity.ChestThiefEntity;
import email.pedersen.item.ChestThiefSpawnEgg;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hoved-indgangspunktet for Chest Thief-modden.
 * Denne klasse er den første der kører, når Minecraft starter med modden.
 * Dens ansvar er at:
 *   1. Registrere selve Chest Thief-entiteten, så spillet kender den
 *   2. Registrere spawn egg-item, så man kan spawne mobs i kreativ tilstand
 *   3. Indlæse config-filen med indstillinger (radius, biomer osv.)
 *   4. Fortælle spillet i hvilke biomer mobs spawner naturligt
 *   5. Lytte på begivenheder: spillere der ødelægger kister, og server-stop
 * Implementerer ModInitializer — Fabric kalder onInitialize() automatisk ved opstart.
 */
public class ChestThiefMod implements ModInitializer {

    /** Det unikke id for denne mod. Bruges som præfix på alle ressource-navne, f.eks. "chest_thief:chest_thief". */
    public static final String MOD_ID = "chest_thief";

    /** Logger til at skrive beskeder i konsollen, så man kan følge med i hvad modden laver. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * En intern nøgle der entydigt identificerer Chest Thief-entiteten i Minecrafts registre.
     * Tænk på det som entitetens "CPR-nummer" i spillet.
     */
    private static final ResourceKey<EntityType<?>> CHEST_THIEF_KEY = ResourceKey.create(
            Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(MOD_ID, "chest_thief")
    );

    /**
     * Selve entitets-typen for Chest Thief.
     * Dette objekt fortæller Minecraft: "der eksisterer en mob der hedder chest_thief,
     * den er 0.6 blokke bred og 1.8 blokke høj, og den er en MONSTER."
     *
     * Registreres med det samme (static), så andre klasser kan bruge den til at
     * spawne mobs med, f.eks. i spawn egg og biom-spawning.
     */
    public static final EntityType<ChestThiefEntity> CHEST_THIEF_ENTITY_TYPE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            CHEST_THIEF_KEY,
            EntityType.Builder.of(ChestThiefEntity::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.8f)          // bredde × højde i blokke
                    .clientTrackingRange(8)       // antal chunks klienten tracker mobs inden for
                    .build(CHEST_THIEF_KEY)
    );

    /**
     * Spawn egg-itemet, der vises i kreativ-menuens "Spawn Eggs"-fane.
     * Når man bruger det på en blok, spawner en Chest Thief-mob.
     *
     * .spawnEgg(CHEST_THIEF_ENTITY_TYPE) knytter itemet til entiteten,
     * så Minecraft ved hvilken mob der skal spawnes.
     */
    public static final ChestThiefSpawnEgg CHEST_THIEF_SPAWN_EGG = Registry.register(
            BuiltInRegistries.ITEM,
            ResourceKey.create(Registries.ITEM, id("chest_thief_spawn_egg")),
            new ChestThiefSpawnEgg(
                    new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id("chest_thief_spawn_egg")))
                            .spawnEgg(CHEST_THIEF_ENTITY_TYPE)
            )
    );

    /**
     * Kører én gang når serveren/spillet starter med modden.
     * Her sættes alt op: attributter, spawn-regler, event-lyttere osv.
     */
    @Override
    public void onInitialize() {
        LOGGER.info("Chest Thief Mod initializing...");

        // Registrer brugerdefinerede lyd-events (skal ske tidligt, inden de bruges)
        ChestThiefSounds.init();

        // Indlæs indstillinger fra config-filerne i Minecraft's config/-mappe
        ChestThiefConfig.load();

        // Registrer stats for Chest Thief: liv, hastighed, angrebsskade osv.
        // Uden denne linje ville mobs ikke have nogen værdier og ville crashe.
        FabricDefaultAttributeRegistry.register(CHEST_THIEF_ENTITY_TYPE, ChestThiefEntity.createAttributes());

        // Fortæl Minecraft hvordan mobs spawner naturligt:
        // ON_GROUND = de spawner på terræn (ikke i luften eller vand)
        // MOTION_BLOCKING_NO_LEAVES = de spawner ikke under blade
        // checkChestThiefSpawnRules = kun om natten/i mørke (som zombier) OG kræver kister i nærheden
        SpawnPlacements.register(
                CHEST_THIEF_ENTITY_TYPE,
                SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                ChestThiefEntity::checkChestThiefSpawnRules
        );

        // Tilføj spawn egg til kreativ-menuen under "Spawn Eggs"
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS).register(entries ->
                entries.accept(CHEST_THIEF_SPAWN_EGG)
        );

        // Lyt på begivenheden "en levende entitet har taget skade".
        // Hvis det er en Chest Thief der rammes, aktiveres dens retaliation-timer,
        // så den angriber den der slog den — selv om det er dagtid.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            if (entity instanceof ChestThiefEntity chestThief) {
                // getEntity() = den ansvarlige entitet (f.eks. spilleren der skød pilen)
                // getDirectEntity() = den direkte årsag (f.eks. selve pilen eller spillerens knytnæve)
                // Vi foretrækker getEntity() (den "skyldige") som hævnmål
                Entity attackerEntity = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
                LivingEntity attacker = attackerEntity instanceof LivingEntity le ? le : null;
                BlockPos attackerPos = attackerEntity != null ? attackerEntity.blockPosition() : null;
                chestThief.provoke(attacker, attackerPos);
            }
        });

        // Registrer i hvilke biomer mobs spawner naturligt
        registerSpawns();

        // Registrer event-lyttere for kiste-sporing (ødelæggelse og server-stop)
        registerChestTracking();

        LOGGER.info("Chest Thief Mod initialized!");
    }

    /**
     * Tilføjer naturlig spawning af Chest Thieves i de biomer der er angivet i config.
     * For hvert biom-navn fra config parses det til en Identifier (f.eks. "minecraft:plains"),
     * og Minecraft fortælles at spawne Chest Thieves der med den angivne vægt og gruppe-størrelse.
     * Ugyldigge biom-navne i config springer over med en advarsel i konsollen.
     */
    private void registerSpawns() {
        ChestThiefConfig config = ChestThiefConfig.getInstance();

        for (String biomeName : config.getSpawnBiomes()) {
            try {
                Identifier biomeId = Identifier.parse(biomeName);
                BiomeModifications.addSpawn(
                        BiomeSelectors.includeByKey(
                                ResourceKey.create(Registries.BIOME, biomeId)
                        ),
                        MobCategory.MONSTER,
                        CHEST_THIEF_ENTITY_TYPE,
                        config.getSpawnWeight(),    // hvor hyppigt de spawner (højere = mere almindelig)
                        config.getSpawnMinGroup(),  // minimum antal i en gruppe
                        config.getSpawnMaxGroup()   // maksimum antal i en gruppe
                );
            } catch (Exception e) {
                LOGGER.warn("Invalid biome in config: {}", biomeName, e);
            }
        }
    }

    /**
     * Registrerer event-lyttere der holder ChestTracker opdateret.
     * To begivenheder lyttes på:
     *   - En spiller ødelægger en kiste → fjern kisten fra ChestTracker,
     *     så mobs ikke forsøger at gå til en kiste der ikke længere eksisterer.
     *   - Serveren stopper → ryd al data i ChestTracker og ChestCoordinator,
     *     så der ikke er gammelt data når serveren starter igen.
     */
    private void registerChestTracking() {
        // Når en spiller ødelægger en blok: tjek om det er en kiste, og fjern den i så fald
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (state.getBlock() instanceof ChestBlock) {
                ChestTracker.removeChest(world, pos);
            }
        });

        // Når serveren lukker ned: ryd al hukommelse så næste opstart starter frisk
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ChestTracker.clearAll();
            ChestCoordinator.clearAll();
        });
    }

    /**
     * Hjælpemetode der laver en Identifier med mod-id som præfix.
     * F.eks.: id("chest_thief_spawn_egg") → "chest_thief:chest_thief_spawn_egg"
     *
     * Bruges overalt i koden for at undgå at gentage MOD_ID manuelt.
     *
     * @param path den del af identifieren der kommer efter ":"
     * @return en fuld Identifier med chest_thief-præfix
     */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
