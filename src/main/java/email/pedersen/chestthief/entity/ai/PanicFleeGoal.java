package email.pedersen.chestthief.entity.ai;

import email.pedersen.chestthief.config.ChestThiefConfig;
import email.pedersen.chestthief.entity.ChestThiefEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * AI-mål: Flygt i panik fra et angreb om dagen.
 * Aktiveres når ChestThiefEntity.isPanicking() er true, hvilket sker
 * i 60% af tilfældene (konfigurerbart) når mob'en angribes om dagen.
 * Hvad der sker:
 *   1. Mob'en dropper ét tilfældigt item fra sin beholdning ved start
 *   2. Mob'en sprinter væk fra angriberens position i panicDurationTicks ticks
 *   3. Når panikken slutter, nulstilles isPanicking og mob'en vender tilbage
 *      til sin normale adfærd (FindAndStealFromChestGoal eller LeaveAreaGoal)
 * Prioritet 1 i goalSelector — blokerer angreb (prioritet 2) og stjæl (prioritet 5)
 * via MOVE-flag, mens panikken varer.
 * De resterende items i beholdningen kan fås ved at slå mob'en ihjel (se dropCustomDeathLoot).
 */
public class PanicFleeGoal extends Goal {

    /** Den mob der ejer dette mål. */
    private final ChestThiefEntity mob;

    /** Config-instansen — bruges til panik-varighed. */
    private final ChestThiefConfig config;

    /**
     * Nedtæller i ticks for hvor længe panikken varer.
     * Sættes til panicDurationTicks i start() og tæller ned i tick().
     */
    private int ticksRemaining;

    /**
     * @param mob den Chest Thief der ejer dette mål
     */
    public PanicFleeGoal(ChestThiefEntity mob) {
        this.mob = mob;
        this.config = ChestThiefConfig.getInstance();
        // MOVE-flag: dette mål bruger navigation.
        // Blokerer andre MOVE-mål (MeleeAttackGoal, FindAndStealFromChestGoal) mens panik er aktiv.
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * Kan målet starte? Kun hvis mob'en er i panik-tilstand, kender angriber-positionen,
     * og IKKE er i besærk-mode. Besærk og panik er gensidigt udelukkende: en tyv der
     * er i besærk fastholdes i den tilstand indtil timeren udløber, uanset yderligere angreb.
     * isPanicking sættes til true i ChestThiefEntity.provoke() med 60% sandsynlighed,
     * men kun når isBerserk er false — dette check er en ekstra sikring på mål-niveau.
     */
    @Override
    public boolean canUse() {
        return mob.isPanicking() && mob.getPanicFleeFrom() != null && !mob.isBerserk();
    }

    /**
     * Kan målet fortsætte? Kun mens mob'en stadig er i panik og timeren løber.
     */
    @Override
    public boolean canContinueToUse() {
        return mob.isPanicking() && ticksRemaining > 0;
    }

    /**
     * Kører når panikken starter:
     *   1. Sæt timeren til panikvarighed fra config
     *   2. Drop ét tilfældigt item fra beholdningen
     *   3. Start navigation væk fra angriberen (sprint-hastighed 1.5)
     */
    @Override
    public void start() {
        ticksRemaining = config.getPanicDurationTicks();
        dropOneCarriedItem();
        navigateAway();
    }

    /**
     * Kører hvert tick mens mob'en er i panik.
     * Tæller ned og gen-navigerer hvis stien er afsluttet (mob er ved at gå i stå).
     */
    @Override
    public void tick() {
        ticksRemaining--;
        // Re-navigér hvis den nuværende sti er afsluttet men panikken ikke er slut
        if (mob.getNavigation().isDone()) {
            navigateAway();
        }
    }

    /**
     * Kører når panikken slutter (timeren når 0 eller canContinueToUse() returnerer false).
     * Nulstiller panik-tilstanden på entiteten og stopper navigationen.
     */
    @Override
    public void stop() {
        mob.stopPanic();        // nulstil isPanicking og panicFleeFrom
        mob.getNavigation().stop();
    }

    /**
     * Beregner en position væk fra angriberen og navigerer derhen i sprint-tempo.
     * Bruger DefaultRandomPos.getPosAway() som finder et tilfældigt gangbart punkt
     * i retningen væk fra den angivne position (op til 16 blokke horisontalt, 7 vertikalt).
     * Hastighed 1.5 = sprint (normal hastighed er 1.0).
     */
    private void navigateAway() {
        BlockPos fleeFrom = mob.getPanicFleeFrom();
        if (fleeFrom == null) return;

        Vec3 fleeVec = Vec3.atCenterOf(fleeFrom);
        Vec3 runTo = DefaultRandomPos.getPosAway(mob, 16, 7, fleeVec);
        if (runTo != null) {
            mob.getNavigation().moveTo(runTo.x, runTo.y, runTo.z, 1.5); // 1.5 = sprint
        }
    }

    /**
     * Dropper ét tilfældig item fra mob'ens beholdning på mob'ens nuværende position.
     * Finder alle ikke-tomme slots, vælger en tilfældig af dem og dropper den stack.
     * Slottet ryddes bagefter. Hvis beholdningen er tom, sker der ingenting.
     * Items kan kun droppes server-side, så metoden er no-op på klientsiden.
     */
    private void dropOneCarriedItem() {
        // Items kan kun droppes på serveren
        if (!(mob.level() instanceof ServerLevel serverLevel)) return;

        NonNullList<ItemStack> carried = mob.getCarriedItems();

        // Find alle ikke-tomme slots
        List<Integer> filledSlots = new ArrayList<>();
        for (int i = 0; i < carried.size(); i++) {
            if (!carried.get(i).isEmpty()) filledSlots.add(i);
        }

        if (filledSlots.isEmpty()) return; // beholdningen er allerede tom

        // Vælg en tilfældig fyldt slot og drop den
        int slotToDrop = filledSlots.get(mob.getRandom().nextInt(filledSlots.size()));
        ItemStack toDrop = carried.get(slotToDrop);
        mob.spawnAtLocation(serverLevel, toDrop); // drop item i verden
        carried.set(slotToDrop, ItemStack.EMPTY); // ryd slottet
    }
}
