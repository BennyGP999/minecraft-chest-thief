package email.pedersen.entity.ai;

import email.pedersen.ChestCoordinator;
import email.pedersen.ChestTracker;
import email.pedersen.config.ChestThiefConfig;
import email.pedersen.entity.ChestThiefEntity;
import email.pedersen.ChestThiefSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AI-mål: Find den nærmeste kiste og stjæl det mest værdifulde item fra den.
 * Dette er det vigtigste "dagtids-mål" for Chest Thief. Det kører kun om dagen
 * og er inaktivt om natten, hvor mob'en i stedet angriber.
 * Adfærden er opdelt i tre tilstande (en state machine):
 *   SEARCHING → Spørg ChestTracker om den nærmeste kiste. Venter 2 sekunder
 *               mellem søgninger. Foretrækker kister der ikke allerede er
 *               "optaget" af en anden Chest Thief (via ChestCoordinator).
 *   MOVING    → Gå hen mod kisten. Tjek løbende at kisten stadig eksisterer.
 *               Når mob'en er inden for 4 blokke, åbn kisten og skift til STEALING.
 *               Registrer "krav" på kisten når mob'en er inden for 4 blokke,
 *               så andre mobs omdirigerer til en anden kiste.
 *   STEALING  → Stjæl det mest værdifulde item og erstat det med gulerødder.
 *               Vent chestInteractionIntervalTicks ticks mellem hvert stjæl.
 *               Når kisten er tom for værdifulde ting, marker den som "udtømt"
 *               og gå tilbage til SEARCHING for at finde den næste kiste.
 * Vigtige detaljer:
 *   - Bruger ChestTracker til at finde kister (ingen block-scanning = billig)
 *   - Bruger ChestCoordinator til at koordinere med andre Chest Thieves
 *   - Mens mob'en er i snor (leash): detekter men stjæl ikke
 *   - Genbruger avoidSet til at undgå allokering af nye objekter hver søgning
 */
public class FindAndStealFromChestGoal extends Goal {

    /** Den mob der ejer dette mål. */
    private final ChestThiefEntity mob;

    /** Config-instansen — bruges til radius, cooldown-tider osv. */
    private final ChestThiefConfig config;

    /** Positionen på den kiste mob'en i øjeblikket sigter mod. Null = ingen mål. */
    @Nullable
    private BlockPos targetChest = null;

    /** Nedtæller i ticks inden næste stjæl-forsøg. */
    private int stealCooldown = 0;

    /** Nedtæller i ticks inden næste kiste-søgning (så vi ikke søger hvert tick). */
    private int lookupCooldown = 0;

    /**
     * Kister der allerede er tømt for værdifulde ting i denne "runde".
     * Ryddes når målet starter forfra (start()-metoden).
     */
    private final Set<BlockPos> exhaustedChests = new HashSet<>();

    /**
     * Genbrugt sæt til søgningen — undgår at oprette et nyt objekt i hukommelsen
     * hver gang vi søger (sker hvert 40. tick pr. mob, potentielt mange mobs).
     */
    private final Set<BlockPos> avoidSet = new HashSet<>();

    /** Om kisten i øjeblikket er åben (animeret). Bruges til at lukke den korrekt. */
    private boolean chestIsOpen = false;

    /**
     * Den position mob'en skal stå ved siden af kisten (et af de 4 naboblokke).
     * Null hvis ingen gangbar naboposition fandtes — bruges da kistens centrum som fallback.
     */
    @Nullable
    private BlockPos standingPos = null;

    /**
     * Antal ticks navigation har været "done" uden at mob'en nåede kisten.
     * Bruges til at opdage at en kiste er utilgængelig (f.eks. bag en mur eller
     * en kløft der er for bred). Nulstilles når navigation kører, eller mob'en
     * klatrer på en stige (onClimbable() = true).
     */
    private int navStuckTicks = 0;

    /**
     * De tre tilstande i state machine'n.
     * Mob'en er altid i præcis én tilstand ad gangen.
     */
    private enum State { SEARCHING, MOVING, STEALING }
    private State state = State.SEARCHING;

    /**
     * @param mob den Chest Thief-entitet der ejer dette mål
     */
    public FindAndStealFromChestGoal(ChestThiefEntity mob) {
        this.mob = mob;
        this.config = ChestThiefConfig.getInstance();
        // MOVE-flag: dette mål bruger navigation (bevægelse).
        // Fortæller Minecraft at andre mål med MOVE-flag ikke kan køre samtidig.
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * Kan målet starte? Kun hvis beholdningen ikke er fuld.
     * Aktiv dag OG nat — tyven stjæler også om natten.
     * Når beholdningen er fuld, overtager LeaveAreaGoal (prioritet 4).
     */
    @Override
    public boolean canUse() {
        return !mob.isInventoryFull() && !mob.isDeparting();
    }

    /**
     * Kan målet fortsætte? Ikke fuld beholdning, ikke ved at drage bort, og der er noget at lave.
     */
    @Override
    public boolean canContinueToUse() {
        if (mob.isInventoryFull()) return false;
        if (mob.isDeparting()) return false;
        return targetChest != null || state == State.SEARCHING;
    }

    /**
     * Kører når målet starter (første gang, og efter solnedgang/solopgang).
     * Nulstiller al tilstand så vi starter frisk.
     */
    @Override
    public void start() {
        state = State.SEARCHING;
        lookupCooldown = 0;
        standingPos = null;
        navStuckTicks = 0;
        exhaustedChests.clear(); // glem alle tømte kister fra forrige runde
    }

    /**
     * Kører når målet stopper (f.eks. ved solnedgang, eller mob'en dør).
     * Sørger for at kisten lukkes og alt ryddes op korrekt.
     */
    @Override
    public void stop() {
        ChestCoordinator.release(mob.getUUID()); // frigiv eventuelt krav på en kiste
        if (chestIsOpen) {
            setChestOpen(targetChest, false);    // luk kisten så animationen er korrekt
            chestIsOpen = false;
        }
        targetChest = null;
        standingPos = null;
        mob.setTargetChestPos(null);
        mob.setStealing(false);
        state = State.SEARCHING;
        mob.getNavigation().stop();              // stop navigationen
    }

    /**
     * Kører hvert tick (20 gange/sekund) mens målet er aktivt.
     * Opdaterer isStealing-flag så NightStealthGoal kan reagere korrekt,
     * og delegerer til den relevante tilstands-metode.
     */
    @Override
    public void tick() {
        mob.setStealing(state == State.STEALING);
        switch (state) {
            case SEARCHING -> tickSearching();
            case MOVING    -> tickMoving();
            case STEALING  -> tickStealing();
        }
    }

    /**
     * SEARCHING-tilstand: Find den nærmeste kiste og sæt kurs mod den.
     * Søger ikke hvert tick — venter 40 ticks (2 sekunder) mellem søgninger,
     * da det er unødvendigt at søge oftere.
     * Logik:
     *   1. Byg en liste over kister vi vil undgå (udtømte + andre mobs' krav)
     *   2. Spørg ChestTracker om den nærmeste kiste uden for listen
     *   3. Hvis alle er optaget: find den nærmeste ignorer krav (men ikke udtømte)
     *   4. Bekræft at kisten stadig eksisterer i verden (kan være sprunget i luften)
     *   5. Start navigation mod kisten og skift til MOVING
     */
    private void tickSearching() {
        if (lookupCooldown > 0) {
            lookupCooldown--;
            return;
        }
        lookupCooldown = 40; // søg igen om 2 sekunder

        // Byg sæt over positioner vi vil undgå (genbrugger avoidSet for at spare hukommelse)
        avoidSet.clear();
        avoidSet.addAll(exhaustedChests);                    // kister der allerede er tømt
        avoidSet.addAll(ChestCoordinator.getClaimedPositions()); // kister andre mobs er på vej til

        int maxVertical = config.getChestDetectionMaxVerticalDist();
        BlockPos nearest = ChestTracker.findNearest(mob.level(), mob.blockPosition(),
                config.getChestDetectionRadius(), maxVertical, avoidSet);

        if (nearest == null) {
            // Alle kister er optaget — find den nærmeste og ignorer krav (men ikke udtømte)
            nearest = ChestTracker.findNearest(mob.level(), mob.blockPosition(),
                    config.getChestDetectionRadius(), maxVertical, exhaustedChests);
        }

        if (nearest != null) {
            // Bekræft at kisten stadig er der (kan være ødelagt siden den blev tracked)
            if (isValidChest(nearest)) {
                targetChest = nearest;
                standingPos = findBestAdjacentPos(nearest);
                mob.setTargetChestPos(nearest);
                state = State.MOVING;
                navigateToChest();
            } else {
                // Forældet optagelse — fjern den fra trackeren
                ChestTracker.removeChest(mob.level(), nearest);
            }
        } else {
            // Ingen kister tilgængelige — vandre tilfældigt i stedet for at stå stille.
            //
            // Problemet uden dette: FindAndStealFromChestGoal holder MOVE-flagget aktivt
            // selvom den ikke laver noget (state = SEARCHING, ingen kiste fundet). Det blokerer
            // WaterAvoidingRandomStrollGoal (prioritet 7), som aldrig får lov at køre, og
            // tyven bare stirrer fremad.
            //
            // Løsning: start en kort tilfældig vandring selv. Mob'en ser naturlig ud,
            // og lookupCooldown sørger for at vi tjekker igen om 2 sekunder.
            if (mob.getNavigation().isDone()) {
                // Vælg en tilfældig retning (0–2π radianer = fuld cirkel) og en afstand (6–14 blokke).
                // Polær-til-kartesisk konvertering: angle er vinklen fra X-aksen i XZ-planet,
                //   tx = mob.getX() + cos(angle) * dist  →  X-komponenten af målpositionen
                //   tz = mob.getZ() + sin(angle) * dist  →  Z-komponenten af målpositionen
                // Det giver et jævnt fordelt punkt på en ring rundt om mob'en.
                double angle = mob.getRandom().nextDouble() * 2 * Math.PI;
                double dist = 6 + mob.getRandom().nextDouble() * 8; // 6–14 blokke væk
                double tx = mob.getX() + Math.cos(angle) * dist;
                double tz = mob.getZ() + Math.sin(angle) * dist;
                mob.getNavigation().moveTo(tx, mob.getY(), tz, 0.8);
            }
        }
    }

    /**
     * MOVING-tilstand: Gå hen mod kisten.
     * Tjekker løbende:
     *   - At kisten stadig eksisterer (kan være ødelagt undervejs)
     *   - Om mob'en er nær nok til at interagere (distancen i blokke²)
     *   - Om navigationen er gået i stå (re-path i så fald)
     * Distancer er kvadrerede (distSq) for at undgå kvadratrod-beregning (hurtigere).
     *   distSq ≤ 4.0  = inden for 2 blokke = nær nok til at interagere
     *   distSq ≤ 16.0 = inden for 4 blokke = tæt nok til at registrere krav
     */
    private void tickMoving() {
        if (targetChest == null) {
            state = State.SEARCHING;
            return;
        }

        // Kisten er forsvundet (sprunget i luften, spilleren ødelagde den osv.)
        if (!isValidChest(targetChest)) {
            ChestTracker.removeChest(mob.level(), targetChest);
            targetChest = null;
            mob.setTargetChestPos(null);
            state = State.SEARCHING;
            return;
        }

        double distSq = mob.blockPosition().distSqr(targetChest);

        // Inden for 2 blokke OG direkte adjacent til kisten: åbn kisten og skift til STEALING.
        // Vi tjekker BEGGE betingelser for at forhindre "tyveri-igennem-væg":
        //   - distSq <= 4.0 alene er utilstrækkeligt i labyrint-layout, fordi mob'en kan
        //     være inden for 2 blokke af kisten men adskilt af en blokvæg.
        //   - isAdjacentToChest() kræver at mob'en er præcis 1 blok fra kisten i en
        //     kardinalretning (N/S/E/W) — der er fysisk ingen plads til en hel blok-væg
        //     imellem dem, så dette er en tilstrækkelig garanti mod tyveri igennem vægge.
        //   - Vi tjekker IKKE standingPos her — mob'en kan ankomme fra en hvilken som helst
        //     side i labyrinten, og standingPos beregnes fra startpositionen og matcher
        //     ikke nødvendigvis den retning mob'en faktisk navigerede.
        if (distSq <= 4.0 && isAdjacentToChest()) {
            // Stop navigation FØR tilstandsskift: mob'en har resterende bevægelsesfart fra
            // navigation. Uden stop() vil den fortsætte fremad og klatre op på kisten
            // (der er 0.875 blokke høj — inden for hoppehøjde). navigation.stop() afregistrerer
            // den aktive rute og lader friktionen i travel() bremse mob'en på stedet.
            mob.getNavigation().stop();
            ChestCoordinator.claim(mob.getUUID(), targetChest); // marker kisten som "vores"
            setChestOpen(targetChest, true);                    // åbn kiste-animationen
            chestIsOpen = true;
            mob.setInvisible(false);                           // usynlighed afbrydes ved tyveri
            state = State.STEALING;
            stealCooldown = config.getChestInteractionIntervalTicks();
            return;
        }

        // Inden for 4 blokke: registrer krav så andre mobs omdirigerer nu
        if (distSq <= 16.0) {
            ChestCoordinator.claim(mob.getUUID(), targetChest);
        }

        // ── STUCK-DETEKTION ─────────────────────────────────────────────────────
        // Tæller ticks hvor navigationen ikke kører (isDone = true) uden at mob'en
        // er nær nok til at stjæle. Det sker typisk fordi pathfinderen ikke kan
        // finde en rute — kisten er bag en for bred kløft, en massiv væg, eller
        // en blokering vi ikke kan løse.
        //
        // Undtagelse: mens mob'en klatrer (onClimbable = true) bruger vi ikke
        // pathfinder-navigationen — vi sætter deltaMovement direkte. Disse ticks
        // tæller ikke som "stuck" selvom isDone() er sandt.
        //
        // Efter ~10 sekunder (200 ticks) giver vi op: marker kisten som udtømt
        // og søg efter en ny kiste i stedet for at stå stille for evigt.
        if (!mob.getNavigation().isDone() || mob.onClimbable()) {
            navStuckTicks = 0;
        } else {
            navStuckTicks++;
            if (navStuckTicks >= 200) {
                exhaustedChests.add(targetChest.immutable()); // undgå samme kiste i denne runde
                ChestCoordinator.release(mob.getUUID());      // frigiv kravet så andre mobs kan prøve
                targetChest = null;
                mob.setTargetChestPos(null);
                navStuckTicks = 0;
                state = State.SEARCHING;
                return;
            }
        }

        // Navigationen er færdig men vi ankom ikke — find ny vej
        if (mob.getNavigation().isDone()) {

            // ── STIGE-KLATRING (fase 1 + 2) ────────────────────────────────────────
            // Denne blok køres FØR stige-fallback og navigateToChest() fordi
            // getMoveControl().setWantedPosition() og setDeltaMovement() her virker
            // i SAMME tick (goalSelector kører før MoveControl.tick() og travel() i aiStep()).
            // Sætter vi deltaMovement EFTER super.tick() (i ChestThiefEntity.tick()) virker
            // det først næste tick, og travel() kan overskrive det inden da.
            if (targetChest != null && targetChest.getY() > mob.getBlockY()) {

                if (mob.onClimbable()) {
                    // Fase 2: mob er inde i stige-blokken.
                    mob.getNavigation().stop();

                    BlockPos above = mob.blockPosition().above();
                    if (mob.level().getBlockState(above).is(BlockTags.CLIMBABLE)) {
                        // Midt på stigen — flere trin over os.
                        // Stop horisontal navigation og sæt Y-velocity direkte så
                        // travel() bruger (0, 0.2, 0) som udgangspunkt DENNE tick.
                        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
                        mob.setDeltaMovement(0, 0.2, 0);
                    } else {
                        // Øverst på stigen — ingen stige over os.
                        //
                        // Problemet med fortsat opadgående push (0, 0.2, 0) her:
                        // mob'en forlader det øverste stige-trin (onClimbable → false),
                        // tyngdekraften trækker den ned igen til det samme trin, og
                        // cyklen gentager sig i en uendelig løkke.
                        //
                        // Løsning: skift til horisontal MoveControl mod kisten, kombineret
                        // med et lille opadgående skub der hjælper mob'en over kanten og
                        // ud på platformsniveau. Travel() kombinerer begge dele og mob'en
                        // skrider af stigen sideværts i stedet for at hoppe på stedet.
                        mob.getMoveControl().setWantedPosition(
                                targetChest.getX() + 0.5, mob.getY(),
                                targetChest.getZ() + 0.5, 1.2);
                        mob.setDeltaMovement(0, Math.max(mob.getDeltaMovement().y, 0.15), 0);
                    }
                    return;
                }

                // Fase 1: søg i alle 8 retninger (kardinal + diagonal) efter klatrebar nabo.
                // Diagonal-tjek er nødvendigt fordi pathfinderens done-tærskel kan stoppe
                // mob'en ved hjørnet af approach-blokken — for eksempel ved blockPos (9,y,8)
                // når approach er (10,y,8) og stigen er (10,y,9). Fra hjørneblokken er stigen
                // diagonalt placeret og fanges ikke af et rent kardinal-tjek.
                BlockPos mobPos = mob.blockPosition();
                for (int ddx = -1; ddx <= 1; ddx++) {
                    for (int ddz = -1; ddz <= 1; ddz++) {
                        if (ddx == 0 && ddz == 0) continue;
                        BlockPos nb = mobPos.offset(ddx, 0, ddz);
                        if (mob.level().getBlockState(nb).is(BlockTags.CLIMBABLE)) {
                            // Brug MoveControl — kører FØR travel() i SAMME tick via aiStep()-rækkefølgen:
                            //   1. goalSelector.tick() (her)  2. navigation.tick()
                            //   3. moveControl.tick()          4. travel()
                            // MoveControl oversætter setWantedPosition() til bevægelsesinput som
                            // travel() bruger — mobens bevægelse mod stigen sker STRAKS denne tick.
                            mob.getMoveControl().setWantedPosition(
                                    nb.getX() + 0.5, nb.getY(), nb.getZ() + 0.5, 1.2);
                            return; // ingen navigateToChest() — vi er ved at træde på stigen
                        }
                    }
                }
            }

            // ── STIGE-FALLBACK ──────────────────────────────────────────────────────
            // Kisten er ovenover men vi er ikke nær en stige endnu.
            // Find den nærmeste klatrebar blok og naviger til tilgangsblokken foran den.
            // (Kun aktiv når kisten er >1 blok ovenover og vi ikke er på stigen endnu.)
            if (targetChest != null
                    && targetChest.getY() > mob.getBlockY() + 1
                    && !mob.onClimbable()) {
                int hdx = Math.abs(mob.getBlockX() - targetChest.getX());
                int hdz = Math.abs(mob.getBlockZ() - targetChest.getZ());
                if (hdx <= 8 && hdz <= 8) {
                    BlockPos climbable = findNearbyClimbable();
                    if (climbable != null) {
                        // For LadderBlock: naviger til tilgangsblokken én blok foran stigens
                        // åbne side (FACING.getOpposite()). Det giver mob'en en direkte
                        // tilgangsvinkel og undgår diagonal-stop ved stigens hjørne.
                        BlockPos navTarget = climbable;
                        BlockState climbState = mob.level().getBlockState(climbable);
                        if (climbState.getBlock() instanceof LadderBlock) {
                            Direction facing = climbState.getValue(LadderBlock.FACING);
                            // FACING = retning mod væggen; .getOpposite() = åben tilgangsside
                            BlockPos approachPos = climbable.relative(facing.getOpposite());
                            BlockState approachState = mob.level().getBlockState(approachPos);
                            if (approachState.getCollisionShape(mob.level(), approachPos).isEmpty()) {
                                navTarget = approachPos;
                            }
                        }
                        mob.getNavigation().moveTo(
                                navTarget.getX() + 0.5,
                                navTarget.getY(),
                                navTarget.getZ() + 0.5,
                                1.0
                        );
                        return;
                    }
                }
            }

            // ── HEGN-LÅG-FALLBACK ──────────────────────────────────────────────────
            // Pathfinderen behandler lukkede hegn-låger som BLOCKED og kan ikke
            // planlægge en komplet rute igennem labyrinten forbi dem. I stedet
            // returnerer den den bedste delvise rute — til det tættest tilgængelige
            // punkt, som typisk er et hjørne af labyrinten tæt på kisten, men ikke
            // foran lågen. Mob'en stopper der og OpenGateGoal aktiverer aldrig.
            //
            // Løsning, to tilfælde:
            //
            //   A) Mob'en er IKKE foran lågen endnu:
            //      naviger til approach-positionen (1 blok foran lågen, mob-side).
            //      Den vej er fri — den er på mob-siden af lågen i korridoren.
            //
            //   B) Mob'en ER ved approach-positionen (approach == blockPosition()):
            //      returnér uden at kalde navigateToChest(). Mob'en venter stille
            //      mens OpenGateGoal opdager lågen 1 blok væk og åbner den.
            //      Vigtigt: vi starter IKKE ny navigation her — det ville sende
            //      mob'en væk og skabe oscillation.
            //
            // Vi tjekker fra første stuck-tick (navStuckTicks >= 1) — en tærskel
            // på > 3 ville køre navigateToChest() de første ticks og sende mob'en
            // tilbage til nærmeste-til-kisten-punkt inden OpenGateGoal når at reagere.
            if (navStuckTicks >= 1) {
                BlockPos gateApproach = findGateApproachNearby();
                if (gateApproach != null) {
                    // Nulstil tælleren — dette er aktiv ventetid, ikke "stuck"
                    navStuckTicks = 0;
                    if (!gateApproach.equals(mob.blockPosition())) {
                        // Tilfælde A: navigér hen til approach-positionen
                        mob.getNavigation().moveTo(
                                gateApproach.getX() + 0.5,
                                gateApproach.getY(),
                                gateApproach.getZ() + 0.5,
                                1.0
                        );
                    }
                    // Tilfælde B (mob er allerede ved approach): vent — OpenGateGoal åbner lågen
                    return;
                }
            }

            navigateToChest();
        }
    }

    /**
     * STEALING-tilstand: Stjæl items fra kisten med jævne mellemrum.
     * Håndterer:
     *   - Leash: Mob er i snor → luk kisten og gå til SEARCHING (ingen stjæleri)
     *   - Validering: Kisten er væk → gå til SEARCHING
     *   - For langt væk: Mob er skubbet væk → luk kisten og gå til MOVING
     *   - Cooldown: Vent N ticks mellem hvert stjæl (fra config)
     *   - Tom kiste: Ingen flere værdifulde ting → marker udtømt og gå til SEARCHING
     */
    private void tickStealing() {
        if (targetChest == null) {
            state = State.SEARCHING;
            return;
        }

        // Mob er i snor: detekter kisten men stjæl ikke — luk kisten og reset
        if (mob.isLeashed()) {
            if (chestIsOpen) {
                setChestOpen(targetChest, false);
                chestIsOpen = false;
            }
            targetChest = null;
            mob.setTargetChestPos(null);
            state = State.SEARCHING;
            return;
        }

        // Tjek at kisten stadig har en ChestBlockEntity (dvs. er en rigtig kiste)
        Level level = mob.level();
        if (!(level.getBlockEntity(targetChest) instanceof ChestBlockEntity chest)) {
            ChestTracker.removeChest(level, targetChest);
            targetChest = null;
            mob.setTargetChestPos(null);
            state = State.SEARCHING;
            return;
        }

        // Mob er skubbet for langt væk (f.eks. af en spiller eller eksplosion)
        if (mob.blockPosition().distSqr(targetChest) > 9.0) {
            if (chestIsOpen) {
                setChestOpen(targetChest, false); // luk kisten inden vi går
                chestIsOpen = false;
            }
            state = State.MOVING; // gå tilbage og prøv igen
            return;
        }

        // Vent på cooldown inden næste stjæl
        if (stealCooldown > 0) {
            stealCooldown--;
            return;
        }

        // Stjæl det mest værdifulde item!
        stealFromChest(chest);
        stealCooldown = config.getChestInteractionIntervalTicks();

        // Beholdningen er fuld efter det netop stjålne item → stop her, LeaveAreaGoal overtager
        if (mob.isInventoryFull()) {
            setChestOpen(targetChest, false);
            chestIsOpen = false;
            ChestCoordinator.release(mob.getUUID());
            targetChest = null;
            mob.setTargetChestPos(null);
            state = State.SEARCHING;
            lookupCooldown = 40; // kort pause inden næste søgning (hvis beholdningen tømmes igen)
            return;
        }

        // Ingen flere værdifulde ting i kisten → marker udtømt og find næste kiste
        if (!hasValuableItems(chest)) {
            setChestOpen(targetChest, false);
            chestIsOpen = false;
            exhaustedChests.add(targetChest.immutable()); // immutable() = lav en fast kopi af positionen
            ChestCoordinator.release(mob.getUUID());       // frigiv kravet
            targetChest = null;
            mob.setTargetChestPos(null);
            state = State.SEARCHING;
            lookupCooldown = 0; // søg straks efter næste kiste
        }
    }

    /**
     * Navigerer mob'en mod den horisontale naboretning til kisten der er tættest
     * på mob'ens nuværende position.
     * Hvorfor IKKE kistens centrum:
     *   moveTo(chestX, chestY, chestZ) med accuracy=1 lader pathfinderen acceptere
     *   enhver node inden for 1 blok af kisten — inklusiv toppen. Pathfinderen kan
     *   vælge toppen som den "billigste" node (f.eks. hvis kisten er i et hjørne),
     *   og mob'en ender på toppen i stedet for foran.
     * Hvorfor nærmeste side (ikke kistens FACING):
     *   I en labyrint ankommer mob'en fra den side labyrinten tillader. Hvis vi
     *   naviger mod kistens forside (FACING), kan pathfinderen prøve at rute
     *   mob'en hele vejen rundt — hvilket fejler hvis vægge spærrer. Vi bruger
     *   i stedet den tilgængelige naboretning der er tættest på mob'ens
     *   nuværende position, og re-beregner den ved hvert kald (mob'ens position
     *   ændrer sig mens den navigerer labyrinten).
     * Fallback: hvis ingen horisontal nabo er gangbar (kisten er omgivet), lader
     * vi pathfinderen finde vejen selv som før.
     */
    private void navigateToChest() {
        if (targetChest == null) return;

        Level level = mob.level();
        BlockPos mobPos = mob.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = targetChest.relative(dir);
            // Tre-lags gangbarhedstjek — alle tre skal bestå:
            //   adj         (fødder)  — selve blokken må ikke have kollisionform (ikke solid)
            //   adj.above() (hoved)   — blokken over fødder skal også være fri (mob er 2 blokke høj)
            //   adj.below() (underlag)— blokken under fødder skal have fast overside (noget at stå på)
            if (!level.getBlockState(adj).getCollisionShape(level, adj).isEmpty()) continue;
            if (!level.getBlockState(adj.above()).getCollisionShape(level, adj.above()).isEmpty()) continue;
            if (!level.getBlockState(adj.below()).isFaceSturdy(level, adj.below(), Direction.UP)) continue;
            double dist = mobPos.distSqr(adj);
            if (dist < bestDist) {
                bestDist = dist;
                best = adj;
            }
        }

        if (best != null) {
            // Brug createPath() med accuracy=0 (præcis position) i stedet for den sædvanlige
            // moveTo(x, y, z, speed) der internt kalder createPath(..., 1).
            //
            // Problemet med accuracy=1: pathfinderen betragter enhver node inden for
            // Manhattan-afstand 1 fra målet som et acceptabelt slutpunkt. Det betyder
            // at en blok 2 skridt fra kisten (Manhattan-afstand 1 fra den adjacent blok)
            // er tilstrækkelig — pathfinderen stopper der og mob'en bevæger sig aldrig
            // til den adjacent blok.
            //
            // Med accuracy=0 SKAL pathfinderen nå præcis den adjacent blok. Mob'en
            // ankommer til den korrekte position, og navigation.stop() i STEALING-
            // transitionen forhindrer den i at gå videre op på kisten (step-height 1.0
            // > kiste-højde 0.875 = mob kan ellers træde direkte op).
            Path path = mob.getNavigation().createPath(best, 0);
            if (path != null) {
                mob.getNavigation().moveTo(path, 1.0);
                return;
            }
            // createPath returnerede null (ingen rute til præcis adjacent blok) —
            // prøv med standard accuracy=1 mod samme punkt som fallback
            mob.getNavigation().moveTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, 1.0);
        } else {
            // Ingen gangbar nabo fundet — lad pathfinderen navigere til kistens centrum
            mob.getNavigation().moveTo(targetChest.getX() + 0.5, targetChest.getY(), targetChest.getZ() + 0.5, 1.0);
        }
    }

    /**
     * Søger efter en klatrebar blok (stige, lian, stillads) inden for en radius
     * på 4 blokke rundt om mob'en, på mob'ens aktuelle Y-niveau eller én blok over.
     * Bruges som fallback i tickMoving() når kisten er ovenover og normal
     * pathfinding ikke kan finde en rute op — f.eks. fordi den eneste adgang
     * er via en stige. Vi finder stigens position og navigerer tyven derhen
     * så den kan træde op og begynde at klatre.
     * Vi tjekker kun Y=0 og Y+1 relativt til mob'en: stiger der er mere end
     * én blok over gulvniveau ville kræve et hop for at nå, og det håndterer
     * vi ikke her.
     * @return positionen på en klatrebar blok, eller null hvis ingen fundet
     */
    /**
     * Scanner op til 8 blokke rundt om mob'en for en lukket hegn-lås, og returnerer
     * positionen 1 blok foran lågen på mob-siden (approach-position).
     * Bruges som fallback i tickMoving() når mob'en sidder fast fordi pathfinderen
     * ikke kan planlægge igennem en lukket lås. Pathfinderen returnerer da en delvis
     * rute der slutter langt fra kisten — approach-positionen er dog tilgængelig
     * (den er PÅ mob-siden af lågen, i den korridor mob'en allerede er i).
     * Hvis der er flere lukkede låger i nærheden, vælges den der peger mest i
     * retning mod kisten (størst prikprodukt af vektor-til-lås og vektor-til-kiste).
     * @return approach-position foran den nærmeste relevante lås, eller null
     */
    @Nullable
    private BlockPos findGateApproachNearby() {
        if (targetChest == null) return null;
        BlockPos center = mob.blockPosition();
        Level level = mob.level();

        // Retningsvektor fra mob mod kisten — bruges til at vælge den mest relevante lås
        double toChestX = targetChest.getX() - center.getX();
        double toChestZ = targetChest.getZ() - center.getZ();

        BlockPos bestApproach = null;
        double bestDot = Double.NEGATIVE_INFINITY;

        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(check);
                    if (!(state.getBlock() instanceof FenceGateBlock)) continue;
                    if (!state.hasProperty(BlockStateProperties.OPEN)) continue;
                    if (state.getValue(BlockStateProperties.OPEN)) continue; // allerede åben

                    // Approach-position: 1 blok foran lågen, på mob-siden
                    BlockPos approach = gateApproachPos(check, state, center);
                    if (approach == null) continue;

                    // Prikprodukt af (vektor fra mob til lås) · (vektor fra mob til kiste).
                    // Prikproduktet er størst når de to vektorer peger i samme retning —
                    // dvs. når lågen ligger "på vejen" mod kisten. Negative værdier betyder
                    // at lågen er bag mob'en (modsat kiste-retningen). Vi vælger den lås
                    // med det største prikprodukt, så vi prioriterer den lås der er mest
                    // "i retning mod kisten" og undgår at vælge irrelevante naboer.
                    double gx = check.getX() - center.getX();
                    double gz = check.getZ() - center.getZ();
                    double dot = gx * toChestX + gz * toChestZ;
                    if (dot > bestDot) {
                        bestDot = dot;
                        bestApproach = approach;
                    }
                }
            }
        }
        return bestApproach;
    }

    /**
     * Beregner approach-positionen for en hegn-lås: den blok 1 trin foran lågen
     * langs passage-aksen, på den side der er tættest på mob'en.
     * En lås med FACING langs Z-aksen (NORTH/SOUTH) passeres i Z-retningen.
     *   Approach-siderne er gatePos.north() og gatePos.south().
     * En lås med FACING langs X-aksen (EAST/WEST) passeres i X-retningen.
     *   Approach-siderne er gatePos.east() og gatePos.west().
     * Vi vælger den af de to sider der er tættest på mob'en.
     * @param gatePos   position på hegn-låsen
     * @param state     blokstate (bruges til at læse FACING)
     * @param mobPos    mob'ens nuværende blokposition
     * @return approach-position, eller null hvis ingen af siderne er gangbar
     */
    @Nullable
    private BlockPos gateApproachPos(BlockPos gatePos, BlockState state, BlockPos mobPos) {
        Direction facing = state.getValue(FenceGateBlock.FACING);
        BlockPos sideA, sideB;
        if (facing.getAxis() == Direction.Axis.Z) {
            sideA = gatePos.north(); // (0, 0, -1)
            sideB = gatePos.south(); // (0, 0, +1)
        } else {
            sideA = gatePos.east();  // (+1, 0, 0)
            sideB = gatePos.west();  // (-1, 0, 0)
        }
        // Vælg den side der er tættest på mob'en
        return sideA.distSqr(mobPos) <= sideB.distSqr(mobPos) ? sideA : sideB;
    }

    @Nullable
    private BlockPos findNearbyClimbable() {
        BlockPos center = mob.blockPosition();
        Level level = mob.level();
        int radius = 4;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    if (level.getBlockState(check).is(BlockTags.CLIMBABLE)) {
                        return check;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finder den bedst egnede naboposition til at stå ved kisten.
     * Prioritetsrækkefølge baseret på kistens åbningsretning (FACING):
     *   1. Foran kisten — der hvor låget åbner mod (score 3)
     *   2. Ved siden af kisten — de to retninger vinkelret på FACING (score 2)
     *   3. Bagved kisten (score 1)
     * En position er kun gangbar hvis:
     *   - Selve blokken er ikke solid (mob'en kan stå her)
     *   - Blokken ovenover er ikke solid (plads til hoved)
     *   - Blokken nedenunder har en solid overside (fast underlag)
     * Hvis to gangbare positioner har samme score, vælges den tættest på mob'en.
     * Returnerer null hvis ingen naboretning er gangbar — caller bruger
     * da kistens centrum som fallback.
     * @param chestPos kistens position
     * @return den bedste stå-position, eller null
     */
    @Nullable
    private BlockPos findBestAdjacentPos(BlockPos chestPos) {
        Level level = mob.level();
        BlockPos mobPos = mob.blockPosition();

        // Aflæs kistens åbningsretning fra block state.
        // ChestBlock.FACING angiver hvilken retning kistens "mund" vender mod —
        // den retning spilleren normalt står i for at åbne kisten.
        BlockState chestState = level.getBlockState(chestPos);
        Direction chestFacing = chestState.hasProperty(ChestBlock.FACING)
                ? chestState.getValue(ChestBlock.FACING)
                : null;

        BlockPos best = null;
        int bestScore = -1;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = chestPos.relative(dir);

            // Gangbarhedstjek — getCollisionShape().isEmpty() erstatter den deprecated isSolid()
            if (!level.getBlockState(adj).getCollisionShape(level, adj).isEmpty()) continue;
            if (!level.getBlockState(adj.above()).getCollisionShape(level, adj.above()).isEmpty()) continue;
            if (!level.getBlockState(adj.below()).isFaceSturdy(level, adj.below(), Direction.UP)) continue;

            // Beregn score ud fra retning relativt til kistens åbning
            int score;
            if (chestFacing == null) {
                score = 2; // ingen facing-info — alle gangbare sider er ligeværdige
            } else if (dir == chestFacing) {
                score = 3; // foran kisten — bedste position
            } else if (dir == chestFacing.getOpposite()) {
                score = 1; // bagved kisten — næstsidste valg
            } else {
                score = 2; // ved siden af (clockwise eller counterclockwise)
            }

            double dist = mobPos.distSqr(adj);
            // Højere score vinder; ved uafgjort vinder den tættest på mob'en
            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                bestScore = score;
                bestDist = dist;
                best = adj;
            }
        }
        return best;
    }

    /**
     * Tjekker om mob'en er direkte adjacent til kisten — præcis 1 blok væk i én
     * kardinalretning (nord, syd, øst eller vest), max 1 blok lodret forskel.
     * Dette er sikringen mod "tyveri-igennem-væg":
     *   Hvis mob'en er 1 blok fra kisten i en kardinalretning, er der bogstaveligt
     *   ingen plads til en solid blok-væg imellem dem — de er direkte naboer.
     *   En distance på 2 blokke (distSq = 4.0) derimod tillader netop én blok-tykkelse
     *   væg imellem, og det var præcis det scenarie der forårsagede labyrint-buggen.
     * Vi tjekker ikke standingPos her med vilje: standingPos beregnes fra mob'ens
     * startposition og matcher ikke nødvendigvis den side mob'en faktisk navigerer til
     * i en labyrint. Mob'en ankommer fra den side labyrinten tillader, og den kan stjæle
     * fra en hvilken som helst kardinalretning ved kisten.
     * @return true hvis mob'en er direkte adjacent til kisten
     */
    private boolean isAdjacentToChest() {
        if (targetChest == null) return false;
        BlockPos mobPos = mob.blockPosition();
        int dx = Math.abs(mobPos.getX() - targetChest.getX());
        int dz = Math.abs(mobPos.getZ() - targetChest.getZ());
        int dy = Math.abs(mobPos.getY() - targetChest.getY());
        // Kardinal-adjacent: præcis 1 blok i enten X eller Z (ikke diagonalt), max 1 lodret
        return dy <= 1 && ((dx == 1 && dz == 0) || (dx == 0 && dz == 1));
    }

    /**
     * Åbner eller lukker kiste-animationen ved at sende en block-event til serveren.
     * Block-eventet styrer kistens lid-animation på klient-siden, men afspiller
     * IKKE automatisk kiste-krakelydene — dem skal vi selv afspille eksplicit.
     * Vanilla-Minecraft afspiller lyden i ChestBlockEntity.startOpen() som vi
     * ikke kalder, så vi gør det manuelt her i stedet.
     * Når open = true:
     *   1. Block-event → kistens lid åbner (animation)
     *   2. CHEST_OPEN  → standard kiste-krakelyd fra kistens position
     *   3. OPEN_CHEST  → mob'ens egen "opdagelse"-lyd fra mob'ens position
     * @param pos  positionen på kisten, eller null (gør ingenting)
     * @param open true for at åbne, false for at lukke
     */
    private void setChestOpen(@Nullable BlockPos pos, boolean open) {
        if (pos == null) return;
        if (!(mob.level() instanceof ServerLevel serverLevel)) return;
        var blockState = serverLevel.getBlockState(pos);
        if (blockState.getBlock() instanceof ChestBlock) {
            // Trigger kistens lid-animation (håndteres af ChestBlockEntity client-side)
            serverLevel.blockEvent(pos, blockState.getBlock(), 1, open ? 1 : 0);

            if (open) {
                // Kiste-krakelyd fra kistens position (vanilla spiller ikke denne automatisk via blockEvent)
                serverLevel.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);

                // Mob'ens "opdagelse"-lyd — spilles fra mob'ens position
                serverLevel.playSound(
                        null,
                        mob.blockPosition(),
                        ChestThiefSounds.OPEN_CHEST,
                        SoundSource.HOSTILE,
                        0.6f,
                        0.85f + mob.getRandom().nextFloat() * 0.3f // pitch 0.85–1.15
                );
            } else {
                // Kiste-lukkelyd fra kistens position
                serverLevel.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1.0f);
            }
        }
    }

    /**
     * Spiller mob'ens begejstringslyd når den finder og stjæler et item.
     * Bruger ChestThiefSounds.STEAL_ITEM — det brugerdefinerede lyd-event
     * der er defineret i assets/chest_thief/sounds.json.
     * Pitchen varieres tilfældigt (0.9–1.1) så lyden ikke lyder identisk
     * hver gang der stjæles fra en kiste.
     * Lydfilen kan skiftes ved at lægge steal_item.ogg i
     * assets/chest_thief/sounds/ og opdatere sounds.json.
     */
    private void playExcitementSound() {
        if (!(mob.level() instanceof ServerLevel serverLevel)) return;
        serverLevel.playSound(
                null,
                mob.blockPosition(),
                ChestThiefSounds.STEAL_ITEM,
                SoundSource.HOSTILE,
                0.6f,
                0.9f + mob.getRandom().nextFloat() * 0.2f // pitch 0.9–1.1
        );
    }

    /**
     * Tjekker om der stadig er en gyldig kiste på den givne position.
     * En gyldig kiste kræver to ting:
     *   1. Blokken er stadig en ChestBlock (ikke ødelagt)
     *   2. Der er stadig en ChestBlockEntity (indholdet er intakt)
     * @param pos  positionen der skal tjekkes
     * @return true hvis der er en gyldig kiste, ellers false
     */
    private boolean isValidChest(BlockPos pos) {
        Level level = mob.level();
        return level.getBlockState(pos).getBlock() instanceof ChestBlock
                && level.getBlockEntity(pos) instanceof ChestBlockEntity;
    }

    /**
     * Stjæler det mest værdifulde item fra kisten og erstatter det med gulerødder.
     * Algoritme:
     *   1. Gå igennem alle 27 pladser i kisten
     *   2. Slå item-id'et op i config's værdiliste (f.eks. "minecraft:diamond" = 500)
     *   3a. stealOnlyListedItems = false (standard):
     *       Items der ikke er i listen og ikke er gulerødder tildeles minimumsværdi 1,
     *       så alt kan stjæles — listen styrer kun prioriteringsrækkefølgen.
     *   3b. stealOnlyListedItems = true:
     *       Items der ikke er i listen springes over — kun listede items stjæles.
     *   4. Tag pladsen med den højeste værdi og erstat det med gulerødder.
     * Gulerødder vælges som erstatning fordi de er harmløse og signalerer
     * at noget er stjålet ("betaling" fra tyvens perspektiv).
     * @param chest den kiste der skal stjæles fra
     */
    private void stealFromChest(ChestBlockEntity chest) {
        Map<String, Integer> values = config.getItemValues();
        boolean onlyListed = config.isStealOnlyListedItems();
        int bestSlot = -1;  // indeks på den mest værdifulde plads (-1 = ingen fundet endnu)
        int bestValue = 0;  // værdien af det bedste item fundet hidtil

        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.isEmpty()) continue; // spring tomme pladser over

            // Slå item-id'et op, f.eks. "minecraft:diamond_sword"
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int value = values.getOrDefault(itemId, 0);

            if (value == 0) {
                if (onlyListed) {
                    // stealOnlyListedItems = true: ignorer items der ikke er i listen
                    continue;
                } else if (stack.getItem() != Items.CARROT) {
                    // stealOnlyListedItems = false: giv alle non-gulerod items minimumsværdi 1
                    value = 1;
                }
            }

            if (value > bestValue) {
                bestValue = value;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            ItemStack stolen = chest.getItem(bestSlot).copy(); // kopi inden vi fjerner
            int stolenCount = stolen.getCount();
            chest.removeItem(bestSlot, stolenCount);  // fjern det stjålne item fra kisten
            // Erstat med gulerødder som "betaling" (samme antal, max 64 pr. stak)
            chest.setItem(bestSlot, new ItemStack(Items.CARROT, Math.min(stolenCount, 64)));
            chest.setChanged(); // marker kisten som ændret så den gemmes korrekt
            // Tilføj det stjålne item til mob'ens beholdning
            mob.addCarriedItem(stolen);
            // Spil begejstringslyd — mob'en er glad for sit bytte.
            // Pitchen varieres lidt tilfældigt så den ikke lyder ens hver gang.
            playExcitementSound();
        }
    }

    /**
     * Tjekker om kisten stadig indeholder noget der er værd at stjæle.
     * Hvad der tæller som "værdifuldt" afhænger af stealOnlyListedItems:
     *   false (standard): Alt der ikke er en gulerod tæller som værdifuldt.
     *   true: Kun items der er i values-listen tæller som værdifulde.
     * Bruges til at afgøre om mob'en skal markere kisten som udtømt
     * og bevæge sig videre til den næste.
     * @param chest kisten der tjekkes
     * @return true hvis kisten indeholder mindst ét stjæleværdigt item
     */
    private boolean hasValuableItems(ChestBlockEntity chest) {
        Map<String, Integer> values = config.getItemValues();
        boolean onlyListed = config.isStealOnlyListedItems();

        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.isEmpty()) continue;

            if (onlyListed) {
                // Kun items i listen tæller
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (values.containsKey(itemId)) return true;
            } else {
                // Alt undtagen gulerødder tæller
                if (stack.getItem() != Items.CARROT) return true;
            }
        }
        return false;
    }
}
