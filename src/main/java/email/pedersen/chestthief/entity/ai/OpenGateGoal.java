package email.pedersen.chestthief.entity.ai;

import email.pedersen.chestthief.entity.ChestThiefEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * AI-mål: Åbn hegn-låger og fældeluger (trapdøre) der blokerer vejen til en kiste.
 * Spillere bruger hegn-låger og trapdøre som simpel sikkerhed — en tyv der tidligere
 * var vandrende handelsmand ved præcis, hvordan man åbner en byport eller kælderluge.
 * Adfærd:
 *   Hvert 5. tick scanner mål'et et område på 2 blokke rundt om mob'en.
 *   Finder det en lukket FenceGateBlock eller TrapDoorBlock, åbner det den,
 *   og lukker den igen 30 ticks (~1,5 sekund) senere — tilstrækkeligt til at
 *   mob'en kan navigere igennem.
 * Vigtige detaljer:
 *   - Ingen Goal-flag: kører parallelt med alle bevægelses-mål (ligesom OpenDoorGoal).
 *   - Kun aktiv når mob'en har et aktivt kiste-mål (targetChestPos != null) og
 *     navigerer aktivt. Dermed åbner tyven ikke tilfældige låger under vandretur.
 *   - Bruger setBlock() med flag=10 (UPDATE_CLIENTS | opdatering) — det trigger
 *     PathNavigation.shouldRecalculatePath() i Minecraft, så mob'en automatisk
 *     omberegner sin sti igennem den nu-åbne lås.
 *   - Kun ét åbent objekt ad gangen: hvis mob'en støder på en ny lås, lukkes
 *     den forrige inden den næste åbnes.
 */
public class OpenGateGoal extends Goal {

    /**
     * Den Chest Thief-entitet der ejer dette mål.
     * Bruges til at tjekke om mob'en har et aktivt kiste-mål og navigerer.
     */
    private final ChestThiefEntity mob;

    /**
     * Positionen på den lås/fældeluge der i øjeblikket holdes åben.
     * Null = ingen åben lås.
     */
    @Nullable
    private BlockPos openedPos = null;

    /**
     * Nedtæller i ticks inden den åbne lås lukkes automatisk.
     * Nulstilles hver gang en ny lås åbnes.
     */
    private int closeTimer = 0;

    /**
     * Positionen på den forrige lås der venter på at blive lukket.
     * Sættes når en ny lås opdages i en dobbelt-dør-gang, så den forrige
     * lås ikke lukkes øjeblikkeligt (mens tyven måske stadig er i mellemrummet).
     * Null = ingen forrige lås venter på lukning.
     */
    @Nullable
    private BlockPos previousOpenedPos = null;

    /**
     * Nedtæller til lukning af previousOpenedPos.
     * Arver den resterende tid fra openedPos' closeTimer ved overgang.
     */
    private int previousCloseTimer = 0;

    /**
     * Nedtæller til næste scanning af omgivelserne.
     * Vi scanner ikke hvert tick for at spare CPU — hvert 5. tick er tilstrækkeligt.
     */
    private int scanTimer = 0;

    /** Antal ticks en lås/fældeluge holdes åben inden automatisk lukning. (~1,5 sekund) */
    private static final int CLOSE_DELAY_TICKS = 30;

    /** Antal ticks mellem hvert scan for nye låger i nærheden. */
    private static final int SCAN_INTERVAL_TICKS = 5;

    /**
     * Scannings-radius i blokke rundt om mob'en (horisontalt).
     * 2 blokke giver mob'en tid til at opdage lågen inden den går ind i den,
     * og undgår falsk positiver fra låger der er langt væk.
     */
    private static final int SCAN_RADIUS = 2;

    /**
     * @param mob den Chest Thief-entitet der ejer dette mål
     */
    public OpenGateGoal(ChestThiefEntity mob) {
        this.mob = mob;
        // Ingen flag — kører parallelt med bevægelses-mål (ligesom OpenDoorGoal).
        // Flags bruges til at forhindre at to mål kører på én gang; her ønsker
        // vi bevidst at dette mål kører uanset hvad andet er aktivt.
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    /**
     * Kan målet starte?
     * Aktiveres i tre situationer:
     *   1. Mob'en navigerer aktivt (isDone = false) — normalt forløb langs ruten,
     *      både på vej mod en kiste OG på vej ud af labyrinten efter stjæleriet.
     *      Tyven kan ikke forlade et indhegnet område hvis målet kun aktiveres, mens
     *      den har et kiste-mål: targetChestPos ryddes efter stjæleriet, og uden
     *      OpenGateGoal er aktivt kan mob'en ikke åbne hegn-lågen igen for at komme ud.
     *   2. Navigation er færdig (isDone = true) men mob'en har et kiste-mål —
     *      pathfinderen returnerede en delvis rute fordi lågen er BLOCKED. Mob'en
     *      er stoppet foran lågen og venter på at den åbnes.
     *   3. closeTimer > 0 — en lås er åbnet og venter på at blive lukket igen.
     *      Fanges i canContinueToUse(), ikke her.
     * Vi undgår dyr blok-scanning i canUse() ved kun at kalde findNearbyOpenable()
     * i det stille tilfælde (isDone = true med kiste-mål) — ikke mens mob'en navigerer.
     * Under tilfældig vandretur (WaterAvoidingRandomStrollGoal) sørger de eksisterende
     * filtre i findNearbyOpenable() — afstands-tjek, akse-tjek og SCAN_RADIUS = 2 —
     * for at mob'en kun åbner låger den rent faktisk er på vej igennem.
     */
    @Override
    public boolean canUse() {
        if (!mob.getNavigation().isDone()) return true;
        // Navigation stoppet med kiste-mål — tjek om der er en lås direkte foran os
        if (mob.getTargetChestPos() != null) return findNearbyOpenable() != null;
        return false;
    }

    /**
     * Kan målet fortsætte?
     * Fortsætter hvis der er en lås der venter på at blive lukket (closeTimer > 0),
     * mob'en navigerer aktivt, eller navigation er stoppet med et kiste-mål og en lås i nærheden.
     */
    @Override
    public boolean canContinueToUse() {
        if (closeTimer > 0) return true;
        if (!mob.getNavigation().isDone()) return true;
        if (mob.getTargetChestPos() != null) return findNearbyOpenable() != null;
        return false;
    }

    /**
     * Kører når målet starter. Scanner straks omgivelserne for åbnbare blokke
     * frem for at vente til det første scan-interval er gået.
     */
    @Override
    public void start() {
        scanTimer = 0;
        tryOpenNearby();
    }

    /**
     * Kører hvert tick mens målet er aktivt.
     * Tæller ned til lukning (både aktuel og forrige lås), og scanner periodisk for nye låger.
     */
    @Override
    public void tick() {
        // Tæl ned til automatisk lukning af forrige lås (fra dobbelt-dør-overgang).
        // Den forrige lås holdes åben i sin resterende tid så tyven ikke skubbes ind i
        // den næste lås, hvis den forrige lukkes mens tyven endnu er i mellemrummet.
        if (previousCloseTimer > 0) {
            previousCloseTimer--;
            if (previousCloseTimer == 0 && previousOpenedPos != null) {
                setOpen(previousOpenedPos, false);
                previousOpenedPos = null;
            }
        }

        // Tæl ned til automatisk lukning af den aktuelle lås
        if (closeTimer > 0) {
            closeTimer--;
            if (closeTimer == 0 && openedPos != null) {
                setOpen(openedPos, false);
                openedPos = null;
            }
        }

        // Scan periodevis for nye låger (ikke hvert tick for at spare CPU)
        if (scanTimer > 0) {
            scanTimer--;
        } else {
            scanTimer = SCAN_INTERVAL_TICKS;
            tryOpenNearby();
        }
    }

    /**
     * Kører når målet stoppes (f.eks. mob finder kisten, mister sit mål, eller dør).
     * Sørger for at både den aktuelle og den forrige åbne lås lukkes igen med det samme.
     */
    @Override
    public void stop() {
        if (openedPos != null) {
            setOpen(openedPos, false);
            openedPos = null;
        }
        if (previousOpenedPos != null) {
            setOpen(previousOpenedPos, false);
            previousOpenedPos = null;
        }
        closeTimer = 0;
        previousCloseTimer = 0;
        scanTimer = 0;
    }

    /**
     * Find en lukket åbnbar blok i nærheden og åbn den.
     * Når en ny lås opdages mens en anden allerede er åben (f.eks. dobbelt-dør-gang),
     * flyttes den forrige lås til "previous"-sporet med sin resterende lukke-timer
     * i stedet for at blive lukket øjeblikkeligt. Det sikrer at lås nr. 1 ikke
     * lukker mens tyven endnu er i det én-bloks mellemrum og skubber tyven ind i lås nr. 2.
     */
    private void tryOpenNearby() {
        BlockPos found = findNearbyOpenable();
        if (found == null) return;
        if (found.equals(openedPos)) return; // allerede åbnet denne

        // Hvis der allerede er en "previous"-lås der venter, lukkes den straks
        // inden vi roterer — vi sporer maks. to låger ad gangen.
        if (previousOpenedPos != null) {
            setOpen(previousOpenedPos, false);
            previousOpenedPos = null;
            previousCloseTimer = 0;
        }

        // Flyt den nuværende åbning til "previous" med sin resterende lukke-tid.
        // Den vil lukke naturligt via previousCloseTimer — ikke øjeblikkeligt.
        if (openedPos != null) {
            previousOpenedPos = openedPos;
            previousCloseTimer = closeTimer;
        }

        setOpen(found, true);
        openedPos = found;
        closeTimer = CLOSE_DELAY_TICKS;
        // Navigation genstartes IKKE her — FindAndStealFromChestGoal.tickMoving()
        // kalder navigateToChest() næste tick, som vælger den korrekte horisontale
        // naboretning til kisten og undgår at sende mob'en op på toppen.
    }

    /**
     * Scanner et område på SCAN_RADIUS blokke rundt om mob'en og finder
     * den første lukkede hegn-lås eller fældeluge.
     * Vi tjekker højder -1 til +1 relativt til mob'ens fødder for at håndtere:
     *   dy = -1: trapdøre i gulvet (adgang til kælder nedefra)
     *   dy =  0: hegn-låger og trapdøre i samme højde
     *   dy = +1: trapdøre i loftet (adgang oppefra) og høje hegn-låger
     * For FenceGateBlock gælder et ekstra aksecheck (se isOnCorrectSideOfGate):
     *   Mob'en skal stå på passage-siden (nord/syd eller øst/vest afhængig af FACING),
     *   ikke fra siden igennem en nabovæg. Scan-radius begrænses til 1 for hegn-låger
     *   så mob'en skal stå direkte ved lågen — aldrig 2 blokke væk igennem en væg.
     * @return positionen på den første lukkede åbnbare blok, eller null hvis ingen fundet
     */
    @Nullable
    private BlockPos findNearbyOpenable() {
        BlockPos center = mob.blockPosition();
        Level level = mob.level();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(check);
                    if (!isClosedOpenable(state)) continue;

                    if (state.getBlock() instanceof FenceGateBlock) {
                        // Scan-radius 1 for hegn-låger: mob skal stå direkte ved lågen.
                        // SCAN_RADIUS = 2 giver mob'en tid til at reagere, men ved afstand 2
                        // kan den stadig "se" lågen igennem en nabovæg langs den forkerte akse.
                        // At kræve afstand ≤ 1 fjerner det problem — mob'en er kun inden for
                        // ét blok-skridt af lågen, og akse-tjekket sørger for korrekt retning.
                        if (Math.abs(dx) > 1 || Math.abs(dz) > 1) continue;
                        if (!isOnCorrectSideOfGate(check, state)) continue;
                    }

                    return check;
                }
            }
        }
        return null;
    }

    /**
     * Tjekker at mob'en er placeret på den rigtige side af en hegn-lås.
     * En hegn-lås har en FACING-retning der angiver passér-aksen — den akse man
     * går langs for at gå igennem lågen:
     *   FACING = NORTH eller SOUTH (Z-akse): man går igennem i Z-retningen.
     *     Mob'en skal stå nord eller syd for lågen (dz != 0, dx = 0).
     *   FACING = EAST eller WEST (X-akse): man går igennem i X-retningen.
     *     Mob'en skal stå øst eller vest for lågen (dx != 0, dz = 0).
     * Uden dette tjek kan mob'en åbne lågen igennem en nabovæg — den er inden for
     * SCAN_RADIUS men på den forkerte side (perpendiculær på passage-aksen).
     * @param gatePos positionen på lågen
     * @param state   blokstate for lågen (bruges til at læse FACING)
     * @return true hvis mob'en er på korrekt passér-side
     */
    private boolean isOnCorrectSideOfGate(BlockPos gatePos, BlockState state) {
        Direction facing = state.getValue(FenceGateBlock.FACING);
        BlockPos mobPos = mob.blockPosition();
        int dx = mobPos.getX() - gatePos.getX();
        int dz = mobPos.getZ() - gatePos.getZ();

        if (facing.getAxis() == Direction.Axis.Z) {
            // NORTH/SOUTH: passér langs Z-aksen → mob skal stå nord/syd (dz != 0, dx = 0)
            return dx == 0 && dz != 0;
        } else {
            // EAST/WEST: passér langs X-aksen → mob skal stå øst/vest (dx != 0, dz = 0)
            return dz == 0 && dx != 0;
        }
    }


    /**
     * Tjekker om en blok er en lukket hegn-lås eller fældeluge.
     * Vi bruger BlockStateProperties.OPEN (den fælles property) frem for
     * FenceGateBlock.OPEN / TrapDoorBlock.OPEN fordi begge bloktyper arver
     * OPEN fra BlockStateProperties — det er den samme BooleanProperty-instans.
     * hasProperty()-tjekket er en ekstra sikring mod at kalde getValue() på
     * en blok der ikke har OPEN-propertyen.
     * @param state block state for den blok der tjekkes
     * @return true hvis blokken er en lukket hegn-lås eller fældeluge
     */
    private boolean isClosedOpenable(BlockState state) {
        return (state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof TrapDoorBlock)
                && state.hasProperty(BlockStateProperties.OPEN)
                && !state.getValue(BlockStateProperties.OPEN);
    }

    /**
     * Åbner eller lukker en hegn-lås eller fældeluge og afspiller korrekt lyd.
     * setBlock() med flag 10 sender en blok-opdatering til klienten OG trigrer
     * Minecrafts block-change notifikation, som får mob'ens PathNavigation til
     * at genberegne sin sti. Det betyder at mob'en automatisk opdaterer sin rute
     * igennem den nu-åbne lås uden at vi skal genstarte navigationen manuelt.
     * @param pos  positionen på blokken der skal åbnes/lukkes
     * @param open true for at åbne, false for at lukke
     */
    private void setOpen(BlockPos pos, boolean open) {
        if (!(mob.level() instanceof ServerLevel serverLevel)) return;
        BlockState state = serverLevel.getBlockState(pos);

        // Tjek at blokken stadig er der og har OPEN-propertyen (kan være ændret siden scanning)
        if (!state.hasProperty(BlockStateProperties.OPEN)) return;

        // Undgå redundant opdatering hvis tilstanden allerede er korrekt
        if (state.getValue(BlockStateProperties.OPEN) == open) return;

        serverLevel.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 10);

        // Afspil korrekt lyd for hegn-låger.
        // Trapdøre får ingen eksplicit lyd herfra — vanilla-lydene kræver
        // block-type-specifik håndtering der varierer med materiale (træ/jern/kobber).
        if (state.getBlock() instanceof FenceGateBlock) {
            serverLevel.playSound(null, pos,
                    open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
                    SoundSource.BLOCKS, 1.0f, 1.0f);
        }
    }
}
