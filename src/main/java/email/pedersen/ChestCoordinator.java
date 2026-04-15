package email.pedersen;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Koordinerer hvilke kister der er "optaget" af hvilke Chest Thieves.
 * Problemet uden koordinering:
 *   Hvis der er 5 Chest Thieves og 1 kiste, ville alle 5 gå til den samme kiste.
 *   Det ser dumt ud og er ineffektivt.
 * Løsningen:
 *   Når en Chest Thief vælger en kiste og er tæt nok på den, registrerer den
 *   et "krav" her. Andre Chest Thieves der søger efter kister, tjekker først
 *   getClaimedPositions() og undgår kister der allerede er optaget.
 * Krav registreres pr. mob-UUID (en unik ID alle entiteter har).
 * Kravet frigives automatisk når mob'en finder en ny kiste, dør, eller målet stopper.
 * Trådsikkerhed:
 *   ConcurrentHashMap bruges fordi CLAIMS kan tilgås fra AI-tråde og event-tråde.
 */
public class ChestCoordinator {

    /**
     * Map over krav: mob'ens UUID → den kistes position der er krævet.
     * En mob kan kun kræve én kiste ad gangen.
     *
     * ConcurrentHashMap er trådsikker: flere tråde kan læse/skrive samtidig
     * uden at data bliver ødelagt (i modsætning til en almindelig HashMap).
     */
    private static final Map<UUID, BlockPos> CLAIMS = new ConcurrentHashMap<>();

    /**
     * Registrerer at en mob har "krav" på en kiste.
     * Erstatter automatisk et eventuelt tidligere krav fra samme mob.
     * Kaldes fra FindAndStealFromChestGoal når mob'en er inden for 4 blokke af kisten.
     *
     * @param mobId UUID på den mob der kræver kisten
     * @param pos   positionen på kisten der kræves
     */
    public static void claim(UUID mobId, BlockPos pos) {
        CLAIMS.put(mobId, pos.immutable()); // immutable() = lav en fast kopi af positionen
    }

    /**
     * Frigiver kravet for en mob.
     * Kisten er nu "ledig" igen for andre Chest Thieves.
     * Kaldes når:
     *   - Mob'en har tømt kisten og leder efter en ny
     *   - Mob'ens mål stopper (solnedgang, død, snor)
     *   - Serveren stopper (via clearAll())
     *
     * @param mobId UUID på den mob hvis krav skal frigives
     */
    public static void release(UUID mobId) {
        CLAIMS.remove(mobId);
    }

    /**
     * Returnerer alle positioner der i øjeblikket er krævet af en mob.
     * Bruges af FindAndStealFromChestGoal til at bygge "undgå"-listen
     * inden den søger efter den nærmeste kiste.
     * Returnerer ConcurrentHashMap's values-view direkte — ingen kopi nødvendig,
     * da ConcurrentHashMap garanterer CME-fri iteration uden lås.
     *
     * @return en live-view af alle aktuelt krævede kiste-positioner
     */
    public static Collection<BlockPos> getClaimedPositions() {
        return CLAIMS.values();
    }

    /**
     * Rydder alle krav.
     * Kaldes når serveren stopper, så der ikke er gammelt data ved næste opstart.
     */
    public static void clearAll() {
        CLAIMS.clear();
    }
}
