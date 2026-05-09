package email.pedersen.syndicate;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holder styr på alle syndicate-kiste-positioner i spillet — én liste pr. dimension.
 *
 * Fungerer som ChestTracker, men sporer kun blokke af typen SyndicateChestBlockEntity.
 * Tyvene afleverer stjålet loot til den nærmeste syndicate-kiste (ikke til en base som sådan),
 * hvilket betyder at spillere frit kan opstille kister i verden og derved tiltrække tyvenes loot.
 *
 * Trådsikkerhed: samme mønster som ChestTracker — ConcurrentHashMap ydre niveau,
 * Collections.synchronizedSet() indre niveau, synchronized-blok ved iteration.
 */
public class SyndicateChestTracker {

    /**
     * Kiste-positioner grupperet efter dimension.
     * Nøgle: dimensionens id som tekst (f.eks. "minecraft:overworld")
     * Værdi: trådsikkert sæt af BlockPos-objekter
     */
    private static final Map<String, Set<BlockPos>> CHESTS_BY_DIMENSION = new ConcurrentHashMap<>();

    /** Delt tomt sæt til findNearest() uden eksklusioner. */
    private static final Set<BlockPos> EMPTY_SET = Collections.emptySet();

    /**
     * Registrerer en syndicate-kiste-position.
     * Kaldes fra SyndicateChestBlockEntity.setLevel() server-side.
     *
     * @param level verdenen kisten befinder sig i
     * @param pos   kistens position
     */
    public static void addChest(Level level, BlockPos pos) {
        String dim = getDimensionKey(level);
        CHESTS_BY_DIMENSION
                .computeIfAbsent(dim, k -> Collections.synchronizedSet(new HashSet<>()))
                .add(pos.immutable());
    }

    /**
     * Fjerner en syndicate-kiste-position.
     * Kaldes fra SyndicateChestBlockEntity.setRemoved() server-side.
     *
     * @param level verdenen kisten befandt sig i
     * @param pos   kistens position
     */
    public static void removeChest(Level level, BlockPos pos) {
        String dim = getDimensionKey(level);
        Set<BlockPos> chests = CHESTS_BY_DIMENSION.get(dim);
        if (chests != null) {
            chests.remove(pos);
            if (chests.isEmpty()) {
                CHESTS_BY_DIMENSION.remove(dim);
            }
        }
    }

    /**
     * Finder den nærmeste syndicate-kiste inden for en given radius.
     *
     * @param level     verdenen der søges i
     * @param origin    udgangspunktet for søgningen (typisk tyvens position)
     * @param maxRadius maksimal søgeradius i blokke
     * @return positionen på den nærmeste syndicate-kiste, eller null hvis ingen fundet
     */
    @Nullable
    public static BlockPos findNearest(Level level, BlockPos origin, double maxRadius) {
        String dim = getDimensionKey(level);
        Set<BlockPos> chests = CHESTS_BY_DIMENSION.get(dim);
        if (chests == null || chests.isEmpty()) return null;

        double maxDistSq = maxRadius * maxRadius;
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        // synchronized-blok kræves: chunk-load events på worker-tråde kan kalde addChest()
        // mens vi itererer — uden lock giver det ConcurrentModificationException.
        synchronized (chests) {
            for (BlockPos pos : chests) {
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
     * Rydder al data i trackeren på tværs af alle dimensioner.
     * Kaldes ved server-stop for at undgå gammelt data ved næste opstart.
     */
    public static void clearAll() {
        CHESTS_BY_DIMENSION.clear();
    }

    /**
     * Laver en tekstnøgle ud fra en verdens dimension, f.eks. "minecraft:overworld".
     *
     * @param level verdenen
     * @return dimensionens id som tekst
     */
    private static String getDimensionKey(Level level) {
        return level.dimension().identifier().toString();
    }
}
