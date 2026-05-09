package email.pedersen.syndicate.block.entity;

import email.pedersen.chestthief.ChestTracker;
import email.pedersen.syndicate.SyndicateChestTracker;
import email.pedersen.syndicate.SyndicateMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for SyndicateChestBlock.
 *
 * Arver al kiste-funktionalitet (inventory, låg-animation, container-åbning) fra ChestBlockEntity.
 * Det eneste vi tilføjer er registrering i SyndicateChestTracker i stedet for ChestTracker.
 *
 * Hvorfor ikke ChestTracker?
 *   ChestBlockEntityMixin injecter i BlockEntity.setLevel() og registrerer ALLE ChestBlockEntity-
 *   instanser i ChestTracker — inklusiv vores. Tyvene ville ellers stjæle fra syndicate-kister.
 *   Vi undgår dette ved at kalde ChestTracker.removeChest() straks efter at mixinen har tilføjet os,
 *   og erstatter det med en registrering i SyndicateChestTracker.
 *
 * Dobbeltkiste:
 *   Når to SyndicateChestBlockEntity-blokke placeres side om side, danner de én dobbelt-kiste
 *   via vanilla ChestBlock-logikken. Begge blokkes block entities registreres individuelt i
 *   SyndicateChestTracker. Loot-leveringen finder den nærmeste blok — dvs. én af de to halve.
 */
public class SyndicateChestBlockEntity extends ChestBlockEntity {

    /**
     * Constructor brugt af BlockEntityType.create() når blokken placeres i verdenen.
     * Sender SYNDICATE_CHEST_BLOCK_ENTITY_TYPE videre til ChestBlockEntity (ikke CHEST-typen).
     *
     * @param pos   kistens position i verdenen
     * @param state blokkens block state ved oprettelsestidspunktet
     */
    public SyndicateChestBlockEntity(BlockPos pos, BlockState state) {
        super(SyndicateMod.SYNDICATE_CHEST_BLOCK_ENTITY_TYPE, pos, state);
    }

    /**
     * Kaldes når blokken indlæses i verdenen (chunk-load eller blok-placering).
     *
     * Rækkefølge:
     *   1. super.setLevel() kalder BlockEntity.setLevel() → ChestBlockEntityMixin tilføjer os
     *      til ChestTracker (vi er en ChestBlockEntity-subklasse)
     *   2. Vi fjerner os straks fra ChestTracker (tyvene må ikke stjæle fra syndicate-kister)
     *   3. Vi tilføjer os til SyndicateChestTracker (tyve afleverer loot hertil)
     *
     * @param level verdenen der sættes
     */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!level.isClientSide()) {
            // Annullér mixin-tilføjelse til ChestTracker — tyvene må ikke stjæle fra os
            ChestTracker.removeChest(level, getBlockPos());
            // Registrér i syndicate-trackeren — tyve afleverer loot hertil
            SyndicateChestTracker.addChest(level, getBlockPos());
        }
    }

    /**
     * Kaldes når blokken fjernes fra verdenen (blok-ødelæggelse eller chunk-unload).
     * Afregistrerer kisten fra SyndicateChestTracker så trackeren ikke indeholder "spøgelses-kister".
     */
    @Override
    public void setRemoved() {
        if (hasLevel() && !getLevel().isClientSide()) {
            SyndicateChestTracker.removeChest(getLevel(), getBlockPos());
        }
        super.setRemoved();
    }

    /**
     * Standardnavnet på containeren — vises i kistens GUI-titel.
     * Oversættes via lang-filen: "block.syndicate.syndicate_chest".
     *
     * @return oversat komponent til GUI-titlen
     */
    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.syndicate.syndicate_chest");
    }
}
