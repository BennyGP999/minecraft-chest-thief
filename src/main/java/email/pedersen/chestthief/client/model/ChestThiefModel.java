package email.pedersen.chestthief.client.model;

import email.pedersen.chestthief.client.state.ChestThiefRenderState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import org.jspecify.annotations.NullMarked;

/**
 * 3D-modellen for Chest Thief-entiteten.

 * En model i Minecraft er en samling af kasser (cubes) der tilsammen danner
 * en figur. Modellen definerer:
 *   - Formen (hvilke kasser, deres størrelse og position)
 *   - UV-koordinater (hvilket område af tekstur-billedet der "males" på hver kasse)
 *   - Animationer (hvordan kasserne bevæger sig)

 * Vi arver fra HumanoidModel som allerede definerer en standard-humanoid
 * (hoved, hat-overlay, krop, højre arm, venstre arm, højre ben, venstre ben).
 * Vi overskriver kasseDefinitionerne med vores egne mål og UV-koordinater,
 * og tilføjer egne animationer i setupAnim().

 * Tekstur-layout (64×64 pixels):
 *   Koordinaterne i texOffs(u, v) angiver hvor på tekstur-billedet (PNG)
 *   "malingen" til den pågældende kasse hentes fra.
 *   Dette matcher standardhjemanoid-layoutet fra Minecraft.

 * Animationerne ændrer rotationen på arme og hoved afhængig af mob'ens tilstand.
 */
@NullMarked
public class ChestThiefModel extends HumanoidModel<ChestThiefRenderState> {

    /**
     * Constructor — modtager en færdigbagt ModelPart fra Minecraft's model-system.
     * ModelPart er et træ af kasse-dele. "root" er roden, og alle andre dele
     * (head, body, right_arm osv.) er børn/barnebørn af root.
     * @param root den færdigbagede rod-del med alle kasse-definitioner
     */
    public ChestThiefModel(ModelPart root) {
        super(root);
    }

    /**
     * Definerer den geometriske form af modellen som en MeshDefinition.
     * Denne metode kører én gang ved registrering og returnerer en beskrivelse
     * af modellen. Minecraft "bager" den derefter til en færdig ModelPart.
     * CubeDeformation bruges til at gøre kasser lidt større end standard
     * (f.eks. til rustning der sidder oven på et lag).
     * @param deformation ekstra størrelsesjustering (CubeDeformation.NONE = ingen justering)
     * @return en MeshDefinition med alle kasse-definitioner
     */
    public static MeshDefinition createMesh(CubeDeformation deformation) {
        // Start med Minecrafts standard-humanoid-mesh (definerer alle de forventede navne)
        MeshDefinition meshDefinition = HumanoidModel.createMesh(deformation, 0.0f);
        PartDefinition partDefinition = meshDefinition.getRoot();

        // Hoved — lidt større end standard for at give et goblin-agtigt udseende
        // texOffs(0, 0): start øverst til venstre på teksturen
        // addBox(x, y, z, bredde, højde, dybde): kasse-dimensioner
        // y = -9.0 placerer toppen af hovedet 9 enheder over "nakken"
        partDefinition.addOrReplaceChild("head",
                CubeListBuilder.create()
                        .texOffs(0, 0)
                        .addBox(-4.0f, -9.0f, -4.0f, 8.0f, 8.0f, 8.0f, deformation),
                PartPose.offset(0.0f, 0.0f, 0.0f)
        );

        // Hat-lag — et overlay oven på hovedet (lidt større end hoved-kassen)
        // texOffs(32, 0): hentes fra højre side af teksturen øverst
        // extend(0.5f): lidt større end den underliggende hoved-kasse
        partDefinition.addOrReplaceChild("hat",
                CubeListBuilder.create()
                        .texOffs(32, 0)
                        .addBox(-4.0f, -9.0f, -4.0f, 8.0f, 8.0f, 8.0f, deformation.extend(0.5f)),
                PartPose.offset(0.0f, 0.0f, 0.0f)
        );

        // Krop
        // texOffs(16, 16): kroppen hentes fra midt på teksturen
        partDefinition.addOrReplaceChild("body",
                CubeListBuilder.create()
                        .texOffs(16, 16)
                        .addBox(-4.0f, 0.0f, -2.0f, 8.0f, 12.0f, 4.0f, deformation),
                PartPose.offset(0.0f, 0.0f, 0.0f)
        );

        // Højre arm — lidt længere end standard for et "rækkende" udseende
        // offset(-5.0, 2.0, 0.0): placeret 5 enheder til højre og 2 ned for kroppen
        partDefinition.addOrReplaceChild("right_arm",
                CubeListBuilder.create()
                        .texOffs(40, 16)
                        .addBox(-3.0f, -2.0f, -2.0f, 4.0f, 13.0f, 4.0f, deformation),
                PartPose.offset(-5.0f, 2.0f, 0.0f)
        );

        // Venstre arm — spejlvendt version af højre arm
        // .mirror(): UV-koordinaterne spejlvendes så teksturen passer på begge sider
        partDefinition.addOrReplaceChild("left_arm",
                CubeListBuilder.create()
                        .texOffs(40, 16).mirror()
                        .addBox(-1.0f, -2.0f, -2.0f, 4.0f, 13.0f, 4.0f, deformation),
                PartPose.offset(5.0f, 2.0f, 0.0f)
        );

        // Højre ben
        // offset(-1.9, 12.0, 0.0): placeret lidt til højre under kroppen
        partDefinition.addOrReplaceChild("right_leg",
                CubeListBuilder.create()
                        .texOffs(0, 16)
                        .addBox(-2.0f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, deformation),
                PartPose.offset(-1.9f, 12.0f, 0.0f)
        );

        // Venstre ben — spejlvendt
        partDefinition.addOrReplaceChild("left_leg",
                CubeListBuilder.create()
                        .texOffs(0, 16).mirror()
                        .addBox(-2.0f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, deformation),
                PartPose.offset(1.9f, 12.0f, 0.0f)
        );

        return meshDefinition;
    }

    /**
     * Opretter det registrerede "layer definition" for denne model.
     * Kaldes én gang af ModelLayerRegistry og returnerer en komplet beskrivelse
     * af modellen inkl. teksturstørrelsen (64×64 pixels).
     * @return en LayerDefinition med mesh-definitionen og teksturstørrelsen
     */
    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshDefinition = createMesh(CubeDeformation.NONE);
        return LayerDefinition.create(meshDefinition, 64, 64); // 64×64 = teksturfilens størrelse
    }

    /**
     * Konfigurerer animationer for hvert frame baseret på mob'ens tilstand.
     * Kaldes hvert frame inden rendering. Her kan vi justere rotationer
     * og positioner på de enkelte dele for at lave animationer.
     * Rækkefølge:
     *   1. super.setupAnim(state) kører standard-humanoid-animationerne
     *      (gang-cyklus: arme og ben svinges) — dette SKAL køre først
     *   2. Derefter overskriver vi med vores egne rotationer
     * Rotation-enheder: Radianer (ikke grader). PI/2 ≈ 1.57 = 90 grader.
     *   Positiv xRot = arm drejer nedad (fremad i human-koordinater)
     *   Negativ xRot = arm drejer opad (bagud)
     * @param state render-tilstanden med mob'ens aktuelle data
     */
    @Override
    public void setupAnim(ChestThiefRenderState state) {
        super.setupAnim(state); // kør standard-animation (gang, sving) FØRST

        // Sæt hoved-pitch baseret på beregnet interpoleret vinkel fra rendereren
        // Dette lader mob'en visuelt kigge op og ned mod kister på forskellige højder
        this.head.xRot = state.headPitch;

        if (state.isTargetingChest) {
            // Sigter på en kiste: arme strakt lidt fremad som om den rækker
            this.rightArm.xRot = -0.6f; // -0.6 rad ≈ -34 grader fremad
            this.leftArm.xRot  = -0.6f;
            this.rightArm.yRot =  0.1f; // lidt udad til siden
            this.leftArm.yRot  = -0.1f;
        }
        // Ellers: standard-animation fra super.setupAnim() bruges (dag og nat)
    }
}
