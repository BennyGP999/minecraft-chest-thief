package email.pedersen.client;

import email.pedersen.ChestThiefMod;
import email.pedersen.client.model.ChestThiefModel;
import email.pedersen.client.renderer.ChestThiefRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.renderer.entity.EntityRenderers;

/**
 * Klient-side indgangspunkt for Chest Thief-modden.
 * I Minecraft er koden opdelt i to dele:
 *   - Server-side: den logik der styrer spillets regler (ChestThiefMod)
 *   - Klient-side: det du ser på skærmen (denne klasse)
 * Klient-koden kører KUN på spillerens computer, ikke på serveren.
 * Det er vigtigt fordi en dedicated server ikke har en skærm at tegne på.
 * Denne klasse registrerer:
 *   1. EntityRenderer: fortæller Minecraft "brug ChestThiefRenderer til at tegne denne mob"
 *   2. ModelLayer: registrerer 3D-modellen der bruges til at tegne mob'en
 * Fabric kalder onInitializeClient() automatisk ved opstart på klient-siden.
 */
public class ChestThiefModClient implements ClientModInitializer {

    /**
     * Kører én gang når klienten starter med modden.
     * Her sættes alt det visuelle op.
     */
    @Override
    public void onInitializeClient() {
        // Registrer rendereren: fortæl Minecraft at den skal bruge ChestThiefRenderer
        // til at tegne alle CHEST_THIEF-entiteter på skærmen.
        // ChestThiefRenderer::new er en constructor-reference — Minecraft opretter
        // en ny renderer og sender en Context med resourceloaders osv.
        // EntityRendererRegistry (Fabric) er deprecated — brug vanilla EntityRenderers i stedet.
        EntityRenderers.register(
                ChestThiefMod.CHEST_THIEF_ENTITY_TYPE,
                ChestThiefRenderer::new
        );

        // Registrer model-laget: knytter MODEL_LAYER-nøglen til ChestThiefModel.createBodyLayer().
        // createBodyLayer() definerer selve 3D-formen (kuber, dimensioner, UV-koordinater).
        // MODEL_LAYER er den nøgle ChestThiefRenderer bruger til at bede om modellen.
        ModelLayerRegistry.registerModelLayer(
                ChestThiefRenderer.MODEL_LAYER,
                ChestThiefModel::createBodyLayer
        );

        ChestThiefMod.LOGGER.info("Chest Thief client initialized!");
    }
}
