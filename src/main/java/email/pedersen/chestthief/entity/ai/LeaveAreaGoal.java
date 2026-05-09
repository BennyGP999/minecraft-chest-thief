package email.pedersen.chestthief.entity.ai;

import email.pedersen.chestthief.ChestThiefSounds;
import email.pedersen.chestthief.config.ChestThiefConfig;
import email.pedersen.chestthief.entity.ChestThiefEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * AI-mål: Gå væk fra gerningsstedet når beholdningen er fuld.
 * Aktiveres automatisk når mob'en har stjålet ting nok til at fylde alle sine
 * bæreslots (standard 5). Mob'en kan ikke stjæle mere og skal forlade kisten.
 * Adfærd:
 *   - Tyven bliver usynlig og sniger sig stille væk fra den sidst besøgte kiste
 *   - Af og til udstøder den en lyd der afslører dens tilstedeværelse (tilfredsheds-grin)
 *   - Fortsætter i leaveDurationTicks ticks (standard 10 sekunder)
 *   - Deaktiveres automatisk ved panik (PanicFleeGoal har højere prioritet)
 *   - FindAndStealFromChestGoal kan ikke starte mens dette mål er aktivt,
 *     fordi de deler MOVE-flag og dette mål har højere prioritet (4 vs 5)
 * Usynlighed:
 *   Sættes til true i start() og hvert tick — re-sætningen hvert tick er bevidst:
 *   panik og berserk kalder setInvisible(false), og ved at sætte den igen hvert tick
 *   sikrer vi at tyven er usynlig igen så snart normal adfærd genoptages.
 *   Fjernes i stop() når målet afsluttes.
 */
public class LeaveAreaGoal extends Goal {

    /** Den mob der ejer dette mål. */
    private final ChestThiefEntity mob;

    /** Config-instansen — bruges til leaveDurationTicks. */
    private final ChestThiefConfig config;

    /**
     * Nedtæller i ticks for hvor længe mob'en skal gå væk.
     * Sættes til leaveDurationTicks i start() og tæller ned i tick().
     */
    private int ticksRemaining;

    /**
     * Cooldown inden næste re-path forsøg. Sættes til 40 ticks (2 sek) efter
     * hvert navigateAway()-kald for at undgå at kalde DefaultRandomPos.getPosAway()
     * hvert tick når mob'en ikke kan finde en sti (f.eks. i et lukket rum).
     */
    private int repathCooldown = 0;

    /**
     * Nedtæller til næste tilfredsheds-lyd.
     * Når den rammer 0 afspilles en lyd og timeren nulstilles til en ny tilfældig varighed.
     * Lyden er et hint til spilleren om at tyven er i nærheden — selvom den er usynlig.
     */
    private int soundCooldown = 0;

    /**
     * @param mob den Chest Thief der ejer dette mål
     */
    public LeaveAreaGoal(ChestThiefEntity mob) {
        this.mob = mob;
        this.config = ChestThiefConfig.getInstance();
        // MOVE-flag: dette mål bruger navigation.
        // Blokerer FindAndStealFromChestGoal (prioritet 5) via MOVE-flag
        // mens beholdningen er fuld.
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * Kan målet starte?
     * Betingelser der ALLE skal være opfyldt:
     *   - Beholdningen er fuld (alle slots optaget)
     *   - Mob'en er ikke i panik (PanicFleeGoal har højere prioritet)
     *   - Mob'en kender positionen på den sidst besøgte kiste
     */
    @Override
    public boolean canUse() {
        return mob.isInventoryFull()
                && !mob.isPanicking()
                && !mob.isDeparting()   // DepartGoal overtager når tyven drager permanent bort
                && mob.getLastChestPos() != null;
    }

    /**
     * Kan målet fortsætte?
     * Stopper automatisk ved panik, afgang eller udtømt timer.
     */
    @Override
    public boolean canContinueToUse() {
        return mob.isInventoryFull()
                && !mob.isPanicking()
                && !mob.isDeparting()
                && ticksRemaining > 0;
    }

    /**
     * Kører når målet starter:
     *   1. Sæt timeren til leaveDurationTicks fra config
     *   2. Gør tyven usynlig — den sniger sig væk med sit bytte
     *   3. Start navigation væk fra den sidst besøgte kiste (normalt tempo)
     */
    @Override
    public void start() {
        ticksRemaining = config.getLeaveDurationTicks();
        repathCooldown = 0;
        // Sæt en tilfældig lyd-cooldown så første lyd ikke spilles med det samme
        int min = config.getLeavingSoundMinTicks();
        soundCooldown = min + mob.getRandom().nextInt(Math.max(1, config.getLeavingSoundMaxTicks() - min));
        mob.setInvisible(true);
        navigateAway();
    }

    /**
     * Kører hvert tick mens mob'en forlader gerningsstedet.
     * Re-sætter usynlighed hvert tick: panik og berserk kalder setInvisible(false),
     * og denne re-sætning sikrer at tyven er usynlig igen så snart den sniger sig væk.
     * Tæller ned lyd-timer og afspiller en tilfreds-lyd når timeren rammer 0 —
     * et auditivt hint til spilleren om at tyven stadig er i nærheden.
     */
    @Override
    public void tick() {
        ticksRemaining--;
        mob.setInvisible(true); // re-sæt hvert tick så panik/berserk ikke efterlader synlig mob

        // Witch-partikler afslører mob'ens position mens den er usynlig — hvert 8. tick
        if (mob.level() instanceof ServerLevel sl && ticksRemaining % 8 == 0) {
            sl.sendParticles(ParticleTypes.WITCH,
                    mob.getX(), mob.getY() + 1.0, mob.getZ(),
                    4, 0.4, 0.5, 0.4, 0.0);
        }

        // Lyd-timer: afspil et tilfredsheds-grin med jævne mellemrum (hvis lyden er slået til)
        if (config.isLeavingSoundEnabled() && --soundCooldown <= 0) {
            playLeavingSound();
            int min = config.getLeavingSoundMinTicks();
            soundCooldown = min + mob.getRandom().nextInt(Math.max(1, config.getLeavingSoundMaxTicks() - min));
        }

        if (repathCooldown > 0) {
            repathCooldown--;
        } else if (mob.getNavigation().isDone()) {
            navigateAway();
        }
    }

    /**
     * Kører når målet stopper (timer udløbet, solnedgang eller panik).
     * Gør tyven synlig igen og stopper navigationen.
     */
    @Override
    public void stop() {
        mob.setInvisible(false);
        mob.getNavigation().stop();
    }

    /**
     * Afspiller en kort tilfredsheds-lyd fra mob'ens position.
     * Lyden er et auditivt hint til spilleren om at tyven er i nærheden,
     * selvom den er usynlig. Kun server-side — klienten spiller ingen lyd direkte.
     * Pitchen varieres lidt tilfældigt (0.85–1.15) så lyden ikke lyder identisk
     * hver gang.
     */
    private void playLeavingSound() {
        if (!(mob.level() instanceof ServerLevel serverLevel)) return;
        serverLevel.playSound(
                null,
                mob.blockPosition(),
                ChestThiefSounds.LEAVING,
                SoundSource.HOSTILE,
                0.6f,
                0.85f + mob.getRandom().nextFloat() * 0.3f
        );
    }

    /**
     * Beregner en position væk fra den sidst besøgte kiste og navigerer derhen.
     * Bruger DefaultRandomPos.getPosAway() til at finde et tilfældigt gangbart
     * punkt i retningen væk fra kiste-positionen (op til 16 blokke horisontalt,
     * 7 vertikalt). Hastighed 1.0 = normalt tempo (ikke sprint).
     * Den langsomme tempo er bevidst: tyven sniger sig roligt ud,
     * i modsætning til panik-flugten der er hurtig.
     */
    private void navigateAway() {
        repathCooldown = 40; // forsøg igen tidligst om 2 sekunder
        BlockPos lastChest = mob.getLastChestPos();
        if (lastChest == null) return;

        // Tilføj tilfældig forskydning til flugtpunktet så mobs der forlader samme kiste
        // spredes i forskellige retninger i stedet for at ende samme sted.
        // Offset op til ±8 blokke i X og Z.
        Vec3 chestVec = Vec3.atCenterOf(lastChest).add(
                (mob.getRandom().nextDouble() - 0.5) * 16,
                0,
                (mob.getRandom().nextDouble() - 0.5) * 16
        );
        Vec3 walkTo = DefaultRandomPos.getPosAway(mob, 16, 7, chestVec);
        if (walkTo != null) {
            mob.getNavigation().moveTo(walkTo.x, walkTo.y, walkTo.z, 1.0); // 1.0 = normalt tempo
        }
    }
}
