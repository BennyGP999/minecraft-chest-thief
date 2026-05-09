package email.pedersen.syndicate.block;

import com.mojang.serialization.MapCodec;
import email.pedersen.syndicate.SyndicateMod;
import email.pedersen.syndicate.block.entity.SyndicateChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * En speciel kiste der tilhører Syndikatet — kan placeres fra creative-menuen.
 *
 * Udvider vanilla ChestBlock for at arve al kiste-logik:
 *   - FACING + TYPE (SINGLE/LEFT/RIGHT) + WATERLOGGED block-state properties
 *   - Automatisk sammenkobling til dobbelt-kiste når to syndicate-kister placeres side om side
 *   - Låg-animation og container-åbning
 *   - Vanilla stiger-mekanik (blokerer ikke stigen i base-design)
 *
 * Den eneste forskel fra en normal kiste:
 *   - Registreret med SYNDICATE_CHEST_BLOCK_ENTITY_TYPE → SyndicateChestBlockEntity
 *     der registrerer positionen i SyndicateChestTracker i stedet for ChestTracker
 *   - Tilpasset renderer (SyndicateChestRenderer) med syndikat-tekstur
 *   - Kan placeres frit i creative → bruges i sjove custom-opstillinger
 *
 * Loot-aflevering:
 *   Tyvene afleverer loot til den nærmeste syndicate-kiste (via SyndicateChestTracker),
 *   ikke til en fast syndicate-base. Det er intentionelt — spilleren kan opsætte kister
 *   strategisk for at tiltrække tyve-loot.
 */
public class SyndicateChestBlock extends ChestBlock {

    /**
     * MapCodec bruges af Minecrafts serialiseringssystem til at gem/load blok-instanser.
     * simpleCodec(factory) opretter en codec der kalder BlockBehaviour.Properties-konstruktøren
     * og intet andet — passer til vores simple blok.
     */
    private static final MapCodec<SyndicateChestBlock> CODEC = simpleCodec(SyndicateChestBlock::new);

    /**
     * Constructor til brug i simpleCodec (fra Properties).
     * Sender standard kiste-lyde videre til ChestBlock.
     *
     * @param properties BlockBehaviour-egenskaber (styrke, lyd osv.)
     */
    public SyndicateChestBlock(BlockBehaviour.Properties properties) {
        super(
                () -> SyndicateMod.SYNDICATE_CHEST_BLOCK_ENTITY_TYPE,
                SoundEvents.CHEST_OPEN,
                SoundEvents.CHEST_CLOSE,
                properties
        );
    }

    /**
     * Returnerer blokkens codec — påkrævet af AbstractChestBlock-hierarkiet.
     * Bruges til datafixer-serialisering og struktur-stamping.
     *
     * @return MapCodec<SyndicateChestBlock>
     */
    @Override
    public MapCodec<SyndicateChestBlock> codec() {
        return CODEC;
    }

    /**
     * Opretter en SyndicateChestBlockEntity i stedet for vanilla ChestBlockEntity.
     *
     * HVORFOR denne override er nødvendig:
     * ChestBlock.newBlockEntity() er hardkodet til at kalde new ChestBlockEntity(BlockPos, BlockState)
     * — den 2-argument-konstruktør der bruger BlockEntityType.CHEST (vanilla) internt.
     * Supplier'en vi sætter i ChestBlock-konstruktøren bruges KUN til getTicker(), ikke til
     * newBlockEntity(). Uden denne override crasher MC ved block-load med:
     *   "Invalid block entity minecraft:chest ... got Block{syndicate:syndicate_chest}"
     *
     * @param pos   blok-positionen i verdenen
     * @param state den aktuelle block state
     * @return en ny SyndicateChestBlockEntity med korrekt SYNDICATE_CHEST_BLOCK_ENTITY_TYPE
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SyndicateChestBlockEntity(pos, state);
    }
}
