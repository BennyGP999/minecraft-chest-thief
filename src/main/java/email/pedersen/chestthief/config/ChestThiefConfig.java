package email.pedersen.chestthief.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import email.pedersen.chestthief.ChestThiefMod;
import email.pedersen.config.JsonConfigLoader;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Håndterer konfiguration for Chest Thief-modden.
 * To config-filer læses fra Minecraft's config/-mappe ved opstart:
 *   chest_thief_config.json — generelle indstillinger:
 *     chestInteractionIntervalTicks: ticks mellem hvert stjæl (20 ticks = 1 sekund)
 *     chestDetectionRadius: søgeradius for kister i blokke
 *     stealOnlyListedItems: om kun items fra values-listen må stjæles (se nedenfor)
 *     spawnBiomes: liste over biomer mob'en spawner i
 *     spawnWeight: spawning-hyppighed (koen er 8; højere = mere almindelig)
 *     spawnMinGroup/spawnMaxGroup: min/max antal i en gruppe ved spawn
 *   chest_thief_values.json — item-prioriteter til tjæleriet:
 *     Et map fra item-id (f.eks. "minecraft:diamond") til en heltalsprioritetsværdi.
 *     Højere værdi = stjæles først.
 *     stealOnlyListedItems = false (standard):
 *       Items der ikke er i listen tildeles automatisk minimumsværdi 1,
 *       så alt i kisten kan stjæles. Listen styrer kun prioritetsrækkefølgen.
 *     stealOnlyListedItems = true:
 *       Kun items der eksplicit er oplistet i chest_thief_values.json stjæles.
 *       Alt andet ignoreres fuldstændigt — nyttigt til servere der kun vil
 *       beskytte specifikke items mod Chest Thieves.
 * Begge filer oprettes automatisk med standardværdier hvis de ikke eksisterer.
 * Implementeret som singleton: én instans deles af hele mods'en.
 * Brug ChestThiefConfig.getInstance() for at hente konfigurationen.
 */
public class ChestThiefConfig {

    private static ChestThiefConfig INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // --- Config fields ---
    /** How often (in ticks) the Chest Thief interacts with a chest. 20 ticks = 1 second. */
    private int chestInteractionIntervalTicks = 60; // 3 seconds

    /** How far (in blocks) the Chest Thief can detect a chest. */
    private double chestDetectionRadius = 100;

    /**
     * Maksimal lodret afstand (i blokke) fra mob'en til en kiste for at den kan detekteres.
     * Filtrerer kister der er for langt over eller under mob'en.
     * Tradeoff:
     *   Lav værdi (f.eks. 8):  finder ikke dybt skjulte baser, men heller ikke vanilla dungeons
     *   Høj værdi (f.eks. 40): finder spillerbaser langt under terræn, men afslører også dungeons
     * Standard: 16 blokke — finder kister i kældre og lavvandede spillerbaser,
     * men ikke forseglade dungeons der typisk ligger 20-40 blokke under terræn.
     */
    private int chestDetectionMaxVerticalDist = 16;

    /** Which biomes the Chest Thief spawns in (resource location strings). */
    private List<String> spawnBiomes = List.of(
            // ── Sletter og enge ───────────────────────────────────────────────
            "minecraft:plains",
            "minecraft:sunflower_plains",
            "minecraft:meadow",
            // ── Skove ─────────────────────────────────────────────────────────
            "minecraft:forest",
            "minecraft:flower_forest",
            "minecraft:birch_forest",
            "minecraft:old_growth_birch_forest",
            "minecraft:dark_forest",
            "minecraft:pale_garden",
            // ── Jungle ────────────────────────────────────────────────────────
            "minecraft:jungle",
            "minecraft:sparse_jungle",
            "minecraft:bamboo_jungle",
            // ── Taiga ─────────────────────────────────────────────────────────
            "minecraft:taiga",
            "minecraft:old_growth_pine_taiga",
            "minecraft:old_growth_spruce_taiga",
            "minecraft:snowy_taiga",
            // ── Sne og kulde ──────────────────────────────────────────────────
            "minecraft:snowy_plains",
            "minecraft:ice_spikes",
            "minecraft:snowy_slopes",
            "minecraft:grove",
            "minecraft:frozen_peaks",
            "minecraft:jagged_peaks",
            // ── Bjerge og stenede ─────────────────────────────────────────────
            "minecraft:windswept_hills",
            "minecraft:windswept_gravelly_hills",
            "minecraft:windswept_forest",
            "minecraft:stony_peaks",
            "minecraft:stony_shore",
            // ── Ørken og savanne ──────────────────────────────────────────────
            "minecraft:desert",
            "minecraft:savanna",
            "minecraft:savanna_plateau",
            "minecraft:windswept_savanna",
            // ── Badlands ──────────────────────────────────────────────────────
            "minecraft:badlands",
            "minecraft:wooded_badlands",
            "minecraft:eroded_badlands",
            // ── Sump ──────────────────────────────────────────────────────────
            "minecraft:swamp",
            "minecraft:mangrove_swamp",
            // ── Strand ────────────────────────────────────────────────────────
            "minecraft:beach",
            "minecraft:snowy_beach",
            // ── Specielle ─────────────────────────────────────────────────────
            "minecraft:cherry_grove",
            "minecraft:mushroom_fields"
    );

    /**
     * Minimum antal kister inden for detektionsradius for at en tyv må spawne naturligt.
     * Sikrer at tyve primært dukker op i nærheden af bebyggelser og spillerbaser
     * frem for i øde vildmark uden noget at stjæle.
     *   0 = ingen krav — tyve spawner overalt som normale monstre
     *   2 = kræver mindst 2 kister i nærheden (standard)
     *   5 = kræver en hel lille base — kun tæt beboede områder tiltrækker tyve
     * Bruger samme detektionsradius og vertikale grænse som selve tyverilogikken.
     */
    private int spawnMinNearbyChests = 2;

    /** Spawn weight (higher = more common). For reference, cows are ~8. */
    private int spawnWeight = 20;

    /** Minimum group size when spawning. */
    private int spawnMinGroup = 1;

    /** Maximum group size when spawning. */
    private int spawnMaxGroup = 2;

    /**
     * Styrer om Chest Thief kun stjæler items der er oplistet i chest_thief_values.json.
     *   false (standard): Alle items kan stjæles. Items der ikke er i listen tildeles
     *                     automatisk minimumsværdi 1, så de stadig kan stjæles —
     *                     listen styrer kun hvilke items der prioriteres højest.
     *   true: Kun items der eksplicit er oplistet i chest_thief_values.json stjæles.
     *         Unlisted items ignoreres helt. Nyttigt til servere der kun vil lade
     *         Chest Thieves stjæle bestemte items (f.eks. kun diamanter og elytraer).
     */
    // Gson sætter feltet via reflection ved deserialisering — IntelliJ ser ikke dette og advarer fejlagtigt.
    @SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
    private final boolean stealOnlyListedItems = false;

    /**
     * Sandsynlighed (0.0–1.0) for at Chest Thief paniker i stedet for at slå igen
     * når den angribes om dagen.
     *   0.0 = aldrig panik — slår altid igen
     *   0.6 = 60% chance for panik, 40% chance for at slå igen (standard)
     *   1.0 = altid panik — slår aldrig igen om dagen
     * Under panik sprinter mob'en væk fra angriberen og taber ét tilfældigt item
     * fra sin beholdning. De resterende items kan fås ved at slå den ihjel.
     */
    private double panicChance = 0.4;

    /**
     * Antal ticks mob'en sprinter væk i panik-tilstand. 20 ticks = 1 sekund.
     * Standard: 80 ticks = 4 sekunder.
     */
    private int panicDurationTicks = 200;

    /**
     * Antal ticks mob'en går væk fra den sidst besøgte kiste når beholdningen er fuld.
     * 20 ticks = 1 sekund. Standard: 200 ticks = 10 sekunder.
     * Bruges af LeaveAreaGoal, som aktiveres automatisk når mob'en har fyldt
     * alle 5 bæreslots og ikke kan stjæle mere.
     */
    private int leaveDurationTicks = 200;

    /**
     * Maksimalt antal item-staks mob'en kan bære på én gang.
     * Når alle slots er fyldt, stopper mob'en med at stjæle og går væk fra gerningsstedet.
     * Items droppes ved død. Standard: 5.
     */
    private int maxCarrySlots = 5;

    /**
     * Antal ticks berserker-mode varer hvis der ikke kommer nye provokationer.
     * Ved nye angreb nulstilles timeren. 20 ticks = 1 sekund. Standard: 300 = 15 sek.
     */
    private int berserkDurationTicks = 300;

    /**
     * Hastighedsmultiplikator under berserker-mode (ADD_MULTIPLIED_BASE).
     * 1.5 = +50% hastighed oveni base-hastighed (0.23 → 0.345). Standard: 1.5.
     */
    private double berserkSpeedMultiplier = 1.5;

    /**
     * Radius i blokke inden for hvilken BerserkTargetGoal scanner efter nye mål.
     * Større end normal FOLLOW_RANGE (35) for at forhindre at spilleren "løber væk". Standard: 50.
     */
    private double berserkFollowRange = 50.0;

    /**
     * Flad hastighedsbonus om natten (ADD_VALUE — lægges direkte til base-hastighed).
     * 0.08 → 0.23 + 0.08 = 0.31 (ca. 35% hurtigere end normalt). Standard: 0.08.
     */
    private double nightSpeedBonus = 0.12;

    /** Korteste usynlighedsperiode om natten i ticks. Standard: 60 = 3 sekunder. */
    private int stealthMinTicks = 60;

    /** Længste usynlighedsperiode om natten i ticks. Standard: 140 = 7 sekunder. */
    private int stealthMaxTicks = 140;

    /** Korteste cooldown mellem usynlighedsperioder i ticks. Standard: 200 = 10 sekunder. */
    private int stealthCooldownMinTicks = 200;

    /** Længste cooldown mellem usynlighedsperioder i ticks. Standard: 400 = 20 sekunder. */
    private int stealthCooldownMaxTicks = 400;

    /**
     * Sandsynlighed (0.0–1.0) for at usynlighed aktiveres når cooldown er udløbet og betingelserne er opfyldt.
     * 0.75 = 75% chance pr. forsøg. Standard: 0.75.
     */
    private double stealthChance = 0.85;

    /**
     * Antal ticks med fuld beholdning inden tyven begynder at drage bort.
     * Giver spilleren et vindue til at angribe inden loot er tabt.
     * 20 ticks = 1 sekund. Standard: 400 = 20 sekunder.
     */
    private int departDelayTicks = 400;

    /**
     * Antal ticks tyven går væk inden den despawner (loot er "solgt på sortmarkedet").
     * 20 ticks = 1 sekund. Standard: 600 = 30 sekunder.
     */
    private int departDurationTicks = 600;

    /**
     * Maksimal levetid i ticks inden tyven drager bort uanset beholdning.
     * Forhindrer at tyve ophober sig i verdenen. Standard: 48000 = 2 Minecraft-dage (40 min).
     */
    private int maxAgeTicks = 48000;

    /**
     * Om tyven afspiller en lyd mens den sniger sig væk med sit bytte.
     * false = lyden er slået fra. Standard: true.
     */
    // Gson sætter feltet via reflection ved deserialisering — IntelliJ ser ikke dette og advarer fejlagtigt.
    @SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
    private final boolean leavingSoundEnabled = true;

    /**
     * Korteste interval i ticks mellem hver tilfredsheds-lyd mens tyven sniger sig væk.
     * 20 ticks = 1 sekund. Standard: 80 = 4 sekunder.
     */
    private int leavingSoundMinTicks = 80;

    /**
     * Længste interval i ticks mellem hver tilfredsheds-lyd mens tyven sniger sig væk.
     * 20 ticks = 1 sekund. Standard: 160 = 8 sekunder.
     */
    private int leavingSoundMaxTicks = 160;

    // --- Item values (separate file) ---
    /** Map of item resource location -> priority value. Higher = more valuable. */
    private transient Map<String, Integer> itemValues = new HashMap<>();

    /**
     * Kontrollerer at alle config-værdier er inden for fornuftige grænser.
     * Klemmer (clamp) værdier der er for høje, lave eller umulige.
     * Eksempel: en bruger sætter chestDetectionRadius = -5 eller 999999.
     * Uden validering ville modden opføre sig uforudsigeligt eller crashe.
     * Med validering korrigeres værdien til det nærmeste gyldige tal.
     */
    public void validate() {
        chestInteractionIntervalTicks = Math.max(1, chestInteractionIntervalTicks);
        chestDetectionRadius = Math.clamp(chestDetectionRadius, 1, 500);
        chestDetectionMaxVerticalDist = Math.clamp(chestDetectionMaxVerticalDist, 1, 256);
        spawnWeight = Math.clamp(spawnWeight, 1, 1000);
        spawnMinGroup = Math.max(1, spawnMinGroup);
        spawnMaxGroup = Math.max(spawnMinGroup, spawnMaxGroup);
        if (spawnBiomes == null || spawnBiomes.isEmpty()) {
            spawnBiomes = List.of("minecraft:plains");
        }
        panicChance = Math.clamp(panicChance, 0.0, 1.0);
        panicDurationTicks = Math.max(1, panicDurationTicks);
        leaveDurationTicks = Math.max(1, leaveDurationTicks);
        maxCarrySlots = Math.clamp(maxCarrySlots, 1, 27);
        berserkDurationTicks = Math.max(1, berserkDurationTicks);
        berserkSpeedMultiplier = Math.clamp(berserkSpeedMultiplier, 1.0, 5.0);
        berserkFollowRange = Math.clamp(berserkFollowRange, 10.0, 200.0);
        nightSpeedBonus = Math.clamp(nightSpeedBonus, 0.0, 1.0);
        stealthMinTicks = Math.max(1, stealthMinTicks);
        stealthMaxTicks = Math.max(stealthMinTicks, stealthMaxTicks);
        stealthCooldownMinTicks = Math.max(1, stealthCooldownMinTicks);
        stealthCooldownMaxTicks = Math.max(stealthCooldownMinTicks, stealthCooldownMaxTicks);
        stealthChance = Math.clamp(stealthChance, 0.0, 1.0);
        departDelayTicks = Math.max(0, departDelayTicks);
        departDurationTicks = Math.max(20, departDurationTicks);
        maxAgeTicks = Math.max(1200, maxAgeTicks);
        leavingSoundMinTicks = Math.max(1, leavingSoundMinTicks);
        leavingSoundMaxTicks = Math.max(leavingSoundMinTicks, leavingSoundMaxTicks);
        spawnMinNearbyChests = Math.max(0, spawnMinNearbyChests);
    }

    // --- Setters (bruges af ClothConfigScreenBuilder) ---
    public void setChestInteractionIntervalTicks(int v) { chestInteractionIntervalTicks = v; }
    public void setChestDetectionRadius(double v)       { chestDetectionRadius = v; }
    public void setChestDetectionMaxVerticalDist(int v) { chestDetectionMaxVerticalDist = v; }
    public void setSpawnWeight(int v)                   { spawnWeight = v; }
    public void setSpawnMinGroup(int v)                 { spawnMinGroup = v; }
    public void setSpawnMaxGroup(int v)                 { spawnMaxGroup = v; }
    public void setSpawnMinNearbyChests(int v)          { spawnMinNearbyChests = v; }
    public void setPanicChance(double v)                { panicChance = v; }
    public void setPanicDurationTicks(int v)            { panicDurationTicks = v; }
    public void setLeaveDurationTicks(int v)            { leaveDurationTicks = v; }
    public void setMaxCarrySlots(int v)                 { maxCarrySlots = v; }
    public void setBerserkDurationTicks(int v)          { berserkDurationTicks = v; }
    public void setBerserkSpeedMultiplier(double v)     { berserkSpeedMultiplier = v; }
    public void setBerserkFollowRange(double v)         { berserkFollowRange = v; }
    public void setNightSpeedBonus(double v)            { nightSpeedBonus = v; }
    public void setStealthMinTicks(int v)               { stealthMinTicks = v; }
    public void setStealthMaxTicks(int v)               { stealthMaxTicks = v; }
    public void setStealthCooldownMinTicks(int v)       { stealthCooldownMinTicks = v; }
    public void setStealthCooldownMaxTicks(int v)       { stealthCooldownMaxTicks = v; }
    public void setStealthChance(double v)              { stealthChance = v; }
    public void setDepartDelayTicks(int v)              { departDelayTicks = v; }
    public void setDepartDurationTicks(int v)           { departDurationTicks = v; }
    public void setMaxAgeTicks(int v)                   { maxAgeTicks = v; }
    public void setLeavingSoundMinTicks(int v)          { leavingSoundMinTicks = v; }
    public void setLeavingSoundMaxTicks(int v)          { leavingSoundMaxTicks = v; }

    /**
     * Validerer og gemmer hoved-config til disk. Kaldes af config-skærmen ved tryk på "Gem".
     * Skriver ikke item-values — dem styres i en separat fil.
     */
    public void save() {
        validate();
        JsonConfigLoader.save(this, FabricLoader.getInstance().getConfigDir()
                .resolve("chest_thief_config.json"), ChestThiefMod.LOGGER);
    }

    // --- Getters ---
    public int getChestInteractionIntervalTicks() { return chestInteractionIntervalTicks; }
    public double getChestDetectionRadius() { return chestDetectionRadius; }
    public int getChestDetectionMaxVerticalDist() { return chestDetectionMaxVerticalDist; }
    public List<String> getSpawnBiomes() { return spawnBiomes; }
    public int getSpawnWeight() { return spawnWeight; }
    public int getSpawnMinGroup() { return spawnMinGroup; }
    public int getSpawnMaxGroup() { return spawnMaxGroup; }
    public boolean isStealOnlyListedItems() { return stealOnlyListedItems; }
    public double getPanicChance() { return panicChance; }
    public int getPanicDurationTicks() { return panicDurationTicks; }
    public int getLeaveDurationTicks() { return leaveDurationTicks; }
    public int getMaxCarrySlots() { return maxCarrySlots; }
    public int getBerserkDurationTicks() { return berserkDurationTicks; }
    public double getBerserkSpeedMultiplier() { return berserkSpeedMultiplier; }
    public double getBerserkFollowRange() { return berserkFollowRange; }
    public double getNightSpeedBonus() { return nightSpeedBonus; }
    public int getStealthMinTicks() { return stealthMinTicks; }
    public int getStealthMaxTicks() { return stealthMaxTicks; }
    public int getStealthCooldownMinTicks() { return stealthCooldownMinTicks; }
    public int getStealthCooldownMaxTicks() { return stealthCooldownMaxTicks; }
    public double getStealthChance() { return stealthChance; }
    public int getDepartDelayTicks() { return departDelayTicks; }
    public int getDepartDurationTicks() { return departDurationTicks; }
    public int getMaxAgeTicks() { return maxAgeTicks; }
    public boolean isLeavingSoundEnabled() { return leavingSoundEnabled; }
    public int getLeavingSoundMinTicks() { return leavingSoundMinTicks; }
    public int getLeavingSoundMaxTicks() { return leavingSoundMaxTicks; }
    public int getSpawnMinNearbyChests() { return spawnMinNearbyChests; }
    public Map<String, Integer> getItemValues() { return itemValues; }

    /**
     * Returnerer den delte config-instans.
     * Indlæser config automatisk hvis den ikke er indlæst endnu.
     * @return den aktuelle konfiguration
     */
    public static ChestThiefConfig getInstance() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    /**
     * Indlæser begge config-filer fra disk.
     * Opretter filer med standardværdier hvis de ikke eksisterer.
     * Validerer alle værdier efter indlæsning for at undgå ugyldige indstillinger.
     * Understøtter automatisk opgradering: Hvis en nyere version af modden
     * tilføjer nye parametre, vil de blive tilføjet filen med standardværdier
     * næste gang serveren starter. Eksisterende værdier bevares.
     * Kaldes automatisk af ChestThiefMod.onInitialize() ved opstart.
     */
    public static void load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();

        // --- Indlæs hoved-config via generisk loader ---
        // JsonConfigLoader håndterer oprettelse, indlæsning og auto-upgrade.
        // validate() kaldes her fordi det er ChestThiefConfigs ansvar at sikre
        // gyldige værdier — den generiske loader kender ikke til vores felter.
        INSTANCE = JsonConfigLoader.load(
                ChestThiefConfig.class,
                configDir.resolve("chest_thief_config.json"),
                ChestThiefConfig::new,
                ChestThiefMod.LOGGER
        );
        INSTANCE.validate();

        // --- Indlæs item-værdier ---
        Path valuesPath = configDir.resolve("chest_thief_values.json");
        if (Files.exists(valuesPath)) {
            try {
                String json = Files.readString(valuesPath);
                @SuppressWarnings("unchecked")
                Map<String, Double> raw = GSON.fromJson(json, Map.class);
                INSTANCE.itemValues = new HashMap<>();
                if (raw != null) {
                    raw.forEach((key, value) -> INSTANCE.itemValues.put(key, value.intValue()));
                }

                // Opgradering: tilføj nye default-items der ikke allerede er i filen.
                // Vi OVERSKRIVER IKKE eksisterende værdier — brugerens tilpasninger bevares.
                // Kun nye item-nøgler (fra nyere versioner af createDefaultItemValues) tilføjes.
                Map<String, Integer> defaults = createDefaultItemValues();
                boolean addedNewItems = false;
                for (Map.Entry<String, Integer> entry : defaults.entrySet()) {
                    if (!INSTANCE.itemValues.containsKey(entry.getKey())) {
                        INSTANCE.itemValues.put(entry.getKey(), entry.getValue());
                        addedNewItems = true;
                    }
                }
                if (addedNewItems) {
                    Files.writeString(valuesPath, GSON.toJson(INSTANCE.itemValues));
                    ChestThiefMod.LOGGER.info("Updated item values file with new default entries");
                }

                ChestThiefMod.LOGGER.info("Loaded {} item values from {}", INSTANCE.itemValues.size(), valuesPath);
            } catch (Exception e) {
                ChestThiefMod.LOGGER.error("Failed to load item values, using defaults", e);
                INSTANCE.itemValues = createDefaultItemValues();
            }
        } else {
            INSTANCE.itemValues = createDefaultItemValues();
            try {
                Files.writeString(valuesPath, GSON.toJson(INSTANCE.itemValues));
                ChestThiefMod.LOGGER.info("Created default item values at {}", valuesPath);
            } catch (IOException e) {
                ChestThiefMod.LOGGER.error("Failed to write default item values", e);
            }
        }
    }

    /**
     * Opretter standardlisten over item-værdier til tjæleriet.
     * Hvert item tildeles en prioritetsværdi — jo højere tal, jo mere
     * eftertragtet er itemet for Chest Thief'en. Mob'en vælger altid
     * det item med højest værdi i kisten.
     * Items der ikke er på listen får automatisk minimumsværdi 1,
     * så alt kan stjæles — ikke kun items i listen.
     * @return et Map fra item-id (f.eks. "minecraft:diamond") til prioritetsværdi
     */
    private static Map<String, Integer> createDefaultItemValues() {
        Map<String, Integer> values = new HashMap<>();
        // Extremely valuable
        values.put("minecraft:nether_star", 1000);
        values.put("minecraft:elytra", 900);
        values.put("minecraft:enchanted_golden_apple", 850);
        values.put("minecraft:totem_of_undying", 800);
        values.put("minecraft:beacon", 750);

        // Very valuable
        values.put("minecraft:diamond_block", 700);
        values.put("minecraft:emerald_block", 680);
        values.put("minecraft:netherite_ingot", 650);
        values.put("minecraft:netherite_scrap", 600);
        values.put("minecraft:diamond", 500);
        values.put("minecraft:emerald", 450);

        // Valuable equipment
        values.put("minecraft:netherite_sword", 550);
        values.put("minecraft:netherite_pickaxe", 540);
        values.put("minecraft:netherite_axe", 530);
        values.put("minecraft:netherite_chestplate", 560);
        values.put("minecraft:netherite_helmet", 545);
        values.put("minecraft:netherite_leggings", 550);
        values.put("minecraft:netherite_boots", 540);
        values.put("minecraft:diamond_sword", 400);
        values.put("minecraft:diamond_pickaxe", 390);
        values.put("minecraft:diamond_axe", 380);
        values.put("minecraft:diamond_chestplate", 410);
        values.put("minecraft:diamond_helmet", 395);
        values.put("minecraft:diamond_leggings", 400);
        values.put("minecraft:diamond_boots", 390);

        // Moderately valuable
        values.put("minecraft:gold_block", 350);
        values.put("minecraft:gold_ingot", 300);
        values.put("minecraft:iron_block", 250);
        values.put("minecraft:iron_ingot", 200);
        values.put("minecraft:lapis_lazuli", 150);
        values.put("minecraft:redstone", 100);
        values.put("minecraft:ender_pearl", 180);
        values.put("minecraft:blaze_rod", 170);
        values.put("minecraft:golden_apple", 350);
        values.put("minecraft:experience_bottle", 200);

        // Special items
        values.put("minecraft:name_tag", 120);
        values.put("minecraft:saddle", 130);
        values.put("minecraft:trident", 500);
        values.put("minecraft:heart_of_the_sea", 400);
        values.put("minecraft:music_disc_13", 200);
        values.put("minecraft:music_disc_cat", 200);

        return values;
    }
}
