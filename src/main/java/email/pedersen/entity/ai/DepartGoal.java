package email.pedersen.entity.ai;

import email.pedersen.ChestThiefSounds;
import email.pedersen.config.ChestThiefConfig;
import email.pedersen.entity.ChestThiefEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * AI-mål: Tyven drager bort med sit bytte og forsvinder.
 * Aktiveres af ChestThiefEntity.startDeparture() når:
 *   - Beholdningen har været fuld i departDelayTicks (standard 20 sek), eller
 *   - Tyven har levet i maxAgeTicks ticks (standard 2 Minecraft-dage)
 * Adfærd:
 *   - Tyven går usynlig og sniger sig væk fra nærmeste spiller (eller lastChestPos)
 *   - Witch-partikler afslører mob'ens position hvert 8. tick mens den er usynlig
 *   - Af og til udstøder den en tilfreds-lyd som et auditivt hint
 *   - Efter departDurationTicks ticks despawner den — loot er "solgt på sortmarkedet"
 *   - Afbrydes midlertidigt af panik og berserk (normal reaktion på angreb)
 *   - Når panik/berserk slutter, genoptager tyven afrejsen — isDeparting er stadig true
 * Usynlighed:
 *   Re-sættes hvert tick (ikke kun i start) så tyven forbliver usynlig selv efter
 *   panik eller berserk midlertidigt har gjort den synlig.
 * Prioritet 3 (MOVE) — lavere end panik (1) og kamp (2), men højere end LeaveAreaGoal (4).
 * Ingen re-prioritering nødvendig: departTimer tæller ned i ChestThiefEntity.tick() uanset
 * om DepartGoal er aktiv, så despawn sker på rette tidspunkt selv under panik/berserk.
 */
public class DepartGoal extends Goal {

    private final ChestThiefEntity mob;

    /** Cooldown inden næste re-path-forsøg (40 ticks = 2 sek). */
    private int repathCooldown = 0;

    /**
     * Nedtæller til næste witch-particle burst.
     * Starter på 0 i start() så partikler spawner med det samme — ikke efter 8 ticks.
     * Sættes til 8 efter hvert burst.
     */
    private int particleCooldown = 0;

    /**
     * Nedtæller til næste tilfredsheds-lyd.
     * Når den rammer 0 afspilles en lyd og timeren nulstilles til en ny tilfældig varighed.
     */
    private int soundCooldown = 0;

    public DepartGoal(ChestThiefEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return mob.isDeparting() && !mob.isPanicking() && !mob.isBerserk();
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isDeparting() && !mob.isPanicking() && !mob.isBerserk();
    }

    /**
     * Kører når afrejsen starter (også når den genoptages efter panik/berserk).
     * Gør tyven usynlig med det samme og nulstiller particle-timeren så
     * det første particle-burst spawner allerede på første tick.
     */
    @Override
    public void start() {
        repathCooldown = 0;
        particleCooldown = 0; // spawn partikler med det samme — ingen forsinkelse
        ChestThiefConfig config = ChestThiefConfig.getInstance();
        int min = config.getLeavingSoundMinTicks();
        soundCooldown = min + mob.getRandom().nextInt(Math.max(1, config.getLeavingSoundMaxTicks() - min));
        mob.setInvisible(true);
        navigateAway();
    }

    /**
     * Kører hvert tick mens tyven drager bort.
     *
     * Re-sætter usynlighed hvert tick: panik og berserk kalder setInvisible(false),
     * og denne re-sætning sikrer at tyven er usynlig igen så snart afrejsen genoptages.
     *
     * Witch-partikler spawner hvert 8. tick (ca. 2-3 gange/sek) som visuelt hint.
     * Lyden afspilles med jævne mellemrum som et auditivt hint.
     */
    @Override
    public void tick() {
        mob.setInvisible(true); // re-sæt hvert tick så panik/berserk ikke efterlader synlig mob

        // Witch-partikler hvert 8. tick — bruger dedikeret tæller så burst sker uafhængigt
        // af mob'ens samlede levetid (mob.tickCount giver uforudsigelig fase ved opstart)
        if (mob.level() instanceof ServerLevel sl) {
            if (--particleCooldown <= 0) {
                sl.sendParticles(ParticleTypes.WITCH,
                        mob.getX(), mob.getY() + 1.0, mob.getZ(),
                        4, 0.4, 0.5, 0.4, 0.0);
                particleCooldown = 8;
            }
        }

        // Lyd-timer: afspil et tilfredsheds-grin med jævne mellemrum (hvis lyden er slået til)
        ChestThiefConfig config = ChestThiefConfig.getInstance();
        if (config.isLeavingSoundEnabled() && --soundCooldown <= 0) {
            playLeavingSound();
            int min = config.getLeavingSoundMinTicks();
            soundCooldown = min + mob.getRandom().nextInt(Math.max(1, config.getLeavingSoundMaxTicks() - min));
        }

        if (--repathCooldown <= 0 && mob.getNavigation().isDone()) {
            navigateAway();
        }
    }

    /**
     * Kører når målet stopper midlertidigt (panik eller berserk afbryder).
     * Gør tyven synlig igen og stopper navigationen.
     * DepartGoal genstartes automatisk når panik/berserk er ovre.
     */
    @Override
    public void stop() {
        mob.setInvisible(false);
        mob.getNavigation().stop();
    }

    /**
     * Afspiller en kort tilfredsheds-lyd fra mob'ens position.
     *
     * Et auditivt hint til spilleren om at tyven er i nærheden.
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
     * Finder en position væk fra den nærmeste spiller (eller lastChestPos) og navigerer derhen.
     * Bruger normalt tempo (1.0) — tyven sniger sig stille væk, ikke i panik.
     */
    private void navigateAway() {
        repathCooldown = 40;

        // Foretrækker at gå væk fra den nærmeste spiller inden for 64 blokke
        Player nearestPlayer = mob.level().getNearestPlayer(mob, 64.0);
        Vec3 fleeFrom;

        if (nearestPlayer != null) {
            fleeFrom = nearestPlayer.position();
        } else {
            BlockPos lastChest = mob.getLastChestPos();
            fleeFrom = lastChest != null ? Vec3.atCenterOf(lastChest) : mob.position();
        }

        // Tilføj tilfældig forskydning til flugtpunktet så mobs der forlader samme area
        // spredes i forskellige retninger i stedet for at ende samme sted.
        Vec3 jitteredFleeFrom = fleeFrom.add(
                (mob.getRandom().nextDouble() - 0.5) * 16,
                0,
                (mob.getRandom().nextDouble() - 0.5) * 16
        );
        Vec3 walkTo = DefaultRandomPos.getPosAway(mob, 16, 7, jitteredFleeFrom);
        if (walkTo != null) {
            mob.getNavigation().moveTo(walkTo.x, walkTo.y, walkTo.z, 1.0);
        }
    }
}
