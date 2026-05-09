package email.pedersen.syndicate.client.renderer;

import email.pedersen.syndicate.SyndicateMod;
import email.pedersen.syndicate.client.model.SyndicateGuardModel;
import email.pedersen.syndicate.client.state.SyndicateGuardRenderState;
import email.pedersen.syndicate.entity.SyndicateGuardEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;

/**
 * Renderer for SyndicateGuardEntity — ansvarlig for at tegne vagten på skærmen.
 *
 * Arver fra HumanoidMobRenderer med tre type-parametre (MC 1.21.2+ RenderState-mønsteret):
 *   1. SyndicateGuardEntity   — entiteten der tegnes (server-side data)
 *   2. SyndicateGuardRenderState — snapshot af data der overføres til rendering
 *   3. SyndicateGuardModel    — selve 3D-modellen
 *
 * Vagter bruger standard humanoid-animationer og kræver ingen brugerdefineret
 * extractRenderState() — al standarddata (position, rotation, gang-cyklus) overføres
 * automatisk af HumanoidMobRenderer.extractRenderState().
 */
public class SyndicateGuardRenderer extends HumanoidMobRenderer<SyndicateGuardEntity, SyndicateGuardRenderState, SyndicateGuardModel> {

    /**
     * Stien til guard-teksturen i syndicate-namespacet.
     * Filen ligger i: src/main/resources/assets/syndicate/textures/entity/guard.png
     */
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("syndicate", "textures/entity/guard.png");

    /**
     * Nøgle der identificerer guard-modellaget i Minecrafts model-registre.
     * Registreres af SyndicateModClient og bruges af rendereren til at bake modellen.
     * Format: (syndicate:guard, "main") — "main" er lagets navn.
     */
    public static final ModelLayerLocation MODEL_LAYER =
            new ModelLayerLocation(
                    Identifier.fromNamespaceAndPath("syndicate", "guard"),
                    "main"
            );

    /**
     * Constructor — oprettes af Minecraft én gang pr. entitets-type.
     * context.bakeLayer(MODEL_LAYER) henter den registrerede model-definition
     * og "bager" den til en færdig ModelPart-træstruktur klar til rendering.
     * 0.5f er skyggeradius under vagten i blokke.
     *
     * @param context indeholder resourceloaders og model-baker fra Minecraft
     */
    public SyndicateGuardRenderer(EntityRendererProvider.Context context) {
        super(context, new SyndicateGuardModel(context.bakeLayer(MODEL_LAYER)), 0.5f);
    }

    /**
     * Returnerer guard-teksturen.
     * Kaldet af rendering-systemet hvert frame — vagter bruger altid den samme tekstur.
     *
     * @param state den aktuelle render-tilstand for vagten
     * @return stien til guard.png
     */
    @Override
    public Identifier getTextureLocation(SyndicateGuardRenderState state) {
        return TEXTURE;
    }

    /**
     * Opretter en ny, tom RenderState-instans.
     * Minecraft kalder denne metode én gang og genbruger instansen hvert frame.
     *
     * @return en ny SyndicateGuardRenderState med standardværdier
     */
    @Override
    public SyndicateGuardRenderState createRenderState() {
        return new SyndicateGuardRenderState();
    }
}
