package email.pedersen.chestthief.entity.ai;

import email.pedersen.chestthief.entity.ChestThiefEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * AI-mål: Få mob'en til at kigge mod den kiste den sigter på.
 * Hele kroppen (hoved og torso) roterer mod kisten, inklusive op/ned.
 * Det giver spilleren et visuelt tegn på hvilken kiste Chest Thief'en
 * har "snuset op" — særligt nyttigt når mob'en er i snor som en "kiste-hund".
 * Teknisk detalje:
 *   Vi bruger mob.getLookControl().setLookAt() i stedet for at sætte xRot direkte.
 *   LookControl.tick() kører hvert frame og ville ellers overskrive vores rotation.
 *   Ved at bruge setLookAt() arbejder vi med, ikke imod, systemet.
 * Dette mål er altid aktivt så længe mob'en har en kiste-position (targetChestPos != null).
 * Det fungerer både om dagen (kiste-søge-mode) og om natten (selvom mob'en angriber,
 * vises kiste-visningen ikke fordi FindAndStealFromChestGoal rydder targetChestPos om natten).
 */
public class LookAtTargetChestGoal extends Goal {

    /** Den mob der ejer dette mål. */
    private final ChestThiefEntity mob;

    /**
     * @param mob den Chest Thief-entitet der ejer dette mål
     */
    public LookAtTargetChestGoal(ChestThiefEntity mob) {
        this.mob = mob;
        // LOOK-flag: dette mål styrer rotation/blik-retning.
        // Andre mål med LOOK-flag kan ikke køre samtidig.
        this.setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    /**
     * Kan målet starte? Kun hvis mob'en har valgt en kiste den sigter mod.
     * @return true hvis targetChestPos er sat, ellers false
     */
    @Override
    public boolean canUse() {
        return mob.getTargetChestPos() != null;
    }

    /**
     * Kan målet fortsætte? Præcis samme betingelse som canUse().
     * Stopper automatisk når FindAndStealFromChestGoal rydder kiste-positionen.
     * @return true hvis mob'en stadig har et kiste-mål
     */
    @Override
    public boolean canContinueToUse() {
        return mob.getTargetChestPos() != null;
    }

    /**
     * Kører hvert tick mens målet er aktivt.
     * Fortæller mob'ens LookControl at den skal kigge mod kistens centrum.
     * LookControl roterer derefter gradvist (over flere ticks) mod målet.
     * Koordinater: kistens centrum er X+0.5, Y+0.5, Z+0.5
     * (blokke er 1×1×1, og BlockPos peger på hjørnet af blokken)
     * De to tal til sidst (10.0f og 40.0f) er max-rotationshastigheder
     * i grader pr. tick for henholdsvis Y-aksen (sving til siden)
     * og X-aksen (kig op/ned).
     */
    @Override
    public void tick() {
        BlockPos chestPos = mob.getTargetChestPos();
        if (chestPos == null) return; // sikkerhedstjek (kan ske i edge cases)

        mob.getLookControl().setLookAt(
                chestPos.getX() + 0.5, // centrum af kisten i X
                chestPos.getY() + 0.5, // centrum af kisten i Y (midt i blokken)
                chestPos.getZ() + 0.5, // centrum af kisten i Z
                10.0f,                 // max rotationshastighed vandret (grader/tick)
                40.0f                  // max rotationshastighed lodret (grader/tick)
        );
    }
}
