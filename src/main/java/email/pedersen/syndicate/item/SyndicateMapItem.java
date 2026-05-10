package email.pedersen.syndicate.item;

import email.pedersen.syndicate.SyndicateBase;
import email.pedersen.syndicate.SyndicateBaseManager;
import email.pedersen.syndicate.SyndicateMod;
import email.pedersen.syndicate.config.SyndicateConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Syndikatskort — et forbrugsvare-item der afslører positionen af nærmeste syndikats-base.
 *
 * Spilleren køber kortet af en Wandering Trader (S12) og bruger det (højreklik).
 * Kortet skabes i det øjeblik det bruges, med centrum på basens region-celle
 * (ikke basens præcise position) — spilleren skal stadig opsøge overfladeindgangen.
 *
 * Implementering:
 *   1. use() kaldes server-side ved højreklik
 *   2. Find nærmeste aktive base via SyndicateBaseManager
 *   3. Opret et standard Minecraft-kort (skala 2 = 1:4, 512×512 blokke) centreret
 *      på basens overfladeindgang
 *   4. Tilføj TARGET_X-dekoration ved basens nøjagtige position
 *   5. Erstat kortet i spillerens hånd med det fyldte kortstack
 *
 * Kortet er single-use: det originale (tomme) kort erstattes af et unikt kort-ID.
 * Genbruger vanilla MapItem.create() og MapItemSavedData.addTargetDecoration() —
 * ingen custom rendering nødvendig.
 *
 * Hvis ingen base er aktiv i dimensionen vises en besked i chat og kortet forbruges ikke.
 */
public class SyndicateMapItem extends Item {

    /**
     * Kortskala 2 = 1:4 (512×512 blokke pr. kort).
     * Tilstrækkelig til at vise et helt region-felt (typisk 1024×1024 blokke)
     * og give spilleren en fornemmelse af retningen — men ikke nøjagtig placering.
     * Skala 0 = 1:1 (128×128 blokke) er for præcis; skala 3 = 1:8 er for unøjagtig.
     */
    private static final byte MAP_SCALE = 2;

    /**
     * Constructor — properties sættes af registreringen i SyndicateMod.
     *
     * @param properties item-egenskaber (maxStackSize, ID osv.)
     */
    public SyndicateMapItem(Properties properties) {
        super(properties);
    }

    /**
     * Håndterer højreklik med kortet i hånden.
     *
     * Logikken er opdelt i:
     *   1. Server-only check (klienten kalder også use() men level er ikke ServerLevel der)
     *   2. Find nærmeste base — ingen base → advar spilleren og forbrugsneutral (PASS)
     *   3. Opret Minecraft-kort med MapItem.create()
     *   4. Tilføj TARGET_X-dekoration ved basens position via MapItemSavedData.addTargetDecoration()
     *   5. Sæt det nye kort i spillerens hånd og returnér SUCCESS
     *
     * InteractionResult.SUCCESS signalerer til Minecraft at aktionen er gennemført og
     * spillerens arm-animation skal spilles. PASS betyder "intet skete".
     *
     * @param level   den verden spilleren er i
     * @param player  spilleren der bruger kortet
     * @param hand    hvilken hånd der holder kortet (main eller off)
     * @return SUCCESS hvis et kort blev genereret, PASS hvis ingen base eksisterer
     */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS;

        ServerLevel serverLevel = (ServerLevel) level;
        SyndicateBase base = SyndicateBaseManager.findNearest(serverLevel, player.blockPosition());

        if (base == null) {
            // Ingen aktiv base i denne dimension — ingen effekt
            SyndicateMod.LOGGER.debug("Syndicate Map used by {} but no active base found in this dimension", player.getName().getString());
            return InteractionResult.PASS;
        }

        BlockPos basePos = base.getPosition();

        // Opret waypoint i Xaero's Minimap hvis installeret OG spilleren har slået det til i config.
        // Standard er fra — baserne er hemmelige og vises ikke på minimap uden aktivt tilvalg.
        if (SyndicateConfig.getInstance().isCreateXaeroWaypoints()
                && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                && FabricLoader.getInstance().isModLoaded("xaerominimap")) {
            net.minecraft.client.Minecraft.getInstance().execute(
                () -> email.pedersen.syndicate.client.XaeroWaypointBridge
                    .addWaypoint(basePos, "Syndicate Base"));
        }

        // Opret et nyt Minecraft-kort centreret på basens overfladeindgang.
        // trackingPosition=true:  spillerens position vises som markør på kortet.
        // unlimitedTracking=true: markøren vises selv når spilleren er uden for kortets
        //                         udsnit — nødvendigt for at navigere til basen fra lang afstand,
        //                         præcis som vanilla's treasure maps opfører sig.
        ItemStack mapStack = MapItem.create(serverLevel, basePos.getX(), basePos.getZ(),
                MAP_SCALE, true, true);

        // Tilføj en TARGET_X-markør ved basens position.
        // Nøglen "syndicate_base" er unik pr. kort — to marker med samme nøgle erstatter hinanden.
        // addTargetDecoration() er statisk og skriver direkte til kortets MapItemSavedData.
        MapItemSavedData.addTargetDecoration(mapStack, basePos, "syndicate_base",
                MapDecorationTypes.TARGET_X);

        // Erstat kortet i spillerens hånd med det nye, fyldte kort.
        // setItemInHand() virker for begge hænder og håndterer ItemStack-størrelse korrekt.
        player.setItemInHand(hand, mapStack);

        SyndicateMod.LOGGER.debug("Syndicate Map activated by {} — base at {}", player.getName().getString(), basePos);
        return InteractionResult.SUCCESS;
    }
}
