package email.pedersen.entity.ai;

import email.pedersen.config.ChestThiefConfig;
import email.pedersen.entity.ChestThiefEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/**
 * Target-selector goal der aktiveres under berserker-mode.
 * Kører hvert 20. tick og opdaterer mob'ens mål efter fast prioritet:
 *   1. Den sidst kendte angriber (getLastHurtByMob) hvis den er inden for range
 *   2. Nærmeste spiller (ikke creative/spectator)
 *   3. Nærmeste jerngolem
 *   4. Nærmeste landsbyboer (defensivt — kun hvis der ikke er andet)
 * Mål-scanning sker kun server-side. Stopper når berserker-mode slutter.
 * Prioritet 0 i targetSelector — overskriver alle andre target-goals under berserk.
 */
public class BerserkTargetGoal extends TargetGoal {

    private final ChestThiefEntity mob;
    private int scanCooldown = 0;

    public BerserkTargetGoal(ChestThiefEntity mob) {
        super(mob, true, false); // mustSee=true, mustReach=false
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        if (!mob.isBerserk()) return false;
        if (mob.level().isClientSide()) return false;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isBerserk() && !mob.level().isClientSide();
    }

    @Override
    public void start() {
        scanCooldown = 0;
        findAndSetTarget();
    }

    @Override
    public void tick() {
        if (--scanCooldown <= 0) {
            scanCooldown = 20; // re-scan hvert sekund

            LivingEntity current = mob.getTarget();
            double range = ChestThiefConfig.getInstance().getBerserkFollowRange();

            // Behold nuværende mål hvis det stadig er gyldigt og inden for range
            if (current != null && current.isAlive() && mob.distanceTo(current) <= range) {
                return;
            }

            // Nuværende mål er tabt — find nyt
            findAndSetTarget();
        }
    }

    @Override
    public void stop() {
        // Lad stopBerserk() i entiteten håndtere target-rydning
    }

    /**
     * Scanner omgivelserne og sætter det højest-prioriterede mål.
     */
    private void findAndSetTarget() {
        double range = ChestThiefConfig.getInstance().getBerserkFollowRange();
        AABB searchBox = mob.getBoundingBox().inflate(range);

        // 1. Sidste angriber (hvis stadig i live og inden for range)
        LivingEntity lastAttacker = mob.getLastHurtByMob();
        if (lastAttacker != null && lastAttacker.isAlive() && mob.distanceTo(lastAttacker) <= range) {
            mob.setTarget(lastAttacker);
            return;
        }

        // 2. Nærmeste spiller (ikke creative/spectator)
        List<Player> players = mob.level().getEntitiesOfClass(Player.class, searchBox,
                p -> !p.isCreative() && !p.isSpectator() && p.isAlive());
        if (!players.isEmpty()) {
            Player nearest = players.stream()
                    .min(Comparator.comparingDouble(mob::distanceTo))
                    .orElse(null);
            if (nearest != null) {
                mob.setTarget(nearest);
                return;
            }
        }

        // 3. Nærmeste jerngolem
        List<IronGolem> golems = mob.level().getEntitiesOfClass(IronGolem.class, searchBox,
                g -> g.isAlive());
        if (!golems.isEmpty()) {
            IronGolem nearest = golems.stream()
                    .min(Comparator.comparingDouble(mob::distanceTo))
                    .orElse(null);
            if (nearest != null) {
                mob.setTarget(nearest);
                return;
            }
        }

        // 4. Nærmeste landsbyboer (defensivt)
        List<AbstractVillager> villagers = mob.level().getEntitiesOfClass(AbstractVillager.class, searchBox,
                v -> v.isAlive());
        if (!villagers.isEmpty()) {
            AbstractVillager nearest = villagers.stream()
                    .min(Comparator.comparingDouble(mob::distanceTo))
                    .orElse(null);
            mob.setTarget(nearest);
        }
    }
}
