package email.pedersen.syndicate.entity;

import email.pedersen.syndicate.SyndicateMod;
import email.pedersen.syndicate.entity.ai.SkeletonLikeRangedCombatGoal;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Vagt-entity der bevogtger en syndikats-base.
 *
 * En vagt spawner indendørs i basen (på emerald_block-markørpositioner fra .nbt-filen)
 * og forlader aldrig basen — ikke via kode, men via level-design: stigen er den eneste
 * udgang, og vagterne kan ikke navigere op ad stiger. Se CLAUDE.md for detaljer om
 * containment-konventionen.
 *
 * Antal vagter skalerer med lootets størrelse:
 *   targetGuards = clamp(floor(lootCount × guardsPerItem), minGuards, maxGuards)
 * Formel og parametre defineres i SyndicateConfig.
 *
 * Spawnes og despawnes udelukkende af SyndicateBaseManager.
 * UUIDs spores i SyndicateBase.guardUUIDs så manageren ved hvem der er i live.
 */
public class SyndicateGuardEntity extends Monster  implements RangedAttackMob {

    /**
     * Constructor — oprettet af EntityType.create() i SyndicateBaseManager.
     * @param type  entity-typen registreret i SyndicateMod (syndicate:guard)
     * @param level verdenen entity'en tilhører
     */
    public SyndicateGuardEntity(EntityType<? extends SyndicateGuardEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Definerer vagters basisattributter.
     * Disse værdier er bevidst konservative — vagterne er inde i en bunker, ikke ude i åbent
     * terræn, og bør ikke fremstå uovervindelige ved første møde.
     * Registreres via FabricDefaultAttributeRegistry i SyndicateMod.onInitialize().
     *
     * Pileskaden styres IKKE herfra — den sættes via AbstractArrow.setBaseDamage() i
     * CarrotArrowEntity og hentes fra SyndicateConfig.guardArrowDamage.
     * ATTACK_DAMAGE er kun med fordi Monster.createMonsterAttributes() forventer det.
     *
     * @return en builder med max_health=20, movement_speed=0.25, follow_range=20, attack_speed=1
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 20.0)
                .add(Attributes.ATTACK_SPEED, 1.0);
    }

    /**
     * Registrerer AI-goals i prioritetsrækkefølge.
     *
     * goalSelector (hvad vagten gør):
     *   0 – FloatGoal:                      svøm, druknér ikke
     *   1 – SkeletonLikeRangedCombatGoal:  skyder gulerødder mod spillere inden for synslinje
     *   2 – WaterAvoidingRandomStrollGoal:  vandrer tilfældigt — vand undgås (sjældent relevant indendørs)
     *   3 – LookAtPlayerGoal:               kigger på spillere inden for 8 blokke
     *
     * targetSelector (hvem vagten angriber):
     *   0 – NearestAttackableTargetGoal<Player>: angriber den nærmeste spiller inden for follow_range.
     *       mustSeeTarget=false: vagten kræver IKKE synslinje for at vælge et mål — den kan høre/mærke
     *       spilleren gennem vægge. SkeletonLikeRangedCombatGoal håndterer synslinje-kravet til selve
     *       skuddet og bevæger vagten ind i position hvis LOS mangler.
     *   1 – HurtByTargetGoal: retalierer mod det der skader den.
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SkeletonLikeRangedCombatGoal(this));

        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0f));

        // mustSeeTarget=false: vagten vælger mål uden at kræve synslinje — nødvendigt fordi
        // vagter befinder sig indendørs og har vægge/loft mellem sig og spilleren.
        // Med true ville NearestAttackableTargetGoal aldrig finde et mål, og vagten ville
        // kun bruge LookAtPlayerGoal (prioritet 3) uden nogensinde at angribe.
        this.targetSelector.addGoal(0, new NearestAttackableTargetGoal<>(this, Player.class, false));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
    }

    /**
     * Forhindrer vagten i at despawne naturligt.
     * Monster-subklasser despawner normalt hvis ingen spiller er i nærheden.
     * Vagterne ejes af basen og skal kun fjernes eksplicit (ved basens raid eller ved død).
     *
     * @return true — vagten persisterer altid
     */
    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    /**
     * Affyrer en gulerod-pil mod målet.
     *
     * Vigtigt: den 2-argument AbstractArrow-konstruktør placerer pilen ved (0, 0, 0)
     * — det er standard for alle Entity-subklasser der ikke sætter positionen eksplicit.
     * setPos() skal kaldes FØR shoot(), ellers affyres pilen fra verdens oprindelse og
     * rammer aldrig spilleren, selvom retningsvektoren (dx, dy, dz) er korrekt beregnet.
     *
     * Pilen placeres 0.1 blok under øjenhøjde for at undgå kollision med vagtens
     * eget hitbox, og for at justere bane mod spillerens krop i stedet for ansigt.
     *
     * @param target   det mål der skydes mod
     * @param velocity ubrugt (kald-signatur krævet af RangedAttackMob-interfacet)
     */
    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        CarrotArrowEntity arrow = new CarrotArrowEntity(
                SyndicateMod.CARROT_ARROW,
                this.level()
        );

        // Flyt pilen til vagtens position INDEN shoot() — ellers starter den ved (0,0,0).
        arrow.setPos(this.getX(), this.getEyeY() - 0.1, this.getZ());
        arrow.setOwner(this);

        double dx = target.getX() - this.getX();
        double dy = target.getEyeY() - this.getEyeY();
        double dz = target.getZ() - this.getZ();

        arrow.shoot(dx, dy, dz, 1.6f, 1.0f);

        this.level().addFreshEntity(arrow);

        // Afspil skyde-lyden på vagtens position.
        // null som første argument: alle spillere i nærheden hører lyden (ikke kun én).
        // Pitch varieres let (0.8–1.06) for at give hvert skud et organisk feel.
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SyndicateMod.GUARD_SHOOT_SOUND, SoundSource.HOSTILE,
                1.0f, 0.8f + this.getRandom().nextFloat() * 0.26f);
    }
}
