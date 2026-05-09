package email.pedersen.chestthief.mixin;

import email.pedersen.chestthief.ChestTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin der fanger kiste-oprettelse og registrerer positioner i ChestTracker.
 * Hvad er et Mixin?
 *   Et Mixin er en teknik til at ændre eller udvide Minecrafts egne klasser
 *   uden at have adgang til kildekoden. Med @Inject kan vi "springe ind"
 *   i en eksisterende metode og køre vores egen kode.
 * Hvorfor injecte i BlockEntity.setLevel()?
 *   Metoden setLevel() kaldes for ALLE BlockEntities (kister, ovne, chests, osv.)
 *   når de placeres i verdenen — enten af en spiller eller ved chunk-indlæsning fra disk.
 *   Det er det eneste sted vi kan fange en kiste der indlæses fra en gemmet verden.
 * Vi injecter i BlockEntity (superklassen) og tjekker ved runtime om instansen
 * er en ChestBlockEntity. Det er mere robust end at injecte direkte i ChestBlockEntity,
 * fordi det fanger alle undertyper af kister.
 * Bemærk:
 *   Da ChestTracker bruger et HashSet, tilføjes duplikater (samme position, to kald)
 *   automatisk kun én gang. Det er sikkert at kalde addChest() flere gange.
 */
@Mixin(BlockEntity.class)
public abstract class ChestBlockEntityMixin {

    /**
     * Injectes i slutningen ("TAIL") af BlockEntity.setLevel().
     * Annotationen At("TAIL") betyder at vores kode kører EFTER den originale metode er færdig.
     * CallbackInfo bruges af Mixin-systemet til at kommunikere med os (vi bruger den ikke).
     * Tjekker to betingelser:
     *   1. Vi er på serveren (!level.isClientSide()) — kister spores kun server-side
     *   2. Instansen er en ChestBlockEntity — vi er kun interesseret i kister
     * @param level den verden BlockEntity'en nu er en del af
     * @param ci    Mixin callback-info (ubrugt men påkrævet af API'et)
     */
    @Inject(method = "setLevel", at = @At("TAIL"))
    private void onSetLevel(Level level, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        // isClientSide() er true på klientens side (det du ser) — vi vil kun registrere på serveren
        if (!level.isClientSide() && self instanceof ChestBlockEntity) {
            ChestTracker.addChest(level, self.getBlockPos());
        }
    }
}
