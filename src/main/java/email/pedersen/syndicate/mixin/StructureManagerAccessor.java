package email.pedersen.syndicate.mixin;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Giver adgang til StructureManager's private structureCheck-felt.
 *
 * Behovet: SyndicateBasePlacer.doPlaceBase() skal kalde StructureCheck.onStructureLoad()
 * for at registrere det nyplacerede StructureStart i MC's in-memory cache — ellers
 * finder /locate ikke basen i den aktuelle server-session (kun efter serverrestart,
 * hvor chunk-NBT'en genindlæses og onStructureLoad() kaldes automatisk).
 *
 * StructureCheck er et privat felt i StructureManager; @Accessor er den mixin-mekanisme
 * der eksponerer det uden reflection og uden at bryde adgangskontrol.
 */
@Mixin(StructureManager.class)
public interface StructureManagerAccessor {

    /**
     * Returnerer StructureManager's structureCheck-felt.
     * Feltet hedder "structureCheck" i mojmaps — det er den instans der vedligeholder
     * loadedChunks-cachen som /locate bruger til at slå StructureStart-objekter op.
     *
     * @return structureCheck-instansen
     */
    @Accessor("structureCheck")
    StructureCheck getStructureCheck();
}
