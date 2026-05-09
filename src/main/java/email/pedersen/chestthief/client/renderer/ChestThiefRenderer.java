package email.pedersen.chestthief.client.renderer;

import email.pedersen.chestthief.ChestThiefMod;
import email.pedersen.chestthief.client.model.ChestThiefModel;
import email.pedersen.chestthief.client.state.ChestThiefRenderState;
import email.pedersen.chestthief.entity.ChestThiefEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NullMarked;

/**
 * Renderer for Chest Thief-entiteten — ansvarlig for at tegne mob'en på skærmen.
 * Arver fra HumanoidMobRenderer, som er Minecrafts standardklasse til at tegne
 * humanoide mobs (to ben, to arme, krop, hoved — som en zombie eller spiller).
 * Tre type-parametre (siden Minecraft 1.21.2 bruger "RenderState-mønsteret"):
 *   1. ChestThiefEntity   — entiteten der tegnes (server-side data)
 *   2. ChestThiefRenderState — et snapshot af data der overføres til rendering
 *   3. ChestThiefModel    — selve 3D-modellen med alle kuber og animationer
 * Renderingssystemet er opdelt i to faser pr. frame:
 *   1. extractRenderState(): Kopier data fra entiteten til RenderState (dette kald er trådsikkert)
 *   2. submit() / tegning: Brug RenderState til at tegne (ingen adgang til entiteten her)
 * Denne opdeling gør rendering mere effektiv på multi-core processorer.
 */
@NullMarked
public class ChestThiefRenderer extends HumanoidMobRenderer<ChestThiefEntity, ChestThiefRenderState, ChestThiefModel> {

    /**
     * Stien til tekstur-billedet der "males" på mob'ens 3D-model.
     * Filen ligger i: src/main/resources/assets/chest_thief/textures/entity/chest_thief2.png
     */
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(ChestThiefMod.MOD_ID, "textures/entity/chest_thief2.png");

    /**
     * En nøgle der identificerer dette model-lag i Minecrafts model-registre.
     * Bruges af ChestThiefModClient til at registrere modellen,
     * og af rendereren til at hente den færdigbagede model.
     * Format: (mod-id + "chest_thief", "main")
     * "main" er lagets navn — komplekse mobs kan have flere lag (rustning, hat osv.)
     */
    public static final ModelLayerLocation MODEL_LAYER =
            new ModelLayerLocation(
                    Identifier.fromNamespaceAndPath(ChestThiefMod.MOD_ID, "chest_thief"),
                    "main"
            );

    /**
     * Constructor — oprettes af Minecraft én gang pr. entitets-type.
     * context.bakeLayer(MODEL_LAYER) henter den registrerede model-definition
     * og "bager" den til en færdig ModelPart-træstruktur klar til rendering.
     * 0.5f er skyggeradius (shadow) under mob'en i blokke.
     * @param context indeholder resourceloaders og model-baker fra Minecraft
     */
    public ChestThiefRenderer(EntityRendererProvider.Context context) {
        super(context, new ChestThiefModel(context.bakeLayer(MODEL_LAYER)), 0.5f);
    }

    /**
     * Returnerer stien til tekstur-billedet for en given render-tilstand.
     * Kaldes af rendering-systemet for hvert frame for at bestemme
     * hvilken tekstur der skal bruges. Vi bruger altid den samme tekstur,
     * men metoden kunne i princippet returnere forskellige teksturer
     * baseret på state (f.eks. en beskadiget tekstur ved lavt liv).
     * @param state den aktuelle render-tilstand for mob'en
     * @return stien til tekstur-filen
     */
    @Override
    public Identifier getTextureLocation(ChestThiefRenderState state) {
        return TEXTURE;
    }

    /**
     * Opretter en ny, tom RenderState-instans.
     * Minecraft kalder denne metode én gang og genbruger instansen hvert frame.
     * @return en ny ChestThiefRenderState med standardværdier
     */
    @Override
    public ChestThiefRenderState createRenderState() {
        return new ChestThiefRenderState();
    }

    /**
     * Kopierer data fra entiteten til render-tilstanden én gang pr. frame.
     * Dette er broen mellem server-side entitetsdata og klient-side rendering.
     * super.extractRenderState() håndterer standarddata (position, rotation, animationer).
     * Vi tilføjer vores egne felter oven på.
     * Mth.lerp() (linear interpolation) bruges til at beregne en glidende overgang
     * mellem forrige tick og nuværende tick, så animationen ser smooth ud selv ved
     * lavere TPS (ticks per sekund). partialTick er et tal 0.0–1.0.
     * @param entity      den Chest Thief-entitet vi henter data fra
     * @param state       render-tilstanden der opdateres
     * @param partialTick brøkdel af tick siden sidst opdatering (0.0–1.0)
     */
    @Override
    public void extractRenderState(ChestThiefEntity entity, ChestThiefRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.isTargetingChest = entity.getTargetChestPos() != null;
        state.isNightTime = entity.isNightTime();
        // Beregn interpoleret hoved-pitch (op/ned-rotation) i radianer
        // Lerp giver en glidende overgang mellem to tick-målinger
        state.headPitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot()) * (float) (Math.PI / 180.0);
    }
}
