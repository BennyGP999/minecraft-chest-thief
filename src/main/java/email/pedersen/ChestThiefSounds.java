package email.pedersen;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Registrerer alle brugerdefinerede lyde for Chest Thief-modden.
 * Hvert SoundEvent her svarer til én indgang i assets/chest_thief/sounds.json.
 * Selve lydfilerne (*.ogg) placeres i assets/chest_thief/sounds/.
 * For at tilføje egne lyde:
 *   1. Læg en .ogg-lydfil i: src/main/resources/assets/chest_thief/sounds/<navn>.ogg
 *   2. Opdatér sounds.json så den peger på filen i stedet for placeholder-lyden.
 *      Eksempel — erstat placeholder-eventet med filen:
 *        "open_chest": { "sounds": ["chest_thief:open_chest"] }
 *      Her er "chest_thief:open_chest" sti til assets/chest_thief/sounds/open_chest.ogg.
 * Bruges som singleton via static-felter — ingen instans nødvendig.
 * Kald ChestThiefSounds.init() i ChestThiefMod.onInitialize() for at aktivere registreringen.
 */
public class ChestThiefSounds {

    /**
     * Lyd der spilles fra mob'ens position når den åbner en kiste.
     * Skal give et fornemmelse af "opdagelse" eller lumsk begejstring.
     * Placeholder indtil egen .ogg tilføjes: entity.evoker.ambient
     */
    public static final SoundEvent OPEN_CHEST = register("open_chest");

    /**
     * Lyd der spilles fra mob'ens position når den stjæler et item.
     * Skal give et fornemmelse af grådig triumf.
     * Placeholder indtil egen .ogg tilføjes: entity.vindicator.celebrate
     */
    public static final SoundEvent STEAL_ITEM = register("steal_item");

    /**
     * Lyd der afspilles med jævne mellemrum mens tyven sniger sig væk (usynlig).
     * Skal lyde som et tilfreds grin eller fnisen — et auditivt hint til spilleren
     * om at tyven stadig er i nærheden med sit bytte.
     * Placeholder indtil egen .ogg tilføjes: entity.witch.ambient
     */
    public static final SoundEvent LEAVING = register("leaving");

    /**
     * Registrerer et SoundEvent med det givne navn i Minecrafts lyd-register.
     * Identifier-formatet "chest_thief:<navn>" svarer til indgangen i sounds.json
     * og til lydfilen assets/chest_thief/sounds/<navn>.ogg.
     *
     * @param name lydnavnet, f.eks. "open_chest"
     * @return det registrerede SoundEvent-objekt
     */
    private static SoundEvent register(String name) {
        Identifier id = Identifier.fromNamespaceAndPath("chest_thief", name);
        return Registry.register(
                BuiltInRegistries.SOUND_EVENT,
                id,
                SoundEvent.createVariableRangeEvent(id)
        );
    }

    /**
     * Kaldes ved mod-initialisering for at sikre at klassen indlæses
     * og alle SoundEvents registreres.
     * Selve registreringen sker i de statiske feltinitialiserings-blokke ovenfor —
     * denne metode udløser blot klassens indlæsning.
     */
    public static void init() {
        ChestThiefMod.LOGGER.info("Registered Chest Thief sounds");
    }
}
