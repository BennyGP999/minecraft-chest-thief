package email.pedersen.syndicate.client;

import email.pedersen.syndicate.SyndicateMod;
import email.pedersen.syndicate.client.model.SyndicateGuardModel;
import email.pedersen.syndicate.client.renderer.SyndicateChestRenderer;
import email.pedersen.syndicate.client.renderer.SyndicateGuardRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;

/**
 * Klient-side indgangspunkt for Syndicate-modulet.
 * Kører KUN på spillerens computer — ikke på en dedicated server.
 *
 * Ansvarsområder:
 *   - Registrere renderer for SyndicateGuardEntity (S09)
 *   - Registrere model-lag for SyndicateGuardEntity (S09)
 */
public class SyndicateModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SyndicateMod.LOGGER.info("Syndicate client initializing...");

        // Registrer rendereren: fortæl Minecraft at den skal bruge SyndicateGuardRenderer
        // til at tegne alle GUARD_ENTITY_TYPE-entiteter på skærmen.
        EntityRenderers.register(
                SyndicateMod.GUARD_ENTITY_TYPE,
                SyndicateGuardRenderer::new
        );

        // ThrownItemRenderer viser CarrotArrowEntity som et flyvende carrot-item —
        // visuelt identisk med en kastet gulerod. Kræver at CarrotArrowEntity implementerer
        // ItemSupplier.getItem() der returnerer carrot-ItemStack'en.
        EntityRenderers.register(
                SyndicateMod.CARROT_ARROW,
                ThrownItemRenderer::new
        );

        // Registrer model-laget: knytter MODEL_LAYER-nøglen til SyndicateGuardModel.createBodyLayer().
        // createBodyLayer() definerer selve 3D-formen (standard humanoid 64×64 tekstur-layout).
        ModelLayerRegistry.registerModelLayer(
                SyndicateGuardRenderer.MODEL_LAYER,
                SyndicateGuardModel::createBodyLayer
        );

        // Registrer de tre model-lag til SyndicateChestRenderer (single, left-half, right-half).
        // ChestModel-statiske metoder returnerer LayerDefinition med korrekt kubbe-geometri.
        ModelLayerRegistry.registerModelLayer(
                SyndicateChestRenderer.MODEL_LAYER_SINGLE,
                ChestModel::createSingleBodyLayer
        );
        ModelLayerRegistry.registerModelLayer(
                SyndicateChestRenderer.MODEL_LAYER_LEFT,
                ChestModel::createDoubleBodyLeftLayer
        );
        ModelLayerRegistry.registerModelLayer(
                SyndicateChestRenderer.MODEL_LAYER_RIGHT,
                ChestModel::createDoubleBodyRightLayer
        );

        // Registrer block entity-rendereren for SyndicateChestBlockEntity.
        // Minecraft kalder SyndicateChestRenderer::new én gang pr. block entity-type.
        BlockEntityRendererRegistry.register(
                SyndicateMod.SYNDICATE_CHEST_BLOCK_ENTITY_TYPE,
                SyndicateChestRenderer::new
        );

        SyndicateMod.LOGGER.info("Syndicate client initialized!");
    }
}
