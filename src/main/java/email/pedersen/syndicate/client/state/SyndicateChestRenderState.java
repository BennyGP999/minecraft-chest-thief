package email.pedersen.syndicate.client.state;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * RenderState for SyndicateChestBlockEntity — holder de data rendereren behøver pr. frame.
 *
 * I MC 26.1.1 bruger BlockEntityRenderer et "RenderState"-mønster identisk med entity-rendering:
 *   1. extractRenderState() kopierer data fra block entity til denne klasse (server-data → kopi)
 *   2. submit() bruger kun denne klasses felter til at tegne blokken
 *
 * Adskillelsen sikrer at rendering-tråden aldrig tilgår block entity-data direkte,
 * hvilket eliminerer potentielle data-race-conditions.
 *
 * Felterne initialiseres til safe defaults der matcher en sydvendt enkelt-kiste med lukket låg.
 */
public class SyndicateChestRenderState extends BlockEntityRenderState {

    /**
     * Kiste-typen: SINGLE (enkelt), LEFT (venstre halvdel), RIGHT (højre halvdel).
     * Bestemmer hvilken model der bruges til rendering.
     */
    public ChestType type = ChestType.SINGLE;

    /**
     * Drejeretning for kistens front-side.
     * Bruges til at beregne model-transformationen (ChestRenderer.modelTransformation).
     */
    public Direction facing = Direction.SOUTH;

    /**
     * Lågets åbningsgrad fra 0.0 (lukket) til 1.0 (fuldt åben).
     * Værdien er allerede "cubic-easet" — ChestModel.setupAnim() bruger den direkte.
     */
    public float open = 0.0f;
}
