package email.pedersen.syndicate.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import email.pedersen.syndicate.block.entity.SyndicateChestBlockEntity;
import email.pedersen.syndicate.client.state.SyndicateChestRenderState;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.MultiblockChestResources;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/**
 * Renderer for SyndicateChestBlockEntity.
 *
 * Genbruger vanilla ChestModel-geometrien for alle tre varianter (single, left, right)
 * og bruger den brugerdefinerede syndikat-tekstur via CHEST_SHEET-atlasset.
 *
 * Rendering-mønsteret i MC 26.1.1:
 *   1. extractRenderState() kopierer type, facing og openness fra block entity → RenderState
 *   2. submit() transformerer PoseStack og kalder SubmitNodeCollector.submitModel()
 *      med SpriteId + SpriteGetter — præcis som vanilla ChestRenderer gør det.
 *
 * Tekstur-systemet (vigtigt):
 *   Kiste-teksturer i MC 26.1.1 er IKKE standalone PNG-filer tilgået via Identifier —
 *   de er sprites i CHEST_SHEET-atlasset (Sheets.CHEST_SHEET). Atlasset bygges fra alle
 *   PNG-filer i assets/<namespace>/textures/entity/chest/ på tværs af alle namespaces
 *   (defineret i assets/minecraft/atlases/chests.json).
 *
 *   Vores syndicate_chest.png inkluderes automatisk i atlasset fordi den ligger i
 *   assets/syndicate/textures/entity/chest/syndicate_chest.png.
 *   SpriteId'et er (CHEST_SHEET, "syndicate:entity/chest/syndicate_chest").
 *
 * Model-lag (ModelLayerLocation) er defineret her og registreres af SyndicateModClient.
 */
public class SyndicateChestRenderer implements BlockEntityRenderer<SyndicateChestBlockEntity, SyndicateChestRenderState> {

    /** Sprite for enkelt-kiste — syndicate_chest.png. */
    private static final SpriteId SPRITE_SINGLE = new SpriteId(
            Sheets.CHEST_SHEET,
            Identifier.fromNamespaceAndPath("syndicate", "entity/chest/syndicate_chest")
    );

    /** Sprite for venstre halvdel af dobbeltkiste — syndicate_chest_left.png. */
    private static final SpriteId SPRITE_LEFT = new SpriteId(
            Sheets.CHEST_SHEET,
            Identifier.fromNamespaceAndPath("syndicate", "entity/chest/syndicate_chest_left")
    );

    /** Sprite for højre halvdel af dobbeltkiste — syndicate_chest_right.png. */
    private static final SpriteId SPRITE_RIGHT = new SpriteId(
            Sheets.CHEST_SHEET,
            Identifier.fromNamespaceAndPath("syndicate", "entity/chest/syndicate_chest_right")
    );

    /**
     * Model-lag for enkelt-kiste (single-chest geometri fra vanilla ChestModel).
     * Registreres i SyndicateModClient med ChestModel.createSingleBodyLayer() som definition.
     */
    public static final ModelLayerLocation MODEL_LAYER_SINGLE =
            new ModelLayerLocation(Identifier.fromNamespaceAndPath("syndicate", "syndicate_chest"), "main");

    /**
     * Model-lag for venstre halvdel af dobbeltkiste.
     * Registreres med ChestModel.createDoubleBodyLeftLayer().
     */
    public static final ModelLayerLocation MODEL_LAYER_LEFT =
            new ModelLayerLocation(Identifier.fromNamespaceAndPath("syndicate", "syndicate_chest_left"), "main");

    /**
     * Model-lag for højre halvdel af dobbeltkiste.
     * Registreres med ChestModel.createDoubleBodyRightLayer().
     */
    public static final ModelLayerLocation MODEL_LAYER_RIGHT =
            new ModelLayerLocation(Identifier.fromNamespaceAndPath("syndicate", "syndicate_chest_right"), "main");

    /**
     * De tre model-instanser grupperet i MultiblockChestResources.
     * MultiblockChestResources.select(type) returnerer den korrekte model baseret på SINGLE/LEFT/RIGHT.
     */
    private final MultiblockChestResources<ChestModel> models;

    /**
     * Sprite-getter der slår sprites op i CHEST_SHEET-atlasset hvert frame.
     * Hentes fra context.sprites() i constructoren — atlasset er garanteret loaded på det tidspunkt.
     */
    private final SpriteGetter sprites;

    /**
     * Constructor — Minecraft opretter én instans pr. block entity-type.
     * context.bakeLayer() "bager" den registrerede LayerDefinition til en færdig ModelPart-struktur.
     * context.sprites() giver adgang til CHEST_SHEET-atlasset.
     *
     * @param context indeholder BERenderer-baker og sprite-getter
     */
    public SyndicateChestRenderer(BlockEntityRendererProvider.Context context) {
        this.models = new MultiblockChestResources<>(
                new ChestModel(context.bakeLayer(MODEL_LAYER_SINGLE)),
                new ChestModel(context.bakeLayer(MODEL_LAYER_LEFT)),
                new ChestModel(context.bakeLayer(MODEL_LAYER_RIGHT))
        );
        this.sprites = context.sprites();
    }

    /**
     * Opretter en ny, tom RenderState-instans.
     * Minecraft kalder denne metode én gang og genbruger instansen hvert frame.
     */
    @Override
    public SyndicateChestRenderState createRenderState() {
        return new SyndicateChestRenderState();
    }

    /**
     * Kopierer data fra block entity til render state én gang pr. frame.
     * Adskillelsen sikrer trådsikkerhed: submit() tilgår kun render state, aldrig block entity.
     *
     * @param entity      blokentiteten der tegnes
     * @param state       render state der opdateres
     * @param partialTick interpoleringsbrøkdel (0.0–1.0)
     * @param camera      kamerets position (bruges til synligheds-check)
     * @param crumbling   nedbrydnings-overlay ved blok-minering
     */
    @Override
    public void extractRenderState(SyndicateChestBlockEntity entity, SyndicateChestRenderState state,
                                   float partialTick, Vec3 camera,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, camera, crumbling);

        BlockState blockState = entity.getLevel() != null
                ? entity.getBlockState()
                : Blocks.CHEST.defaultBlockState();

        state.type = blockState.hasProperty(ChestBlock.TYPE)
                ? blockState.getValue(ChestBlock.TYPE)
                : ChestType.SINGLE;

        state.facing = blockState.getValue(ChestBlock.FACING);

        // Cubic easing på låget: openNess er lineær (0→1), vi ønsker en blød start og hurtig afslutning.
        // Vanilla-formlen: eased = 1 - (1 - raw)^3
        float raw = entity.getOpenNess(partialTick);
        float inv = 1.0f - raw;
        state.open = 1.0f - inv * inv * inv;
    }

    /**
     * Tegner kisten ved hjælp af render state og SubmitNodeCollector.
     *
     * Transformationsrækkefølge:
     *   1. ChestRenderer.modelTransformation(facing) roterer modellen korrekt baseret på facing
     *   2. ChestModel.setupAnim(open) animerer låget til den korrekte åbningsgrad
     *   3. submitModel() med SpriteId + SpriteGetter sender geometrien til render-pipelinen
     *      via CHEST_SHEET-atlasset — præcis som vanilla ChestRenderer gør det.
     *
     * Det tredje int-argument (0) er "flags" til submitModel — vanilla sætter det til 0.
     *
     * @param state       render state med type, facing, open, light, breakProgress
     * @param poseStack   transformationsstack — allerede sat til blokkens position
     * @param collector   render-pipeline-interface
     * @param cameraState kamerets render-tilstand (ubrugt her)
     */
    @Override
    public void submit(SyndicateChestRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState) {
        poseStack.pushPose();

        poseStack.mulPose(ChestRenderer.modelTransformation(state.facing));

        ChestModel model = models.select(state.type);

        SpriteId sprite = switch (state.type) {
            case LEFT  -> SPRITE_LEFT;
            case RIGHT -> SPRITE_RIGHT;
            default    -> SPRITE_SINGLE;
        };

        collector.submitModel(
                model,
                state.open,
                poseStack,
                state.lightCoords,
                OverlayTexture.NO_OVERLAY,
                -1,
                sprite,
                sprites,
                0,
                state.breakProgress
        );

        poseStack.popPose();
    }
}
