package email.pedersen.syndicate.entity.ai;

import email.pedersen.syndicate.entity.SyndicateGuardEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * AI-mål: Skelettlignende afstandskamp med gulerod-pile.
 *
 * Adfærdsmønster (samme som vanilla-skelet):
 *   - Tæt på målet (< 8 blokke): vagten bakker og strafes sidelæns for at undgå nærkamp
 *   - God skyde-afstand (8-15 blokke med sigtlinje): stopper og strafes sidelæns mens den sigter
 *   - For langt eller ingen sigtlinje: navigerer mod målet
 *
 * Sigtesystemet:
 *   Vagten skyder ikke straks — der er ATTACK_INTERVAL ticks (1.5 sek.) cooldown
 *   mellem hvert skud for at give spilleren en chance for at undvige.
 *   SyndicateGuardEntity.performRangedAttack() opretter selve CarrotArrowEntity.
 *
 * Prioritet 1 i goalSelector — blokerer MOVE og LOOK via flag.
 */
public class SkeletonLikeRangedCombatGoal extends Goal {

    private final SyndicateGuardEntity mob;

    /**
     * Minimum antal ticks mellem hvert skud (= ~1.5 sekund ved 20 TPS).
     * Giver spilleren tid til at reagere og undvige.
     */
    private static final int ATTACK_INTERVAL = 30;

    /**
     * Kvadreret afstand for "foretrukken skyde-position": 8 blokke.
     * Under denne afstand bakker vagten ud for at holde distance.
     */
    private static final double PREFERRED_DIST_SQ = 64.0;

    /**
     * Maksimal kvadreret afstand hvorfra vagten skyder: 15 blokke.
     * Over denne afstand prøver vagten at komme nærmere.
     */
    private static final double MAX_ATTACK_DIST_SQ = 225.0;

    /**
     * Nedtæller i ticks til næste skud.
     * Nulstilles til ATTACK_INTERVAL efter hvert skud.
     */
    private int attackCooldown = 0;

    /**
     * Strafe-retning: +1 = til højre, -1 = til venstre.
     * Skiftes med jævne mellemrum for at efterligne skelet-vandremønsteret.
     */
    private int strafeDir = 1;

    /**
     * Nedtæller i ticks til næste retningsskift for strafen.
     * Randomiseret i intervallet [20, 40] ticks (~1-2 sekunder).
     */
    private int strafeDirTimer = 0;

    /**
     * Cachet sigtlinje-resultat fra seneste hasLineOfSight()-kald.
     * hasLineOfSight() er en raycast der traverserer op til 16 blok-AABBs —
     * dyr at kalde hvert tick pr. vagt. Vi genberegner kun hvert 3. tick.
     */
    private boolean cachedLOS = false;

    /**
     * Nedtæller til næste hasLineOfSight()-genberegning.
     * Når den rammer 0 genberegnes hasLineOfSight() og timeren sættes til 3.
     * 3 ticks (~150 ms) er kort nok til at skydning og bevægelse stadig føles
     * responsivt, men sparer ~66% af raycast-kaldene.
     */
    private int losCacheTicks = 0;

    /**
     * @param mob den SyndicateGuardEntity der ejer dette mål
     */
    public SkeletonLikeRangedCombatGoal(SyndicateGuardEntity mob) {
        this.mob = mob;
        // MOVE: bruger navigation og MoveControl — blokerer andre MOVE-mål
        // LOOK: bruger LookControl — blokerer WaterAvoidingRandomStrollGoal's LOOK
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /**
     * Aktiveres når vagten har et levende mål.
     * Målet sættes af NearestAttackableTargetGoal eller HurtByTargetGoal.
     */
    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        return t != null && t.isAlive();
    }

    /**
     * Fortsætter så længe målet er i live.
     */
    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    /**
     * Nulstiller cooldown og LOS-cache når målet mistes så vagten ikke "huker" ved næste kamp.
     */
    @Override
    public void stop() {
        attackCooldown = 0;
        losCacheTicks = 0;
    }

    /**
     * Kører hvert tick:
     *   1. Drej mod målet via LookControl
     *   2. Bevæg: bag ud (for tæt), straf (god afstand), nærm dig (for langt / ingen LOS)
     *   3. Tæl cooldown ned og skyd når klar
     */
    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return;

        double distSq = mob.distanceToSqr(target);

        // Cache hasLineOfSight() i 3 ticks for at undgå dyr raycast hvert tick.
        // Med mange vagter på samme server giver det ~66% færre raycasts.
        boolean hasLOS;
        if (losCacheTicks <= 0) {
            hasLOS = mob.hasLineOfSight(target);
            cachedLOS = hasLOS;
            losCacheTicks = 3;
        } else {
            losCacheTicks--;
            hasLOS = cachedLOS;
        }

        // Sæt blikretning mod målet med blød rotation (30° pr. tick)
        mob.getLookControl().setLookAt(target, 30.0f, 30.0f);

        // Opdater strafe-retning med jævne mellemrum
        if (--strafeDirTimer <= 0) {
            strafeDir = mob.getRandom().nextBoolean() ? 1 : -1;
            strafeDirTimer = 20 + mob.getRandom().nextInt(20);
        }

        // Beregn bevægelse baseret på afstand og sigtlinje
        if (hasLOS) {
            if (distSq <= PREFERRED_DIST_SQ) {
                // For tæt: stop pathfinding og bak ud mens vi strafes sidelæns
                mob.getNavigation().stop();
                mob.getMoveControl().strafe(-0.5f, strafeDir * 0.5f);
            } else if (distSq <= MAX_ATTACK_DIST_SQ) {
                // God skydeafstand: stop og straf kun sidelæns (hold positionen)
                mob.getNavigation().stop();
                mob.getMoveControl().strafe(0.0f, strafeDir * 0.5f);
            } else {
                // For langt: nærm dig
                mob.getNavigation().moveTo(target, 1.0);
            }
        } else {
            // Ingen sigtlinje: nærm dig for at finde en åbning
            mob.getNavigation().moveTo(target, 1.0);
        }

        // Skyde-cooldown: tæl ned og skyd når klar og sigtlinje er klar
        if (attackCooldown > 0) {
            attackCooldown--;
        }

        if (attackCooldown == 0 && hasLOS && distSq <= MAX_ATTACK_DIST_SQ) {
            mob.performRangedAttack(target, 1.0f);
            attackCooldown = ATTACK_INTERVAL;
        }
    }
}
