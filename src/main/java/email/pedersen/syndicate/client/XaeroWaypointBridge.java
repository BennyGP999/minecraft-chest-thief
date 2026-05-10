package email.pedersen.syndicate.client;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.MinimapProcessor;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.world.MinimapWorldManager;

/**
 * Bro til Xaero's Minimap — opretter og fjerner permanente waypoints ved Syndicate Bases.
 *
 * Klassen indlæses kun når Xaero's Minimap er installeret og et kald faktisk
 * foretages. For at undgå NoClassDefFoundError ved opstart (hvis Xaero mangler)
 * må alle kald til denne klasse ske inde i en lambda — aldrig via method reference —
 * så JVM'en kun loader Xaero-klasserne i det øjeblik lambdaen eksekveres, ikke
 * når den oprettes.
 *
 * Alle metoder skal kaldes på klient-tråden (via Minecraft.execute).
 *
 * API-kæde bekræftet ved bytecode-inspektion af Xaero's Minimap 26.1.2-25.3.12:
 *   XaeroMinimapSession.getCurrentSession()
 *     → getMinimapProcessor()
 *     → getSession()           (MinimapSession)
 *     → getWorldManager()      (MinimapWorldManager)
 *     → getCurrentWorld()      (MinimapWorld)
 *     → getCurrentWaypointSet()
 *     → add(Waypoint, boolean) / remove(Waypoint)
 */
public class XaeroWaypointBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("chest_thief/xaero");

    /**
     * Opretter et waypoint ved den givne position i Xaero's Minimap.
     *
     * Duplikattjek: eksisterende waypoints med identisk (X, Z) springes over,
     * så gentagne brug af /locate eller Syndicate Map ikke opretter dubletter.
     *
     * @param pos  basens position — alle tre koordinater (X, Y, Z) gemmes i waypoint'et
     * @param name navn vist i Xaero's Minimap-UI og waypoint-listen
     */
    public static void addWaypoint(BlockPos pos, String name) {
        // XaeroMinimapSession er null i menuen eller de første ticks efter login,
        // inden Xaero har initialiseret sin session — tidligt exit uden fejl.
        XaeroMinimapSession xms = XaeroMinimapSession.getCurrentSession();
        if (xms == null) {
            LOGGER.debug("Xaero waypoint for '{}' ved {} sprunget over — ingen aktiv session", name, pos);
            return;
        }

        // getMinimapProcessor() → getSession() giver os adgang til verdensmanageren.
        // Kæden afspejler præcis hvad TemporaryWaypointHandler gør internt.
        MinimapProcessor processor = xms.getMinimapProcessor();
        MinimapSession session = processor.getSession();
        MinimapWorldManager worldManager = session.getWorldManager();

        // getCurrentWorld() returnerer null de første få ticks efter chunk load,
        // inden Xaero har registreret dimensionen.
        MinimapWorld world = worldManager.getCurrentWorld();
        if (world == null) {
            LOGGER.debug("Xaero waypoint for '{}' ved {} sprunget over — ingen aktiv minimap-verden", name, pos);
            return;
        }

        // getCurrentWaypointSet() giver det aktive sæt for den aktuelle dimension.
        WaypointSet waypointSet = world.getCurrentWaypointSet();
        if (waypointSet == null) return;

        // Duplikattjek på (X, Z) — Y ignoreres fordi bases kan ligge på forskellig
        // højde, men vi aldrig ønsker to waypoints til samme bunker.
        for (Waypoint existing : waypointSet.getWaypoints()) {
            if (existing.getX() == pos.getX() && existing.getZ() == pos.getZ()) {
                LOGGER.debug("Xaero waypoint for '{}' ved {} sprunget over — dublet fundet", name, pos);
                return;
            }
        }

        // Opret waypoint med DARK_RED farve og initialen "S" (Syndicate).
        // temporary=false: waypoint'et gemmes på disk og overlever genstart.
        // global=false:    waypoint'et er kun synligt i den aktuelle dimension.
        Waypoint wp = new Waypoint(
            pos.getX(), pos.getY(), pos.getZ(),
            name,
            "S",
            WaypointColor.DARK_RED,
            WaypointPurpose.NORMAL,
            false,
            false
        );
        // add(wp, true): true = tilføj øverst i listen
        waypointSet.add(wp, true);
        LOGGER.info("Xaero waypoint '{}' oprettet ved {}", name, pos);
    }

    /**
     * Fjerner et waypoint ved den givne (X, Z)-position fra Xaero's Minimap.
     * Kaldes når en Syndicate Base fjernes fra registret (fx fordi alle dens kister
     * er ødelagt af TNT), så minimap'et ikke viser en pil til et tomt hul.
     *
     * Matcher kun på (X, Z) — samme konvention som addWaypoint's duplikattjek.
     *
     * @param pos  basens position — X og Z bruges til at finde waypoint'et
     */
    public static void removeWaypoint(BlockPos pos) {
        XaeroMinimapSession xms = XaeroMinimapSession.getCurrentSession();
        if (xms == null) return;

        MinimapProcessor processor = xms.getMinimapProcessor();
        MinimapSession session = processor.getSession();
        MinimapWorldManager worldManager = session.getWorldManager();

        MinimapWorld world = worldManager.getCurrentWorld();
        if (world == null) return;

        WaypointSet waypointSet = world.getCurrentWaypointSet();
        if (waypointSet == null) return;

        // Indsaml matches i en separat liste inden sletning — man må ikke fjerne
        // elementer fra en Iterable mens man itererer over den (ConcurrentModificationException).
        java.util.List<Waypoint> toRemove = new java.util.ArrayList<>();
        for (Waypoint wp : waypointSet.getWaypoints()) {
            if (wp.getX() == pos.getX() && wp.getZ() == pos.getZ()) {
                toRemove.add(wp);
            }
        }

        for (Waypoint wp : toRemove) {
            waypointSet.remove(wp);
            LOGGER.info("Xaero waypoint '{}' fjernet (base ødelagt ved {})", wp.getName(), pos);
        }
    }
}
