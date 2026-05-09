package email.pedersen.syndicate.client.model;

import email.pedersen.syndicate.client.state.SyndicateGuardRenderState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;

/**
 * 3D-model for SyndicateGuardEntity.
 *
 * Arver fra HumanoidModel som definerer standard-humanoid-formen
 * (hoved, hat-overlay, krop, arme, ben). Vi bruger standarddimensionerne
 * uden tilpasninger — vagterne ser ud som generic humanoids, og al
 * visuel differentiering sker via tekstur-filen (guard.png).
 *
 * Tekstur-layout: 64×64 pixels, standard humanoid UV-koordinater.
 * Animationer: kun standard gang-cyklus fra HumanoidModel.setupAnim().
 */
public class SyndicateGuardModel extends HumanoidModel<SyndicateGuardRenderState> {

    /**
     * Constructor — modtager den færdigbagede rod-ModelPart fra Minecraft.
     * @param root rodknuden med alle under-dele (head, body, arms, legs)
     */
    public SyndicateGuardModel(ModelPart root) {
        super(root);
    }

    /**
     * Definerer modellens geometri som en MeshDefinition.
     * Bruger HumanoidModel's standarddefinition — ingen tilpassede kasse-størrelser.
     * @param deformation ekstra størrelsesjustering (NONE = ingen)
     * @return MeshDefinition med standard humanoid-geometri
     */
    public static MeshDefinition createMesh(CubeDeformation deformation) {
        return HumanoidModel.createMesh(deformation, 0.0f);
    }

    /**
     * Registrerings-indgangspunkt for model-laget.
     * Kaldet af ModelLayerRegistry i SyndicateModClient.
     * @return LayerDefinition med standard humanoid-mesh og 64×64 teksturstørrelse
     */
    public static LayerDefinition createBodyLayer() {
        return LayerDefinition.create(createMesh(CubeDeformation.NONE), 64, 64);
    }
}
