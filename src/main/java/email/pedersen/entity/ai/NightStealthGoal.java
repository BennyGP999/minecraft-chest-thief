package email.pedersen.entity.ai;

import email.pedersen.config.ChestThiefConfig;
import email.pedersen.entity.ChestThiefEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * AI-mål: Kortvarig usynlighed om natten mens tyven er aktiv ved en kiste.
 * Aktiveres når alle betingelser er opfyldt:
 *   - Det er nat
 *   - Mob'en har et kiste-mål (er aktivt i gang med tyveri)
 *   - Mob'en er ikke i berserk- eller panik-tilstand
 *   - Cooldown er udløbet
 * Effekt:
 *   - Mob'en sættes usynlig i stealthTimer ticks (3–7 sek, fra config)
 *   - Witch-particles spawnes nær mob'en hvert 10. tick som visuelt hint
 *     til spillere om at "noget er der" — undgår total uforudsigelighed
 *   - Mob'en bliver synlig igen når timeren udløber, eller ved panik/berserk
 *     (disse kalder setInvisible(false) direkte i ChestThiefEntity)
 * Ingen Goal.Flag: Kører parallelt med FindAndStealFromChestGoal og
 * MeleeAttackGoal — påvirker ikke navigation eller kamp.
 * Cooldown håndteres internt i canUse(): stealthCooldown tæller ned mod 0
 * mens målet er inaktivt. Ny cooldown sættes i stop().
 */
public class NightStealthGoal extends Goal {

    private final ChestThiefEntity mob;

    /** Resterende ticks af den aktuelle usynlighedsperiode. */
    private int stealthTimer = 0;

    /**
     * Cooldown-tæller inden næste usynlighedsperiode.
     * Tæller ned mod 0 i canUse() — returnerer false mens > 0.
     */
    private int stealthCooldown = 0;

    public NightStealthGoal(ChestThiefEntity mob) {
        this.mob = mob;
        // Ingen flags — kører parallelt med alle andre mål
    }

    @Override
    public boolean canUse() {
        if (stealthCooldown > 0) {
            stealthCooldown--;
            return false;
        }
        if (!mob.isNightTime()
                || mob.getTargetChestPos() == null
                || mob.isBerserk()
                || mob.isPanicking()
                || mob.isStealing()
                || mob.level().isClientSide()) {
            return false;
        }
        // Ikke garanteret aktivering ved hver cooldown — brug stealthChance
        return mob.getRandom().nextDouble() < ChestThiefConfig.getInstance().getStealthChance();
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isNightTime()
                && mob.getTargetChestPos() != null
                && !mob.isBerserk()
                && !mob.isPanicking()
                && !mob.isStealing()   // mister usynlighed øjeblikkeligt når tyveriet begynder
                && stealthTimer > 0;
    }

    @Override
    public void start() {
        ChestThiefConfig config = ChestThiefConfig.getInstance();
        int min = config.getStealthMinTicks();
        int max = config.getStealthMaxTicks();
        stealthTimer = min + mob.getRandom().nextInt(Math.max(1, max - min + 1));
        mob.setInvisible(true);
    }

    @Override
    public void tick() {
        stealthTimer--;

        // Spawn witch-particles som visuelt hint — kun server-side.
        // Partiklerne afslører mob'ens position for spilleren, selvom den er usynlig.
        // Hvert 8. tick = ca. 2-3 gange i sekundet — hyppigt nok til at følge med.
        if (mob.level() instanceof ServerLevel sl && stealthTimer % 8 == 0) {
            sl.sendParticles(
                    ParticleTypes.WITCH,
                    mob.getX(), mob.getY() + 1.0, mob.getZ(),
                    4,            // antal partikler pr. burst
                    0.4, 0.5, 0.4, // spredning X/Y/Z — lidt større så de er lettere at se
                    0.0           // hastighed
            );
        }
    }

    @Override
    public void stop() {
        mob.setInvisible(false);

        ChestThiefConfig config = ChestThiefConfig.getInstance();
        int minCd = config.getStealthCooldownMinTicks();
        int maxCd = config.getStealthCooldownMaxTicks();
        stealthCooldown = minCd + mob.getRandom().nextInt(Math.max(1, maxCd - minCd + 1));
    }
}
