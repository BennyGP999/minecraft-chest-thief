package email.pedersen.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Generisk hjælpeklasse til at indlæse og gemme JSON-konfigurationsfiler.
 *
 * Deles mellem alle moduler i modden (chestthief, syndicate, osv.) og kender
 * ikke til konkrete config-klasser — den arbejder udelukkende med GSON og filsystemet.
 *
 * Understøtter automatisk opgradering: nye felter med standardværdier tilføjes
 * filen automatisk ved opstart, fordi vi altid skriver filen tilbage til disk
 * efter indlæsning. Eksisterende værdier fra brugeren bevares.
 *
 * Typisk brug fra en konkret config-klasse:
 *   INSTANCE = JsonConfigLoader.load(MyConfig.class, path, MyConfig::new, logger);
 *   INSTANCE.validate();
 */
public class JsonConfigLoader {

    // setPrettyPrinting() sikrer at filen er læsbar for mennesker med indrykning og linjeskift.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Indlæser en JSON-konfigurationsfil og returnerer et objekt af typen T.
     *
     * Tre scenarier håndteres:
     *   1. Filen eksisterer og er gyldig JSON → deserialisér til T, skriv tilbage (auto-upgrade)
     *   2. Filen eksisterer men er korrupt     → brug standardværdier, log fejlen
     *   3. Filen eksisterer ikke               → opret T med standardværdier, skriv til disk
     *
     * Kalderen er ansvarlig for at kalde validate() på det returnerede objekt
     * for at sikre at alle værdier er inden for gyldige grænser.
     *
     * @param type           Class-objektet for T — bruges af GSON til deserialisering
     * @param path           Fuld sti til JSON-filen (typisk i Minecraft's config/-mappe)
     * @param defaultFactory Supplier der opretter en ny instans med standardværdier
     * @param logger         Logger fra det kaldende modul, så log-linjer angiver korrekt kilde
     * @return               Det indlæste eller nyoprettede config-objekt
     */
    public static <T> T load(Class<T> type, Path path, Supplier<T> defaultFactory, Logger logger) {
        T instance;

        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                instance = GSON.fromJson(json, type);

                // GSON returnerer null hvis JSON-filen er tom eller indeholder "null".
                if (instance == null) {
                    logger.warn("Config file was empty or null, using defaults: {}", path);
                    instance = defaultFactory.get();
                }

                // Skriv altid tilbage til fil efter indlæsning.
                // GSON serialiserer ALLE felter — inkl. nye felter med standardværdier
                // der ikke fandtes i den gamle fil. Det sikrer at filen altid afspejler
                // den aktuelle version af modden uden at brugeren skal gøre noget manuelt.
                Files.writeString(path, GSON.toJson(instance));
                logger.info("Loaded config from {}", path);
            } catch (Exception e) {
                logger.error("Failed to load config from {}, using defaults", path, e);
                instance = defaultFactory.get();
            }
        } else {
            // Filen eksisterer ikke — opret med standardværdier og gem til disk.
            instance = defaultFactory.get();
            try {
                Files.writeString(path, GSON.toJson(instance));
                logger.info("Created default config at {}", path);
            } catch (IOException e) {
                logger.error("Failed to write default config to {}", path, e);
            }
        }

        return instance;
    }

    /**
     * Gemmer et vilkårligt objekt som formateret JSON til den angivne fil.
     * Bruges når en config-klasse selv ønsker at skrive til disk uden for den normale load-cyklus.
     *
     * @param config  Objektet der skal serialiseres
     * @param path    Filen der skrives til
     * @param logger  Logger fra det kaldende modul
     */
    public static void save(Object config, Path path, Logger logger) {
        try {
            Files.writeString(path, GSON.toJson(config));
        } catch (IOException e) {
            logger.error("Failed to save config to {}", path, e);
        }
    }
}
