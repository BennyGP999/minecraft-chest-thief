package email.pedersen.syndicate.config;

import email.pedersen.config.JsonConfigLoader;
import email.pedersen.syndicate.SyndicateMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Konfiguration for Syndicate-modulet.
 * Indlæses fra filen syndicate_config.json i Minecraft's config/-mappe ved opstart.
 * Filen oprettes automatisk med standardværdier hvis den ikke eksisterer.
 *
 * Implementeret som singleton: én instans deles af hele modulet.
 * Brug SyndicateConfig.getInstance() for at hente konfigurationen.
 *
 * Understøtter automatisk opgradering via JsonConfigLoader:
 * Nye felter med standardværdier tilføjes filen automatisk ved næste serverstart,
 * mens eksisterende brugerindstillinger bevares.
 */
public class SyndicateConfig {

    /** Den delte instans — null indtil load() er kaldt første gang. */
    private static SyndicateConfig INSTANCE;

    // --- Verdensgeneration ---

    /**
     * Afstand i chunks mellem centrum i hvert "region-felt" (gitter-celle).
     * Jo højere tal, jo sjældnere og mere spredte er baserne.
     * Standard: 64 chunks = 1024 blokke mellem base-centrer.
     * Minimum: 16 (sikrer at der er plads til separation-margin).
     */
    private int baseSpacingChunks = 32;

    /**
     * Minimum afstand i chunks fra cellegrænsen til base-centrum.
     * Forhindrer baser i at spawne for tæt på hinanden på tværs af cellegrænser.
     * Skal være under halvdelen af baseSpacingChunks for at der er plads til en kandidat.
     * Standard: 24 chunks = 384 blokke fra cellekant.
     */
    private int baseSeparationChunks = 12;

    // --- Vagter ---

    /**
     * Baseskade pr. pil affyret af en syndikats-vagt (AbstractArrow.setBaseDamage).
     * Vanilla skeleton-pil = 2.0. Skaden ganges med afstandsfaktoren og modifier-attributter
     * der allerede er indregnet i pilens beregning — dette er udgangspunktet.
     * Standard: 2.3 (≈ +15 % ift. vanilla skeleton-pil).
     * Minimum: 0.5.
     */
    private double guardArrowDamage = 2.3;

    /**
     * Antal vagter pr. item i basen (ganget med samlet item-antal og afrundet ned).
     * Eksempel: 50 items × 0.5 = 25 vagter, men clampet til maxGuards.
     * Standard: 0.5 — halvt så mange vagter som der er items.
     */
    private double guardsPerItem = 0.5;

    /**
     * Minimum antal vagter i basen, uanset om der slet ikke er nogen items endnu.
     * Sikrer at en nyoprettet tom base stadig er farlig at gå ind i.
     * Standard: 2.
     */
    private int minGuards = 4;

    /**
     * Maksimalt antal vagter i basen, selv om loot-mængden tilsiger flere.
     * Sætter et loft så basen ikke bliver umulig at raide ved meget loot.
     * Standard: 16.
     */
    private int maxGuards = 16;

    // --- Loot ---

    /**
     * Samlet antal items der fordeles i basens kister ved oprettelse (max 10).
     * Består af starterValuableCount værdifulde items + resten som common filler.
     * Standard: 10.
     */
    private int starterLootTotal = 10;

    /**
     * Antal af de samlede starter-items der stammer fra ChestThiefConfig's prioritetsliste
     * (medium-prioritet 100–400: jernredskaber, mad, basismaterialer).
     * Resten af starterLootTotal fyldes med almindelige filler-items (kul, planker, brød osv.).
     * Skal være ≤ starterLootTotal. Standard: 3.
     */
    private int starterValuableCount = 3;

    /**
     * Andel af peakLootCount der afgør om basen betragtes som raidet.
     * Raid trigges når: wasOpened == true OG currentLootCount < peakLootCount × raidThreshold.
     * 0.3 = raidet når under 30% af det hidtil højeste item-antal er tilbage.
     * Standard: 0.3.
     */
    private double raidThreshold = 0.3;

    // --- Opdagelse ---

    /**
     * Sandsynlighed (0.0–1.0) for at en Wandering Trader har Syndikatskort i sit sortiment.
     * 0.25 = 25 % chance pr. trader. Standard: 0.25.
     */
    private double mapTraderChance = 0.5;

    /**
     * Styrer om basens position automatisk tilføjes som waypoint i Xaero's Minimap
     * når spilleren lokaliserer basen (via /locate eller Syndikatskort).
     *
     * Standard: false — baserne er hemmelige og vises ikke på minimap med mindre
     * spilleren aktivt slår denne indstilling til. Har kun effekt hvis Xaero's
     * Minimap er installeret.
     */
    private boolean createXaeroWaypoints = false;

    // --- Loot-aflevering ---

    /**
     * Maksimal afstand i blokke hvorfra en tyv afleverer loot til en syndicate-kiste.
     * Tyve søger den nærmeste SyndicateChestBlockEntity inden for denne radius.
     * Hvis ingen kiste er tæt nok, tabes looten ("solgt lokalt").
     * Standard: 200 blokke — stor nok til at dække de fleste spiller-baser.
     */
    private double lootDeliveryRadius = 1000000.0;

    // --- Vagt-respawn ---

    /**
     * Antal server-ticks mellem hvert respawn-forsøg for vagter.
     * Respawn køres kun hvis ingen spiller er i nærheden af basen.
     * Standard: 6000 ticks = 5 minutter.
     */
    private int guardRespawnIntervalTicks = 6000;

    /**
     * Antal blokke ekstra "buffer" rundt om basens AABB til spillernærhedstjekket.
     * En spiller inden for basen + buffer forhindrer respawn — giver spilleren ro til at udforske.
     * Standard: 8 blokke.
     */
    private int guardRespawnPlayerBuffer = 8;

    // -------------------------------------------------------------------------
    // Validering
    // -------------------------------------------------------------------------

    /**
     * Kontrollerer at alle værdier er inden for gyldige grænser.
     * Klemmer (clamp) værdier der er for høje, lave eller indbyrdes inkonsistente.
     * Kaldes automatisk af load() efter deserialisering fra JSON.
     */
    public void validate() {
        // Spacing skal være mindst 16 chunks for at give plads til separation-margin
        baseSpacingChunks = Math.max(16, baseSpacingChunks);

        // Separation skal være positiv og under halvdelen af spacing minus 1,
        // så der altid er mindst 1 chunk at randomisere inden for cellen.
        int maxSep = (baseSpacingChunks - 1) / 2;
        baseSeparationChunks = Math.clamp(baseSeparationChunks, Math.min(1, maxSep), maxSep);

        // Guards-attributter
        guardArrowDamage = Math.max(0.5, guardArrowDamage);

        // Guards-formel
        guardsPerItem = Math.clamp(guardsPerItem, 0.0, 10.0);
        minGuards = Math.max(0, minGuards);
        maxGuards = Math.max(minGuards, maxGuards);

        // Loot
        starterLootTotal = Math.clamp(starterLootTotal, 0, 27);
        // starterValuableCount kan ikke overstige det samlede antal starter-items
        starterValuableCount = Math.clamp(starterValuableCount, 0, starterLootTotal);
        raidThreshold = Math.clamp(raidThreshold, 0.0, 1.0);

        // Opdagelse
        mapTraderChance = Math.clamp(mapTraderChance, 0.0, 1.0);

        // Loot-aflevering
        lootDeliveryRadius = Math.max(1.0, lootDeliveryRadius);

        // Vagt-respawn
        guardRespawnIntervalTicks = Math.max(200, guardRespawnIntervalTicks);
        guardRespawnPlayerBuffer = Math.max(0, guardRespawnPlayerBuffer);
    }

    // -------------------------------------------------------------------------
    // Singleton-håndtering
    // -------------------------------------------------------------------------

    /**
     * Returnerer den delte config-instans.
     * Indlæser config automatisk hvis den ikke er indlæst endnu.
     * @return den aktuelle konfiguration
     */
    public static SyndicateConfig getInstance() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    /**
     * Indlæser syndicate_config.json fra Minecraft's config/-mappe.
     * Opretter filen med standardværdier hvis den ikke eksisterer.
     * Validerer alle værdier efter indlæsning.
     * Kaldes fra SyndicateMod.onInitialize() ved serveropstart.
     */
    public static void load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();

        // JsonConfigLoader håndterer oprettelse, indlæsning og auto-upgrade.
        // Kaldet returnerer altid en ikke-null instans med gyldige (men måske ustemt) værdier.
        INSTANCE = JsonConfigLoader.load(
                SyndicateConfig.class,
                configDir.resolve("syndicate_config.json"),
                SyndicateConfig::new,
                SyndicateMod.LOGGER
        );

        // validate() er SyndicateConfigs eget ansvar — den generiske loader
        // kender ikke til vores specifikke felter og grænser.
        INSTANCE.validate();
    }

    // -------------------------------------------------------------------------
    // Setters (bruges af ClothConfigScreenBuilder)
    // -------------------------------------------------------------------------

    public void setBaseSpacingChunks(int v)          { baseSpacingChunks = v; }
    public void setBaseSeparationChunks(int v)        { baseSeparationChunks = v; }
    public void setGuardArrowDamage(double v)         { guardArrowDamage = v; }
    public void setGuardsPerItem(double v)            { guardsPerItem = v; }
    public void setMinGuards(int v)                   { minGuards = v; }
    public void setMaxGuards(int v)                   { maxGuards = v; }
    public void setStarterLootTotal(int v)            { starterLootTotal = v; }
    public void setStarterValuableCount(int v)        { starterValuableCount = v; }
    public void setRaidThreshold(double v)            { raidThreshold = v; }
    public void setMapTraderChance(double v)          { mapTraderChance = v; }
    public void setLootDeliveryRadius(double v)       { lootDeliveryRadius = v; }
    public void setGuardRespawnIntervalTicks(int v)   { guardRespawnIntervalTicks = v; }
    public void setGuardRespawnPlayerBuffer(int v)    { guardRespawnPlayerBuffer = v; }
    public void setCreateXaeroWaypoints(boolean v)    { createXaeroWaypoints = v; }

    /**
     * Validerer og gemmer config til disk. Kaldes af config-skærmen ved tryk på "Gem".
     */
    public void save() {
        validate();
        JsonConfigLoader.save(this, FabricLoader.getInstance().getConfigDir()
                .resolve("syndicate_config.json"), SyndicateMod.LOGGER);
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public double getGuardArrowDamage()     { return guardArrowDamage; }
    public int getBaseSpacingChunks()      { return baseSpacingChunks; }
    public int getBaseSeparationChunks()   { return baseSeparationChunks; }
    public double getGuardsPerItem()       { return guardsPerItem; }
    public int getMinGuards()              { return minGuards; }
    public int getMaxGuards()              { return maxGuards; }
    public int getStarterLootTotal()            { return starterLootTotal; }
    public int getStarterValuableCount()        { return starterValuableCount; }
    public double getRaidThreshold()            { return raidThreshold; }
    public double getMapTraderChance()          { return mapTraderChance; }
    public double getLootDeliveryRadius()       { return lootDeliveryRadius; }
    public int getGuardRespawnIntervalTicks()   { return guardRespawnIntervalTicks; }
    public int getGuardRespawnPlayerBuffer()    { return guardRespawnPlayerBuffer; }
    public boolean isCreateXaeroWaypoints()     { return createXaeroWaypoints; }
}
