package email.pedersen.chestthief.events;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Et Fabric-event der fyres når en Chest Thief afslutter sin afrejse med loot.
 *
 * Formålet med dette event er at afkoble chestthief-modulet fra syndicate-modulet.
 * ChestThiefEntity ved ikke noget om syndikatbaser — den fyrer blot dette event
 * og overlader det til lyttere (f.eks. SyndicateMod) at beslutte hvad der skal ske.
 *
 * Afhængighedspilen går kun én vej: syndicate lytter på chestthief's event.
 * chestthief importerer intet fra syndicate.
 *
 * Brug:
 *   // Registrér lytter (i SyndicateMod.onInitialize):
 *   ChestThiefDepartedEvent.EVENT.register((level, pos, loot) -> { ... });
 *
 *   // Fyr eventet (i ChestThiefEntity.tick når departTimer == 0):
 *   ChestThiefDepartedEvent.EVENT.invoker().onDeparted(level, pos, loot);
 */
public final class ChestThiefDepartedEvent {

    /**
     * Selve event-objektet. ArrayBacked betyder at alle registrerede lyttere
     * kaldes i den rækkefølge de er registreret — simpelt og forudsigeligt.
     * Lambdaen i midten er "kombinator-funktionen": den sammensætter alle
     * lyttere til ét samlet kald, som EventFactory kalder med invoker().
     */
    public static final Event<Callback> EVENT = EventFactory.createArrayBacked(
            Callback.class,
            listeners -> (level, pos, loot) -> {
                for (Callback listener : listeners) {
                    listener.onDeparted(level, pos, loot);
                }
            }
    );

    // Utility-klasse — ingen instanser
    private ChestThiefDepartedEvent() {}

    /**
     * Funktionel grænseflade som lyttere skal implementere.
     * Fabric kræver en @FunctionalInterface fordi EventFactory bruger den
     * til at generere proxy-objekter via reflection.
     *
     * @param level  Dimensionen tyven befinder sig i — bruges til at slå baser op per dimension
     * @param pos    Tyrens position ved afrejsetidspunktet — bruges til at finde nærmeste base
     * @param loot   Kopi af det stjålne loot — listen er uforanderlig og sikkert at iterere
     */
    @FunctionalInterface
    public interface Callback {
        void onDeparted(ServerLevel level, BlockPos pos, List<ItemStack> loot);
    }
}
