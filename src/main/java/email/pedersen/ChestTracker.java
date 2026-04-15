package email.pedersen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holder styr på alle kiste-positioner i spillet — én liste pr. dimension.
 * I stedet for at scanne alle blokke i verdenen (ekstremt langsomt og dyrt),
 * vedligeholder ChestTracker en liste over kendte kiste-positioner.
 * Listen opdateres via begivenheder:
 *   - En kiste-BlockEntity sættes op i verdenen → tilføj (via ChestBlockEntityMixin)
 *   - En spiller ødelægger en kiste → fjern (via ChestThiefMod)
 * Kister er organiseret pr. dimension (Oververdenen, Nether, End er separate verdener).
 * Nøglen er dimensionens tekststreng, f.eks. "minecraft:overworld".
 * Trådsikkerhed:
 *   CHESTS_BY_DIMENSION er et ConcurrentHashMap så den ydre map er trådsikker.
 *   De indre sæt er Collections.synchronizedSet() for at beskytte mod samtidige ændringer
 *   (kan ske under chunk-indlæsning på worker-tråde).
 */
public class ChestTracker {

    /**
     * Kiste-positioner grupperet efter dimension.
     * Nøgle: dimensionens id som tekst (f.eks. "minecraft:overworld")
     * Værdi: et sæt af BlockPos-objekter (hver kiste er én position)
     * HashSet bruges fordi:
     *   - add() og remove() er O(1) — konstant tid uanset størrelse
     *   - Duplikater ignoreres automatisk (vigtig fordi setLevel() kan kaldes flere gange)
     *   - findNearest() kræver O(n) iteration, men n er antal kister — langt færre end blokke
     */
    private static final Map<String, Set<BlockPos>> CHESTS_BY_DIMENSION = new ConcurrentHashMap<>();

    /** Delt tomt sæt til findNearest() uden eksklusioner — undgår at oprette nyt objekt hvert kald. */
    private static final Set<BlockPos> EMPTY_SET = Collections.emptySet();

    /**
     * Registrerer en kiste-position i trackeren.
     * Kaldes fra ChestBlockEntityMixin når en kiste-BlockEntity sættes op i verdenen.
     * pos.immutable() laver en fast kopi af positionen (BlockPos kan være midlertidig).
     * @param level verdenen kisten befinder sig i
     * @param pos   kistens position i verdenen
     */
    public static void addChest(Level level, BlockPos pos) {
        String dim = getDimensionKey(level);
        // computeIfAbsent: opret et nyt sæt for dimensionen hvis det ikke allerede eksisterer
        CHESTS_BY_DIMENSION.computeIfAbsent(dim, k -> Collections.synchronizedSet(new HashSet<>())).add(pos.immutable());
    }

    /**
     * Fjerner en kiste-position fra trackeren.
     * Kaldes når en spiller ødelægger en kiste (via ChestThiefMod),
     * og når en kiste viser sig at være forsvundet (via FindAndStealFromChestGoal).
     * Hvis det var den sidste kiste i dimensionen, fjernes dimensionens sæt også.
     * @param level verdenen kisten befandt sig i
     * @param pos   kistens position
     */
    public static void removeChest(Level level, BlockPos pos) {
        String dim = getDimensionKey(level);
        Set<BlockPos> chests = CHESTS_BY_DIMENSION.get(dim);
        if (chests != null) {
            chests.remove(pos);
            if (chests.isEmpty()) {
                CHESTS_BY_DIMENSION.remove(dim); // ryd dimensionen op for at spare hukommelse
            }
        }
    }

    /**
     * Finder den nærmeste kendte kiste inden for en given radius.
     * Ingen eksklusioner — finder den absolut nærmeste kiste.
     * @param level     verdenen der søges i
     * @param origin    udgangspunktet for søgningen (typisk mob'ens position)
     * @param maxRadius maksimal søgeradius i blokke
     * @return positionen på den nærmeste kiste, eller null hvis ingen blev fundet
     */
    @Nullable
    public static BlockPos findNearest(Level level, BlockPos origin, double maxRadius) {
        return findNearest(level, origin, maxRadius, EMPTY_SET);
    }

    /**
     * Finder den nærmeste kendte kiste inden for en given radius og vertikal grænse, med eksklusioner.
     * Bruges af FindAndStealFromChestGoal for at undgå:
     *   - Udtømte kister (exhaustedChests)
     *   - Kister andre mobs allerede er på vej til (ChestCoordinator)
     * Algoritmen itererer over alle kendte kister i dimensionen (O(n))
     * og finder den med mindste kvadreret afstand inden for maxRadius.
     * Kvadreret afstand bruges for at undgå kvadratrod-beregning (Math.sqrt er langsom).
     * Det vertikale filter (maxVerticalDist) filtrerer kister der er for langt over eller
     * under mob'en — uanset om de er inden for den horisontale radius. Dette forhindrer
     * at mob'en detekterer dybt nedgravede dungeons og spolerer opdagelsesoplevelsen,
     * mens spillerbaser tæt under terræn stadig kan opdages.
     * @param level          verdenen der søges i
     * @param origin         udgangspunktet for søgningen
     * @param maxRadius      maksimal søgeradius i blokke (3D)
     * @param maxVerticalDist maksimal lodret afstand i blokke (filtrerer for høje/lave kister)
     * @param exclude        sæt af positioner der skal springes over
     * @return positionen på den nærmeste kiste (ikke i exclude), eller null
     */
    @Nullable
    public static BlockPos findNearest(Level level, BlockPos origin, double maxRadius, int maxVerticalDist, Set<BlockPos> exclude) {
        String dim = getDimensionKey(level);
        Set<BlockPos> chests = CHESTS_BY_DIMENSION.get(dim);
        if (chests == null || chests.isEmpty()) {
            return null; // ingen kister i denne dimension
        }

        double maxDistSq = maxRadius * maxRadius; // kvadreret radius (undgår sqrt)
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;  // start med "uendeligt langt væk"

        // synchronized-blok er påkrævet: Collections.synchronizedSet beskytter kun
        // individuelle operationer, ikke iteration. Chunk-load på worker-tråde kan
        // kalde addChest() mens vi itererer — uden lock giver det ConcurrentModificationException.
        synchronized (chests) {
            for (BlockPos pos : chests) {
                if (exclude.contains(pos)) continue; // spring ekskluderede kister over
                if (Math.abs(pos.getY() - origin.getY()) > maxVerticalDist) continue; // for langt over/under
                double distSq = origin.distSqr(pos);
                if (distSq <= maxDistSq && distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearest = pos;
                }
            }
        }

        return nearest;
    }

    /**
     * Finder den nærmeste kendte kiste inden for en given radius, med eksklusioner.
     * Bruger ingen vertikal begrænsning — til intern brug og leash-detektion.
     * @param level     verdenen der søges i
     * @param origin    udgangspunktet for søgningen
     * @param maxRadius maksimal søgeradius i blokke
     * @param exclude   sæt af positioner der skal springes over
     * @return positionen på den nærmeste kiste (ikke i exclude), eller null
     */
    @Nullable
    public static BlockPos findNearest(Level level, BlockPos origin, double maxRadius, Set<BlockPos> exclude) {
        return findNearest(level, origin, maxRadius, Integer.MAX_VALUE, exclude);
    }

    /**
     * Scanner indlæste chunks for eksisterende kister.
     * Denne metode er en placeholder — vi scanner ikke aktivt.
     * I stedet håndteres eksisterende kister af ChestBlockEntityMixin,
     * der fanger kister når de indlæses fra disk (chunk-load).
     * @param level den server-verden der skal scannes (ubrugt p.t.)
     */
    public static void scanLoadedChunks(ServerLevel level) {
        // Ingen aktiv scanning — ChestBlockEntityMixin håndterer chunk-load events
    }

    /**
     * Fjerner alle kister i en specifik dimension fra trackeren.
     * Kan bruges hvis en dimension unloades.
     * @param level verdenen/dimensionen der skal ryddes
     */
    public static void clearDimension(Level level) {
        CHESTS_BY_DIMENSION.remove(getDimensionKey(level));
    }

    /**
     * Tæller antallet af kendte kister inden for en given radius og vertikal grænse.
     * Bruges af ChestThiefEntity.checkChestThiefSpawnRules() til at afgøre om der
     * er nok kister i området til at det er værd for en tyv at spawne der.
     * Jo flere kister i nærheden, jo mere attraktivt er området for en tyv.
     * Bruger samme vertikale filter som findNearest() for konsistens.
     * @param level          verdenen der søges i
     * @param origin         udgangspunktet for søgningen (typisk spawn-positionen)
     * @param maxRadius      maksimal søgeradius i blokke
     * @param maxVerticalDist maksimal lodret afstand i blokke
     * @return antal kister inden for radius og vertikalt filter
     */
    public static int countNearby(Level level, BlockPos origin, double maxRadius, int maxVerticalDist) {
        String dim = getDimensionKey(level);
        Set<BlockPos> chests = CHESTS_BY_DIMENSION.get(dim);
        if (chests == null || chests.isEmpty()) return 0;

        double maxDistSq = maxRadius * maxRadius;
        int count = 0;

        synchronized (chests) {
            for (BlockPos pos : chests) {
                if (Math.abs(pos.getY() - origin.getY()) > maxVerticalDist) continue;
                if (origin.distSqr(pos) <= maxDistSq) count++;
            }
        }
        return count;
    }

    /**
     * Rydder al data i trackeren på tværs af alle dimensioner.
     * Kaldes når serveren stopper, så der ikke er gammelt data ved næste opstart.
     */
    public static void clearAll() {
        CHESTS_BY_DIMENSION.clear();
    }

    /**
     * Returnerer det samlede antal sporede kister på tværs af alle dimensioner.
     * Nyttig til debugging ("Checker: X kister tracked").
     * @return samlet antal kiste-positioner
     */
    public static int getTotalTrackedCount() {
        return CHESTS_BY_DIMENSION.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * Laver en tekstnøgle ud fra en verdens dimension, f.eks. "minecraft:overworld".
     * Bruges som nøgle i CHESTS_BY_DIMENSION-map'et.
     * @param level verdenen
     * @return dimensionens id som tekst
     */
    private static String getDimensionKey(Level level) {
        return level.dimension().identifier().toString();
    }
}
