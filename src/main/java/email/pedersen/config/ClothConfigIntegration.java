package email.pedersen.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod Menu-integration der forbinder config-skærmen med modmenuen.
 * Registreres som "modmenu"-entrypoint i fabric.mod.json og kaldes kun
 * på klienten hvis spilleren har Mod Menu installeret.
 *
 * Cloth Config er en soft dependency — cloth-config-klasser indlæses
 * aldrig direkte fra denne klasse. I stedet er referencen til
 * ClothConfigScreenBuilder pakket ind i en lambda, så JVM'en kun
 * loader ClothConfigScreenBuilder-klassen når lambda'en faktisk kaldes
 * (dvs. når brugeren åbner config-skærmen). Hvis spilleren har Mod Menu
 * men ikke Cloth Config, returneres blot null (ingen config-skærm).
 */
public class ClothConfigIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            // Cloth Config ikke installeret — ingen config-skærm.
            return parent -> null;
        }
        // Lambda-syntaks i stedet for method reference: JVM'en resolver
        // ClothConfigScreenBuilder som klasse først når lambda'en invokeres,
        // ikke ved oprettelse — dermed undgås NoClassDefFoundError hvis
        // cloth-config mangler.
        return parent -> ClothConfigScreenBuilder.build(parent);
    }
}
