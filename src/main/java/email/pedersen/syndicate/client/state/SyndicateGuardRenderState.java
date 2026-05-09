package email.pedersen.syndicate.client.state;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * Snapshot af SyndicateGuardEntity's tilstand til brug i rendering.
 *
 * Siden MC 1.21.2 er rendering opdelt i to faser:
 *   1. extractRenderState(): kopiér data fra entiteten (server-thread)
 *   2. Tegning (render-thread): brug kun RenderState — ingen adgang til entiteten
 *
 * Vagter har ingen tilstandsspecifikke animationer — standarden fra HumanoidRenderState
 * (gang-cyklus, skadeoverlay osv.) er tilstrækkelig.
 */
public class SyndicateGuardRenderState extends HumanoidRenderState {
    // Ingen ekstra felter — standard HumanoidRenderState dækker behovet
}
