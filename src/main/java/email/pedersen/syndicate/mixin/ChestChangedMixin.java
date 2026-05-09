package email.pedersen.syndicate.mixin;

import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Mixin på ChestBlockEntity — bruges til raid-detektion i TASK-S10.
 *
 * Minecraft kalder setChanged() på ChestBlockEntity ved enhver ændring i kistens indhold
 * (items tilføjet, fjernet eller omfordelt). Det er det rigtige tidspunkt at tjekke om
 * basen er raidet: wasOpened sættes til true, og raid-betingelserne evalueres.
 *
 * Implementering: TASK-S10.
 * Fil holdes som tom stub for at undgå build-fejl (syndicate.mixins.json refererer den).
 */
@Mixin(ChestBlockEntity.class)
public abstract class ChestChangedMixin {
    // TODO S10: inject i setChanged() og kald SyndicateBaseManager
}
