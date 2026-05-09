package email.pedersen.syndicate.entity;

import email.pedersen.syndicate.SyndicateMod;
import email.pedersen.syndicate.config.SyndicateConfig;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * En pil der ser ud som en kastet gulerod og dropper en gulerod ved opsamling.
 *
 * Implementerer ItemSupplier så ThrownItemRenderer kan vise carrot-itemet
 * i stedet for en standard pil-geometri — visuelt identisk med en kastet gulerod.
 *
 * pickup sættes til ALLOWED i constructoren så spillere kan samle gulerødderne op
 * når de rammer en blok.
 */
public class CarrotArrowEntity extends AbstractArrow implements ItemSupplier {

    private static final ItemStack CARROT = new ItemStack(Items.CARROT);

    public CarrotArrowEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
        // Baseskaden hentes fra SyndicateConfig.guardArrowDamage — justerbar i syndicate_config.json.
        // Config er altid indlæst inden en entity kan spawnes (SyndicateMod.onInitialize() kører først).
        this.setBaseDamage(SyndicateConfig.getInstance().getGuardArrowDamage());
        // ALLOWED: spillere kan samle pilen op og får en gulerod
        this.pickup = Pickup.ALLOWED;
    }

    /**
     * Returnerer carrot-ItemStack'en som ThrownItemRenderer bruger til det visuelle.
     * Kaldes af rendereren hvert frame — returnerer en delt instans (immutabel nok til rendering).
     */
    @Override
    public ItemStack getItem() {
        return CARROT;
    }

    /**
     * Hvad spilleren får i inventory ved opsamling.
     * Bruges også som fallback af getPickupItem() i AbstractArrow.
     */
    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(Items.CARROT);
    }

    /**
     * Returnerer vores egen landing-lyd i stedet for Minecrafts standard "pil borer sig ind i træ"-lyd.
     * Kaldes af AbstractArrow's constructor for at sætte det initielle soundEvent-felt,
     * og igen af onHitBlock() via getHitGroundSoundEvent() hver gang guleroden rammer en blok.
     */
    @Override
    protected SoundEvent getDefaultHitGroundSoundEvent() {
        return SyndicateMod.CARROT_HIT_SOUND;
    }

    @Override
    protected void doPostHurtEffects(LivingEntity target) {
        super.doPostHurtEffects(target);
    }
}