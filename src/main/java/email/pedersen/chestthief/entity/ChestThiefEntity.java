package email.pedersen.chestthief.entity;

import email.pedersen.chestthief.config.ChestThiefConfig;
import email.pedersen.chestthief.entity.ai.BerserkTargetGoal;
import email.pedersen.chestthief.entity.ai.DepartGoal;
import email.pedersen.chestthief.entity.ai.FindAndStealFromChestGoal;
import email.pedersen.chestthief.entity.ai.LeaveAreaGoal;
import email.pedersen.chestthief.entity.ai.LookAtTargetChestGoal;
import email.pedersen.chestthief.entity.ai.NightStealthGoal;
import email.pedersen.chestthief.entity.ai.OpenGateGoal;
import email.pedersen.chestthief.entity.ai.PanicFleeGoal;
import email.pedersen.chestthief.ChestCoordinator;
import email.pedersen.chestthief.ChestTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import email.pedersen.chestthief.events.ChestThiefDepartedEvent;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Selve Chest Thief-entiteten — den mob der løber rundt og stjæler ting.
 * Klassen arver fra Monster, hvilket betyder at den:
 *   - Er en fjendtlig mob (som zombier og skeletons)
 *   - Tæller med i spawnlimiten for monstre
 *   - Brænder i dagslys (medmindre vi fjerner det — det gør vi ikke)
 *   - Kan angribe spillere
 * Adfærd om dagen:
 *   Leder efter kister, går hen til dem, åbner dem og stjæler det mest
 *   værdifulde item. Erstatter det med gulerødder som "betaling".
 *   Bærer de stjålne items på sig (max 5 slots). Når beholdningen er fuld,
 *   stopper den med at stjæle og går væk fra gerningsstedet.
 *   Kan åbne døre og koordinerer med andre Chest Thieves om ikke at
 *   gå til den samme kiste.
 * Adfærd ved angreb om dagen:
 *   60% chance: PANIK — sprinter væk fra angriberen og taber ét tilfældigt
 *               item fra sin beholdning. Vender tilbage til kiste-adfærd bagefter.
 *   40% chance: HÆVN — slår igen og forbliver fjendtlig i en konfigurerbar periode.
 * Adfærd om natten:
 *   Angriber spillere, landsbyboere og jerngolemmer — ligesom en zombie.
 * Specielt:
 *   Kan lægges i snor (leash). Mens den er i snor, finder og kigger den
 *   på kister men stjæler ikke.
 * Beholdning og drops:
 *   Bærer op til maxCarrySlots item-staks (standard 5). Dropper alle
 *   bårne items ved død. Under panik droppes ét item tilfældigt.
 */
@NullMarked
public class ChestThiefEntity extends Monster {

    /**
     * Positionen på den kiste som mob'en i øjeblikket sigter mod.
     * Bruges af LookAtTargetChestGoal til at rotere hoved og krop mod kisten.
     * Er null når mob'en ikke har noget kiste-mål.
     */
    @Nullable
    private BlockPos targetChestPos = null;

    /**
     * Den sidst besøgte kiste-position. Gemmes selvom mob'en forlader kisten,
     * så LeaveAreaGoal ved hvilken retning den skal gå væk fra.
     * Nul-stilles ikke når kiste-målet ryddes — kun opdateres ved ny kiste.
     */
    @Nullable
    private BlockPos lastChestPos = null;

    /**
     * Om mob'en i øjeblikket er i berserker-mode (aktiv aggression med speed-boost).
     * Sættes til true af startBerserk() ved 40% chance når mob'en rammes om dagen.
     * Sættes til false af stopBerserk() når berserkTimer løber ud.
     */
    private boolean isBerserk = false;

    /**
     * Nedtæller i ticks for hvor længe berserker-mode varer.
     * Nulstilles ved hvert nyt angreb på mob'en. 20 ticks = 1 sekund.
     */
    private int berserkTimer = 0;

    /** Identifier for attribut-modifikatoren der øger hastighed under berserker-mode. */
    private static final Identifier BERSERK_SPEED_ID =
            Identifier.fromNamespaceAndPath("chest_thief", "berserk_speed");

    /** Identifier for attribut-modifikatoren der øger hastighed om natten. */
    private static final Identifier NIGHT_SPEED_ID =
            Identifier.fromNamespaceAndPath("chest_thief", "night_speed");

    /**
     * Om tyven er i afrejse-tilstand — går bort med sit bytte og vil snart despawne.
     * Aktiveres af startDeparture() og varer departDurationTicks ticks.
     */
    private boolean isDeparting = false;

    /**
     * Nedtæller til despawn når tyven drager bort.
     * Tæller ned i tick() uanset om DepartGoal er aktiv (panik/berserk afbryder ikke timeren).
     */
    private int departTimer = 0;

    /**
     * Tæller op mens beholdningen er fuld. Udløser startDeparture() ved departDelayTicks.
     * Nulstilles hvis et item droppes (panik).
     */
    private int fullInventoryTimer = 0;

    /**
     * Om mob'en i øjeblikket er i gang med aktivt at stjæle fra en kiste (STEALING-state).
     * Sættes af FindAndStealFromChestGoal hvert tick. Bruges af NightStealthGoal til
     * at afbryde usynlighed øjeblikkeligt når tyveriet begynder.
     */
    private boolean isStealing = false;

    /**
     * Om mob'en i øjeblikket er i panik-tilstand (sprinter væk fra angriber).
     * Sættes til true i provoke() med 60% sandsynlighed.
     * Sættes til false igen af PanicFleeGoal.stop() når panik-perioden er slut.
     */
    private boolean isPanicking = false;

    /**
     * Positionen mob'en flygter fra under panik (angriberangrebs-position).
     * Bruges af PanicFleeGoal til at beregne flygte-retningen.
     * Null hvis mob'en ikke er i panik.
     */
    @Nullable
    private BlockPos panicFleeFrom = null;

    /**
     * Mob'ens bæreinventar — de items den har stjålet fra kister.
     * Størrelsen er fastsat til maxCarrySlots fra config (standard 5).
     * Tomme slots er ItemStack.EMPTY. Gemmes i NBT og droppes ved død.
     */
    private NonNullList<ItemStack> carriedItems;

    /**
     * Constructor — kaldes af Minecraft når en ny Chest Thief-entitet oprettes i verden.
     * @param entityType den registrerede entitets-type (fra ChestThiefMod)
     * @param level      den verden/dimension mob'en spawner i
     */
    public ChestThiefEntity(EntityType<? extends Monster> entityType, Level level) {
        super(entityType, level);
        int slots = ChestThiefConfig.getInstance().getMaxCarrySlots();
        this.carriedItems = NonNullList.withSize(slots, ItemStack.EMPTY);

    }

    /**
     * Opretter den navigations-type mob'en bruger til at finde vej.
     * Vi bruger GroundPathNavigation (den normale jordbaserede navigation) med
     * fire tilpasninger der tilsammen giver tyven spiller-lignende fremkommelighed:
     *   setCanOpenDoors(true)          — kan åbne træ- og jerndøre (village-raids)
     *   setMaxVisitedNodesMultiplier   — fordobler antallet af pathfinding-noder;
     *                                    finder veje rundt om komplekse forhindringer
     *                                    der ellers ville stoppe standard-pathfinderen
     *   setCanPathThroughWater(true)   — behandler vand som gangbart terræn i stedet
     *                                    for en forhindring; mob'en svømmer mod kister
     *                                    på den anden side af floder og søer
     * @param level den verden navigationen skal fungere i
     * @return en GroundPathNavigation konfigureret til maksimal fremkommelighed
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanOpenDoors(true);

        // Fordobler antallet af pathfinding-noder der beregnes per rute.
        // Standard er 1.0 (normalt begrænset til ~100 noder). Med 2.0 finder
        // pathfinderen veje rundt om komplekse labyrinter og tæt bebyggelse
        // til let ekstra CPU-omkostning per mob per rute-beregning.
        nav.setMaxVisitedNodesMultiplier(2.0f);

        // Aktiver svøm-navigation på NodeEvaluator'en — behandler vandblokke som
        // gangbare noder i pathfinding-grafen (canFloat=true). Uden dette ruder
        // navigationen altid udenom vand, og kister bag en flod er uopnåelige.
        // FloatGoal (prioritet 0 i registerGoals) sørger for at mob'en ikke synker.
        nav.getNodeEvaluator().setCanFloat(true);

        return nav;
    }

    /**
     * Øger step-height fra standard 0.6 til 1.0 blokke.
     * Step-height er den maksimale niveauforskel mob'en automatisk klatrer over
     * uden at hoppe. Standard 0.6 svarer til en halvslab — tyven snubler over
     * mange hverdagsforhindringer (trapper, tærskler, murkanter, jordbunker).
     * Med 1.0 klatrer mob'en direkte op på 1-bloks forhøjninger, præcis som
     * en spiller gør det — en handelsmand der har vandret i årevis kender
     * enhver vej og klatrer ubesværet over det meste.
     * I Minecraft 26.1.1 returnerer vi step-height som en getter fremfor at kalde
     * en setter — det er den korrekte API i denne version.
     * @return 1.0 blokke — svarende til spillerens naturlige step-height
     */
    @Override
    public float maxUpStep() {
        return 1.0f;
    }

    /**
     * Tillader mob'en at klatre op og ned ad stiger, lianer og stillads.
     * LivingEntity's standardimplementering tjekker BlockTags.CLIMBABLE, men
     * Monster-underklasser overskriver den ikke — vi gør det eksplicit her for
     * at sikre og dokumentere adfærden.
     * En vandrende handelsmand klatrer stiger i handelsposter og landsbyers
     * huse hver dag. Som tyv udnytter han præcis den viden til at nå kister
     * på 2. etage eller i kældre.
     * BlockTags.CLIMBABLE dækker bl.a.:
     *   - minecraft:ladder          (normale stiger)
     *   - minecraft:vine            (lianer på vægge)
     *   - minecraft:scaffolding     (stillads)
     *   - minecraft:twisting_vines  (Nether)
     *   - minecraft:weeping_vines   (Nether)
     *   - minecraft:cave_vines      (drypstenshuler)
     * @return true hvis mob'en i øjeblikket befinder sig på en klatrebar blok
     */
    @Override
    public boolean onClimbable() {
        if (this.isSpectator()) return false;
        // getInBlockState() bruges frem for level().getBlockState(blockPosition()) —
        // vanilla bruger en cachet "inBlockState" der opdateres baseret på mob'ens
        // bounding box, ikke blot fødernes præcise blokposition. Det giver korrekt
        // detektion selv når mob'en er i overgang mellem to blokke.
        return this.getInBlockState().is(BlockTags.CLIMBABLE);
    }

    /**
     * Definerer de grundlæggende stats for alle Chest Thieves.
     * Disse værdier gælder for alle instanser af mob'en.
     * Kan kaldes statisk (uden en konkret entitet) fordi stats'ene
     * er de samme for alle Chest Thieves — de er ikke individuelle.
     * @return en builder med alle stats sat
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)      // 10 hjerter — samme som en zombie
                .add(Attributes.MOVEMENT_SPEED, 0.27)  // lidt hurtigere end en zombie (0.25) —
                                                       // 0.27 giver tilstrækkelig vandret fart til
                                                       // at hoppe over 2-bloks kløfter (se opgave 6)
                .add(Attributes.JUMP_STRENGTH, 0.5)    // lidt højere hop end standard (0.42) —
                                                       // clearance til at rydde kanter ved siden
                                                       // af kløfter; pathfinderens tryJumpOn()
                                                       // bruger denne attribut til at beregne
                                                       // jump-højden og generere noder over huller
                .add(Attributes.ATTACK_DAMAGE, 3.0)    // 1,5 hjerters skade pr. slag
                .add(Attributes.FOLLOW_RANGE, 35.0)    // kan se/følge mål inden for 35 blokke
                .add(Attributes.ARMOR, 2.0);            // lidt naturlig rustning
    }

    /**
     * Spawn-betingelse for Chest Thief: tjekker normale monstre-regler OG kræver
     * at der er et minimumsantal kister i nærheden.
     * Normale monstre-regler (checkMonsterSpawnRules) kræver:
     *   - Nat eller tilstrækkeligt mørke (lysniveau ≤ 0)
     *   - Fast underlag at stå på
     * Derudover tjekker vi om der er mindst spawnMinNearbyChests kister inden for
     * detektionsradius og den vertikale grænse. Hvis spawnMinNearbyChests = 0, springes
     * kiste-tjekket over og mob'en kan spawne overalt som normale monstre.
     * Effekt for spilleren:
     *   Tyve dukker primært op nær bebyggelser og baser med kister.
     *   En spiller der bygger i fri natur uden kister vil sjældent se tyve —
     *   men sætter de kister op, begynder tyve at trækkes til stedet.
     * @param type   mob-typen (ChestThiefEntity)
     * @param level  verden-accessor brugt til at tjekke spawning-betingelser
     * @param reason årsagen til spawn (naturlig, kommando, spawn egg osv.)
     * @param pos    positionen der overvejes til spawn
     * @param random tilfældighedskilde
     * @return true hvis mob'en må spawne her
     */
    public static boolean checkChestThiefSpawnRules(
            EntityType<? extends Monster> type,
            ServerLevelAccessor level,
            EntitySpawnReason reason,
            BlockPos pos,
            RandomSource random) {
        // Tjek først de normale monstre-regler (nat/mørke, fast underlag, mv.)
        if (!Monster.checkMonsterSpawnRules(type, level, reason, pos, random)) return false;

        ChestThiefConfig config = ChestThiefConfig.getInstance();
        int minChests = config.getSpawnMinNearbyChests();

        // 0 = ingen kiste-krav — spawn som normale monstre
        if (minChests <= 0) return true;

        // ServerLevelAccessor er ikke Level, men Level er en subtype — cast er sikkert her
        // fordi ChestTracker kun kalder dimension().identifier() og distSqr(), som begge
        // er tilgængelige via Level. I praksis er level altid en ServerLevel ved naturlig spawn.
        if (!(level instanceof Level lv)) return true;

        int nearbyChests = ChestTracker.countNearby(
                lv, pos,
                config.getChestDetectionRadius(),
                config.getChestDetectionMaxVerticalDist());

        return nearbyChests >= minChests;
    }

    /**
     * Registrerer alle AI-mål (Goals) for mob'en.
     * Et Goal er et stykke adfærd som mob'en kan udføre.
     * Prioritet fungerer sådan: lavere tal = højere prioritet.
     * Minecraft kører altid det mål med lavest prioritetstal, der kan starte.
     * Eks: Prioritet 0 (svøm) slår altid prioritet 5 (vandre).
     * goalSelector = hvad mob'en GØR (bevæge sig, angribe, vandre)
     * targetSelector = hvem mob'en ANGRIBER (vælger mål)
     */
    @Override
    protected void registerGoals() {
        // Prioritet 0: Svøm i vand — ALTID aktiv, mob'en drukner ikke
        this.goalSelector.addGoal(0, new FloatGoal(this));

        // Prioritet 1a: Panik-flugt — aktiv i 60% af angrebene.
        // Sprinter væk fra angriberen og taber ét tilfældigt item.
        this.goalSelector.addGoal(1, new PanicFleeGoal(this));

        // Prioritet 1b: Nat-stealth — kortvarig usynlighed ved aktiv tyveri om natten.
        // Ingen flags — kører parallelt med alle andre mål uden at blokere dem.
        this.goalSelector.addGoal(1, new NightStealthGoal(this));

        // Prioritet 2: Angrib det valgte mål — aktiv når targetSelector har fundet et mål.
        // Blokeres af PanicFleeGoal (prioritet 1) via MOVE-flag.
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, true));

        // Prioritet 3a: Åbn døre mens mob'en navigerer — nødvendig for village-raids
        this.goalSelector.addGoal(3, new OpenDoorGoal(this, true));

        // Prioritet 3b: Åbn hegn-låger og trapdøre — kører parallelt (ingen flag),
        // aktiv kun mens mob'en har et kiste-mål så den ikke åbner tilfældige låger
        this.goalSelector.addGoal(3, new OpenGateGoal(this));

        // Prioritet 3b: Drag bort med loot og despawn — overtager når departure er aktiveret.
        // Lavere end panik (1) og kamp (2), men højere end LeaveAreaGoal (4).
        this.goalSelector.addGoal(3, new DepartGoal(this));

        // Prioritet 4: Gå væk fra gerningsstedet når beholdningen er fuld.
        // Blokerer FindAndStealFromChestGoal (prioritet 5) via MOVE-flag.
        this.goalSelector.addGoal(4, new LeaveAreaGoal(this));

        // Prioritet 5: Find en kiste og stjæl fra den — kun aktiv om dagen og når der er plads i beholdningen
        this.goalSelector.addGoal(5, new FindAndStealFromChestGoal(this));

        // Prioritet 6: Kig på den kiste mob'en er på vej til — giver visuel feedback
        this.goalSelector.addGoal(6, new LookAtTargetChestGoal(this));

        // Prioritet 7: Vandre tilfældigt rundt.
        // Vi bruger RandomStrollGoal (uden vand-undgåelse) fremfor
        // WaterAvoidingRandomStrollGoal, fordi mob'en nu bevidst kan svømme
        // — vand-undgåelse i vandreturen ville modvirke svøm-evnen.
        this.goalSelector.addGoal(7, new RandomStrollGoal(this, 1.0));

        // Prioritet 8: Kig på spillere der er inden for 8 blokke
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));

        // Prioritet 9: Kig tilfældigt rundt (lavest prioritet — udfylder "tomgangstid")
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        // Target selector — afgør HVEM mob'en angriber (KUN reaktivt — aldrig proaktivt):
        // Prioritet 0: Berserker-mål — aktiv under berserker-mode, scanner aggressivt inden for berserkFollowRange
        this.targetSelector.addGoal(0, new BerserkTargetGoal(this));
        // Prioritet 1: Angrib den der slog mob'en (defensivt, dag og nat)
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
    }

    /**
     * Aktiveres når mob'en angribes om dagen.
     * Bestemmer tilfældigt om mob'en paniker eller slår igen:
     *   - panicChance (standard 60%): Panik — sprinter væk og taber ét item
     *   - resten (standard 40%): Hævn — sætter angriberen direkte som mål
     * Virker kun om dagen — om natten er mob'en altid fjendtlig i forvejen.
     * Ignoreres hvis mob'en allerede er i panik.
     * Kaldes fra ChestThiefMod via AFTER_DAMAGE-eventet.
     * @param attacker    angriberen som levende entitet (bruges til hævn-mål). Null er tilladt.
     * @param attackerPos positionen på angriberen (bruges til flugte-retning under panik). Null er tilladt.
     */
    public void provoke(@Nullable LivingEntity attacker, @Nullable BlockPos attackerPos) {
        if (isPanicking) return;     // afbryd ikke en igangværende panik

        // Hvis allerede i berserker-mode: nulstil bare timeren og opdater mål
        if (isBerserk) {
            ChestThiefConfig config = ChestThiefConfig.getInstance();
            berserkTimer = config.getBerserkDurationTicks();
            if (attacker != null) setTarget(attacker);
            return;
        }

        ChestThiefConfig config = ChestThiefConfig.getInstance();
        if (this.getRandom().nextDouble() < config.getPanicChance()) {
            // Panik! PanicFleeGoal aktiveres på næste tick fordi isPanicking = true
            isPanicking = true;
            panicFleeFrom = attackerPos;
        } else {
            // Berserker! Hastigheds-boost + aggressiv target-scanning i berserkDurationTicks
            startBerserk(attacker);
        }
    }

    /**
     * Kører én gang pr. tick (20 gange i sekundet) for denne mob.
     * Håndterer hævn-timeren: tæller ned så længe det er dag og
     * mob'en er i hævn-tilstand. Når timeren løber ud, ryddes målet
     * og mob'en vender tilbage til sin normale kiste-adfærd.
     * super.tick() sørger for al normal mob-logik (tyngdekraft, animation osv.)
     */
    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()) {
            ChestThiefConfig config = ChestThiefConfig.getInstance();

            // Berserker-timer
            if (isBerserk && --berserkTimer <= 0) {
                stopBerserk();
            }

            // Nat-hastigheds-modifier: +nightSpeedBonus om natten (deaktiveres under berserk
            // da berserk-boost allerede er større og bruger en separat modifier)
            var speedAttr = getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                if (isNightTime() && !isBerserk) {
                    speedAttr.addOrUpdateTransientModifier(new AttributeModifier(
                            NIGHT_SPEED_ID,
                            config.getNightSpeedBonus(),
                            AttributeModifier.Operation.ADD_VALUE
                    ));
                } else {
                    speedAttr.removeModifier(NIGHT_SPEED_ID);
                }
            }

            // Stige-klatring opad: vanilla's LivingEntity.travel() sætter allerede Y=0.2
            // på bevægelsesvektoren når onClimbable() er sand — men kun hvis mob'en
            // allerede HAR opadgående input. Mobs styres af navigationssystemet, som
            // kun genererer horisontale bevægelsesinput; der er intet der lægger
            // opadgående velocity ind. Vi kompenserer manuelt: sæt Y-hastighed til 0.2
            // (4 blokke/sek.) når mob'en er på en klatrebar blok og kistens Y-position
            // er over mob'ens aktuelle Y. Gælder kun server-side (klienten simulerer
            // ikke AI-logik).
            // Stige-navigation: to trin — "adjacent" og "på stigen"
            //
            // Problemet: Pathfinderens "done"-tærskel stopper mob'en ~0.3–0.5 blokke
            // FORAN stigens blok — for langt væk til at onClimbable() bliver true.
            // Vanilla's movement system rører ikke Y-velocity for mobs uden opadgående
            // input, og navigationssystemet genererer ingen vertikale noder igennem stiger.
            // Resultat: mob'en står plantet foran stigen og går i uendelig re-navigerings-løkke.
            //
            // Løsning: to-fase logik
            //   Fase 1 — ADJACENT: mob'en er i naboblokken til en klatrebar blok.
            //     Sæt en lille horisontal velocity MOD stigen (0.15 blk/tick) for at
            //     skubbe mob'en ind i stigen og overvinde navigation-done-tærsklen.
            //     Ingen Y-hastighed her — mob'en er endnu ikke på stigen.
            //   Fase 2 — PÅ STIGEN: onClimbable() = true.
            //     Nulstil X/Z-velocity (forhindrer drift af stigen) og sæt Y=0.2 (4 blk/sek).
            if (targetChestPos != null && targetChestPos.getY() > this.getBlockY()) {
                if (onClimbable()) {
                    // Fase 2: klatr lodret op, ingen vandret drift
                    setDeltaMovement(0, 0.2, 0);
                } else {
                    // Fase 1: tjek om en klatrebar blok er inden for 1 bloks afstand
                    // (alle 8 retninger — kardinal og diagonal).
                    //
                    // Kun 4 kardinalretninger er ikke nok: pathfinderens "done"-tærskel
                    // kan stoppe mob'en ved HJØRNET af approach-blokken, f.eks. ved
                    // blockPos (9, 64, 8) når approach er (10, 64, 8) og stige er (10, 64, 9).
                    // Fra (9, 64, 8) er stigen diagonal — udenfor kardinal-søgningens rækkevidde.
                    // 8-retnings-søgning fanger også sådanne hjørne-positioner.
                    BlockPos selfPos = blockPosition();
                    BlockPos nearest = null;
                    double nearestDist = Double.MAX_VALUE;
                    for (int ddx = -1; ddx <= 1; ddx++) {
                        for (int ddz = -1; ddz <= 1; ddz++) {
                            if (ddx == 0 && ddz == 0) continue;
                            BlockPos nb = selfPos.offset(ddx, 0, ddz);
                            if (level().getBlockState(nb).is(BlockTags.CLIMBABLE)) {
                                double dx2 = nb.getX() + 0.5 - getX();
                                double dz2 = nb.getZ() + 0.5 - getZ();
                                double dist = dx2 * dx2 + dz2 * dz2;
                                if (dist < nearestDist) {
                                    nearestDist = dist;
                                    nearest = nb;
                                }
                            }
                        }
                    }
                    if (nearest != null) {
                        // Skub mod midten af stige-blokken (+0.5 = blokcentrum, da blockPos er hjørnet).
                        double dx2 = nearest.getX() + 0.5 - getX();
                        double dz2 = nearest.getZ() + 0.5 - getZ();
                        // Beregn afstanden (Pythagoras) og normaliser til enhedsvektor:
                        //   (dx2/len, dz2/len) er en vektor med præcis længde 1 i retning mod stigen.
                        //   Ganges med 0.15 for at sætte farten til 0.15 blokke/tick mod stigen.
                        // Tærsklen > 0.01 forhindrer division med næsten-nul hvis mob'en
                        // allerede er midt i stigen (len ≈ 0 giver numerisk affald).
                        double len = Math.sqrt(dx2 * dx2 + dz2 * dz2);
                        if (len > 0.01) {
                            setDeltaMovement(dx2 / len * 0.15, getDeltaMovement().y, dz2 / len * 0.15);
                        }
                    }
                }
            }

            // Gap-spring: detekter kløft i path og kald jump() ved kanten.
            //
            // WalkNodeEvaluatorMixin genererer noder 2-3 blokke væk over kløfter for
            // Chest Thief, men vanilla's navigation-system udløser IKKE jump() for
            // horisontale spring — det sker automatisk kun ved opadgående noder (trapper).
            // Vi kompenserer manuelt: hvis næste path-node er ≥2 blokke væk horisontalt
            // på SAMME Y-niveau (= et kløftspring, ikke en trappeopgang), OG vi er ved
            // kanten (ingen fast overflade 1 blok fremme), OG vi er på jorden → kald
            // jump(). jump() sætter et internt flag der giver mob'en et hop på næste tick.
            //
            // Timing: dette kører EFTER super.tick() (der indeholder navigation.tick()),
            // så path.getNextNode() afspejler den aktuelle navigationstilstand.
            // isOnGround() tjekkes fordi jump() kun er meningsfuldt fra fast underlag.
            if (onGround() && !getNavigation().isDone()) {
                var gapPath = getNavigation().getPath();
                if (gapPath != null) {
                    var gapNext = gapPath.getNextNode();
                    if (gapNext != null) {
                        int gndx = gapNext.x - getBlockX();
                        int gndz = gapNext.z - getBlockZ();
                        // Næste node er ≥2 blokke væk horisontalt og maks. 1 blok opad.
                        // +1 tillader landing på en blok der er 1 niveau højere end afsætspunktet
                        // (f.eks. en kiste på den anden side af kløften).
                        if ((Math.abs(gndx) >= 2 || Math.abs(gndz) >= 2) && gapNext.y <= getBlockY() + 1) {
                            int gsx = (int) Math.signum(gndx);
                            int gsz = (int) Math.signum(gndz);
                            BlockPos edgeFloor = blockPosition().offset(gsx, -1, gsz);
                            // Er der en kløft 1 blok fremme?
                            if (!level().getBlockState(edgeFloor).isFaceSturdy(level(), edgeFloor, Direction.UP)) {
                                // Kants-nærheds-tjek: mob'en skal være tæt nok på kanten til at
                                // springe med tilstrækkelig rækkevidde til at nå landingssiden.
                                //
                                // Problem med høj tærskel (f.eks. 0.9): mob'en bevæger sig 0.27
                                // blokke/tick, så fra fracX=0.75 → 1.02 i ét tick — tærsklen
                                // springes over uden at aktivere, og mob'en falder uden at hoppe.
                                //
                                // Løsning: to-lags tjek
                                //   Lag 1 — på solid blok (blok X, fast underlag nedenunder):
                                //     Tærskel 0.7 → vindue [0.7, 1.0) = 0.30 bred. Med 0.27 blk/tick
                                //     kan dette vindue ALDRIG springes over i ét tick.
                                //   Lag 2 — i gab-blokken (ingen fast overflade direkte nedenunder):
                                //     Udløs straks (tærskel 0.0). onGround() er stadig true indtil
                                //     bagkant af boundingbox (0.3 blokke bag centret) forlader den
                                //     faste blok. Det giver ~0.3 blokkes margin = >1 ticks vindue.
                                //
                                // Kombinationen garanterer at hoppet altid udløses i rette tid
                                // uanset præcis hvilken fase i blokken mob'en befinder sig.
                                // gapWidth = antal TOM blokke i kløften (ikke afstanden til landingsblokken).
                                // Næste node er f.eks. 2 blokke væk → afstand 2 → kløft har 1 tom blok (1-bloks kløft).
                                // Næste node er 3 blokke væk → afstand 3 → 2 tomme blokke (2-bloks kløft).
                                // -1 konverterer altså node-afstand til antal tomme blokke i kløften.
                                int gapWidth = Math.max(Math.abs(gndx), Math.abs(gndz)) - 1;
                                double fracX = getX() - getBlockX();
                                double fracZ = getZ() - getBlockZ();
                                boolean nearEdge;
                                if (gapWidth >= 2) {
                                    // Tjek om mob'en er direkte over fast underlag (lag 1) eller ej (lag 2)
                                    BlockPos underMob = blockPosition().below();
                                    boolean onSolidBelow = level().getBlockState(underMob)
                                            .isFaceSturdy(level(), underMob, Direction.UP);
                                    double threshold = onSolidBelow ? 0.7 : 0.0;
                                    nearEdge = (gsx > 0 && fracX >= threshold)
                                            || (gsx < 0 && fracX <= (1.0 - threshold))
                                            || (gsz > 0 && fracZ >= threshold)
                                            || (gsz < 0 && fracZ <= (1.0 - threshold));
                                } else {
                                    // 1-bloks kløft: tærskel 0.5 (bekræftet virkende)
                                    nearEdge = (gsx > 0 && fracX >= 0.5)
                                            || (gsx < 0 && fracX <= 0.5)
                                            || (gsz > 0 && fracZ >= 0.5)
                                            || (gsz < 0 && fracZ <= 0.5);
                                }
                                if (nearEdge) {
                                    getJumpControl().jump();
                                    if (gapWidth >= 2) {
                                        // 2-bloks kløft kræver mere horisontal rækkevidde end
                                        // den naturlige fremadgående hastighed giver.
                                        // jumpFromGround() sætter KUN Y-komponenten, så en
                                        // manuelt sat X/Z-hastighed her bevares ind i springet
                                        // næste tick. 0.42 blk/tick ≈ 57% hurtigere end base (0.27)
                                        // og giver ~3.2 blokkes luftrækkevidde — tilstrækkeligt
                                        // til sikkert at rydde 2-bloks kløften.
                                        setDeltaMovement(
                                                gsx * 0.42,
                                                getDeltaMovement().y,
                                                gsz * 0.42
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Departure-logik
            if (!isDeparting) {
                if (isInventoryFull()) {
                    fullInventoryTimer++;
                    if (fullInventoryTimer >= config.getDepartDelayTicks()) {
                        startDeparture();
                    }
                } else {
                    fullInventoryTimer = 0; // nulstil hvis item droppedes (panik)
                }
                // Aldersbaseret afgang uanset beholdning
                if (this.tickCount >= config.getMaxAgeTicks()) {
                    startDeparture();
                }
            } else {
                // Departure-nedtælling kører uanset panik/berserk-afbrydelser
                if (--departTimer <= 0) {
                    // Fyr event inden despawn, så lyttere (f.eks. syndicate-modulet) kan
                    // håndtere loot. List.copyOf() giver en uforanderlig snapshot —
                    // carriedItems må ikke modificeres udefra efter dette punkt.
                    // Eventet er et no-op hvis ingen lyttere er registreret.
                    if (level() instanceof ServerLevel serverLevel && !carriedItems.isEmpty()) {
                        ChestThiefDepartedEvent.EVENT.invoker().onDeparted(
                                serverLevel,
                                blockPosition(),
                                List.copyOf(carriedItems)
                        );
                    }
                    discard();
                }
            }
        }
    }

    /**
     * Kaldes når mob'en dør.
     * Minecraft kalder IKKE stop() på aktive goals ved mob-død, så en kiste der var
     * åben af tyven forbliver åben for altid. Vi lukker den manuelt her via det samme
     * blockEvent-kald som FindAndStealFromChestGoal.setChestOpen() bruger internt.
     * Vi frigiver også et eventuelt ChestCoordinator-krav så andre tyve kan overtage.
     */
    @Override
    public void die(DamageSource damageSource) {
        super.die(damageSource);
        if (targetChestPos != null && level() instanceof ServerLevel sl) {
            var bs = sl.getBlockState(targetChestPos);
            if (bs.getBlock() instanceof ChestBlock) {
                // Luk kiste-animationen (samme mekanisme som ChestBlockEntity bruger internt)
                sl.blockEvent(targetChestPos, bs.getBlock(), 1, 0);
            }
            ChestCoordinator.release(getUUID());
        }
    }

    /**
     * Gemmer mob'ens tilstand i Minecrafts lagringsformat (26.1.x: ValueOutput API).
     * Kaldes automatisk af Minecraft når verdenen gemmes.
     * Vi gemmer udover den normale monster-data:
     *   - De items mob'en bærer (carriedItems)
     *   - Positionen på den sidst besøgte kiste (lastChestPos)
     * Panik-tilstand (isPanicking) gemmes IKKE — det er en midlertidig tilstand
     * der nulstilles automatisk ved næste login.
     * @param output ValueOutput-instansen data gemmes til
     */
    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);

        // Gem bårne items via ContainerHelper (håndterer slots og item-serialisering)
        ContainerHelper.saveAllItems(output, carriedItems);

        // Gem sidst besøgte kiste-position (null gemmes som ingen post)
        output.storeNullable("LastChestPos", BlockPos.CODEC, lastChestPos);

        // Gem berserker-tilstand så den overlever server-genstart
        output.putBoolean("IsBerserk", isBerserk);
        output.putInt("BerserkTimer", berserkTimer);

        // Gem departure-tilstand
        output.putBoolean("IsDeparting", isDeparting);
        output.putInt("DepartTimer", departTimer);
        output.putInt("FullInventoryTimer", fullInventoryTimer);
    }

    /**
     * Indlæser mob'ens tilstand fra Minecrafts lagringsformat (26.1.x: ValueInput API).
     * Kaldes automatisk af Minecraft ved indlæsning af en gemt verden.
     * @param input ValueInput-instansen data indlæses fra
     */
    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);

        // Sørg for at carriedItems har den korrekte størrelse (config kan have ændret sig)
        int slots = ChestThiefConfig.getInstance().getMaxCarrySlots();
        if (carriedItems.size() != slots) {
            carriedItems = NonNullList.withSize(slots, ItemStack.EMPTY);
        }

        // Indlæs bårne items
        ContainerHelper.loadAllItems(input, carriedItems);

        // Indlæs sidst besøgte kiste-position (orElse(null) hvis den ikke var gemt)
        lastChestPos = input.read("LastChestPos", BlockPos.CODEC).orElse(null);

        // Genopretter berserker-tilstand efter server-genstart (inkl. speed-modifier)
        boolean savedBerserk = input.getBooleanOr("IsBerserk", false);
        int savedTimer = input.getIntOr("BerserkTimer", 0);
        if (savedBerserk && savedTimer > 0) {
            isBerserk = true;
            berserkTimer = savedTimer;
            applyBerserkSpeedModifier();
        }

        // Genopret departure-tilstand
        isDeparting = input.getBooleanOr("IsDeparting", false);
        departTimer = input.getIntOr("DepartTimer", 0);
        fullInventoryTimer = input.getIntOr("FullInventoryTimer", 0);

        // Sikr at mob'en aldrig loader som usynlig — NightStealthGoal genanvender usynlighed ved næste nat
        setInvisible(false);
    }

    /**
     * Dropper mob'ens bårne items ved død.
     * Kaldes automatisk af Minecraft som del af døds-logikken.
     * super.dropCustomDeathLoot() håndterer normale loot-tables.
     * Vi tilføjer derefter alle items mob'en bar på sig.
     * @param serverLevel      den server-verden mob'en døde i
     * @param damageSource     skadekilden der dræbte mob'en
     * @param recentlyHit     om mob'en blev ramt indenfor 5 sekunder (påvirker loot-chance)
     */
    @Override
    protected void dropCustomDeathLoot(ServerLevel serverLevel, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(serverLevel, damageSource, recentlyHit);
        for (ItemStack stack : carriedItems) {
            if (!stack.isEmpty()) {
                spawnAtLocation(serverLevel, stack);
            }
        }
    }

    /**
     * Tillader at mob'en kan lægges i snor (leash/lead).
     * Som standard kan monstre ikke lægges i snor, men vi ønsker at
     * spillere kan bruge Chest Thieves som "kiste-hunde": Led dem rundt
     * og de finder kister og kigger på dem, men stjæler ikke mens de er i snor.
     * @return altid true — Chest Thieves kan altid lægges i snor
     */
    @Override
    public boolean canBeLeashed() {
        return true;
    }

    /**
     * Forhindrer tilfældig afstandsbaseret despawn.
     * Chest Thieves despawner aldrig tilfældigt — kun via:
     *   1. Peaceful-mode (håndteres af Minecrafts standard Monster.checkDespawn())
     *   2. Departure-mekanismen (departTimer i tick())
     *   3. Død
     * Dette sikrer at loot kun går tabt via narrativ despawn, ikke tilfældigt.
     */
    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Getters og setters
    // -------------------------------------------------------------------------

    /**
     * Returnerer positionen på den kiste mob'en i øjeblikket sigter mod.
     * Bruges af LookAtTargetChestGoal og ChestThiefRenderer.
     * @return kistens position, eller null hvis mob'en ikke har et mål
     */
    @Nullable
    public BlockPos getTargetChestPos() {
        return targetChestPos;
    }

    /**
     * Sætter kiste-målet og opdaterer lastChestPos hvis positionen ikke er null.
     * Kaldes af FindAndStealFromChestGoal når mob'en vælger eller mister et mål.
     * @param pos den nye kiste-position, eller null for at rydde målet
     */
    public void setTargetChestPos(@Nullable BlockPos pos) {
        this.targetChestPos = pos;
        if (pos != null) {
            this.lastChestPos = pos; // husk den sidst besøgte kiste for LeaveAreaGoal
        }
    }

    /**
     * Returnerer positionen på den kiste mob'en sidst stjal fra.
     * Bruges af LeaveAreaGoal til at finde flygte-retningen.
     * @return den sidst besøgte kiste-position, eller null
     */
    @Nullable
    public BlockPos getLastChestPos() {
        return lastChestPos;
    }

    /**
     * Tjekker om mob'ens beholdning er fuld (alle slots optaget).
     * Bruges af FindAndStealFromChestGoal og LeaveAreaGoal.
     * @return true hvis alle bæreslots er fyldt
     */
    public boolean isInventoryFull() {
        for (ItemStack stack : carriedItems) {
            if (stack.isEmpty()) return false;
        }
        return true;
    }

    /**
     * Tilføjer et item til mob'ens beholdning (første ledige slot).
     * Tilføjer en kopi af stacken — originalen påvirkes ikke.
     * @param stack det item der skal tilføjes
     * @return true hvis der var plads, false hvis beholdningen er fuld
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean addCarriedItem(ItemStack stack) {
        for (int i = 0; i < carriedItems.size(); i++) {
            if (carriedItems.get(i).isEmpty()) {
                carriedItems.set(i, stack.copy());
                return true;
            }
        }
        return false; // beholdningen er fuld
    }

    /**
     * Returnerer mob'ens bæreinventar.
     * Bruges af PanicFleeGoal til at droppe ét tilfældigt item under panik,
     * og af dropCustomDeathLoot til at droppe alle items ved død.
     * @return den mutable liste af bårne items
     */
    public NonNullList<ItemStack> getCarriedItems() {
        return carriedItems;
    }

    /**
     * Returnerer om mob'en i øjeblikket er i panik-tilstand.
     * Bruges af PanicFleeGoal og LeaveAreaGoal.
     * @return true hvis mob'en er i panik
     */
    public boolean isPanicking() {
        return isPanicking;
    }

    /**
     * Returnerer den position mob'en flygter fra under panik.
     * Bruges af PanicFleeGoal til at beregne flygte-retningen.
     * @return angriber-positionen, eller null
     */
    @Nullable
    public BlockPos getPanicFleeFrom() {
        return panicFleeFrom;
    }

    /**
     * Afslutter panik-tilstanden.
     * Kaldes af PanicFleeGoal.stop() når panik-perioden er udløbet.
     */
    public void stopPanic() {
        isPanicking = false;
        panicFleeFrom = null;
        setInvisible(false); // stealth afbrydes øjeblikkeligt ved panik
    }

    /**
     * Starter berserker-mode: sætter speed-modifier, vælger mål og starter timer.
     * Kaldes fra provoke() ved 40% chance om dagen.
     * @param attacker angriberen der satte berserk i gang (bruges som første mål)
     */
    public void startBerserk(@Nullable LivingEntity attacker) {
        ChestThiefConfig config = ChestThiefConfig.getInstance();
        isBerserk = true;
        berserkTimer = config.getBerserkDurationTicks();
        applyBerserkSpeedModifier();
        setInvisible(false); // stealth afbrydes øjeblikkeligt ved berserk
        if (attacker != null) {
            setTarget(attacker);
        }
    }

    /**
     * Afslutter berserker-mode: fjerner speed-modifier og rydder mål.
     * Kaldes automatisk af tick() når berserkTimer løber ud.
     */
    public void stopBerserk() {
        isBerserk = false;
        berserkTimer = 0;
        var speedAttr = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(BERSERK_SPEED_ID);
        }
        setTarget(null);
    }

    /**
     * Tilføjer (eller opdaterer) attribut-modifikatoren der øger hastighed under berserker.
     * ADD_MULTIPLIED_BASE: amount = (multiplier - 1.0), dvs. 0.5 → +50% af base-hastighed.
     */
    private void applyBerserkSpeedModifier() {
        var speedAttr = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr == null) return;
        double multiplier = ChestThiefConfig.getInstance().getBerserkSpeedMultiplier();
        speedAttr.addOrUpdateTransientModifier(
                new AttributeModifier(BERSERK_SPEED_ID, multiplier - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
        );
    }

    /**
     * Returnerer om mob'en i øjeblikket er i berserker-mode.
     * Bruges af BerserkTargetGoal og PanicFleeGoal.
     * @return true hvis mob'en er i berserker-mode
     */
    public boolean isBerserk() {
        return isBerserk;
    }

    /**
     * Starter afrejse-tilstanden: tyven drager bort med loot og despawner efter departDurationTicks.
     * Kaldes automatisk fra tick() ved fuld beholdning over tid eller ved høj alder.
     */
    public void startDeparture() {
        if (isDeparting) return; // undgå dobbelt-aktivering
        isDeparting = true;
        departTimer = ChestThiefConfig.getInstance().getDepartDurationTicks();
    }

    /** Returnerer om tyven er i afrejse-tilstand. Bruges af DepartGoal, LeaveAreaGoal og FindAndStealFromChestGoal. */
    public boolean isDeparting() { return isDeparting; }

    /** Sættes af FindAndStealFromChestGoal — true mens mob'en aktivt stjæler fra en kiste. */
    public void setStealing(boolean stealing) { this.isStealing = stealing; }

    /** Returnerer om mob'en er i aktiv STEALING-state. Bruges af NightStealthGoal. */
    public boolean isStealing() { return isStealing; }

    /**
     * Tjekker om det er nat i den verden mob'en befinder sig i.
     * Minecraft-dagen er 24000 ticks lang:
     *   0     = solopgang
     *   6000  = middag
     *   13000 = solnedgang (her begynder "nat" i vores logik)
     *   18000 = midnat
     *   23000 = daggry (her slutter "nat" igen)
     * @return true hvis det er nat (ticks 13000–22999), ellers false
     */
    public boolean isNightTime() {
        if (this.level() == null) return false;
        long dayTime = this.level().getOverworldClockTime() % 24000;
        return dayTime >= 13000 && dayTime < 23000;
    }

}
