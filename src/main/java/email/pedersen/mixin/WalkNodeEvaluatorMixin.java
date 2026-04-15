package email.pedersen.mixin;

import email.pedersen.entity.ChestThiefEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// NodeEvaluatorAccessor bruges til at tilgå mob-feltet og getNode()-metoden fra
// NodeEvaluator (superklassen). @Shadow virker ikke for nedarvede members der ikke
// er overskrevet direkte i WalkNodeEvaluator — vi bruger accessor/invoker i stedet.

/**
 * Mixin der udvider WalkNodeEvaluator med kløft-hop-evner for ChestThiefEntity.
 *
 * Hvad er problemet?
 *   Minecraft's WalkNodeEvaluator genererer pathfinding-noder ét blok ad gangen
 *   i hver kardinalretning. Det betyder at pathfinderen aldrig planlægger at hoppe
 *   over en kløft på 2 blokke — den ser kun 1 blok frem og giver op når der er
 *   et hul. Mob'en har fysisk set hoppekraft nok (JUMP_STRENGTH = 0.5 + speed = 0.27
 *   giver ~3 blokke horisontal rækkevidde), men pathfinderen ved det ikke.
 *
 * Hvad gør dette Mixin?
 *   Vi injicerer i slutningen af getNeighbors() — den metode der bestemmer hvilke
 *   noder pathfinderen må gå til fra den aktuelle node. Kun for ChestThiefEntity.
 *   For hver kardinalretning tjekker vi: er der en 2-bloks kløft (ingen fast underlag
 *   1 blok fremme) og en gyldig landingsposition (fast underlag + åbent rum) 2 blokke
 *   fremme? Hvis ja, tilføjer vi landingsnoden til nabosættet.
 *
 * Vigtige begrænsninger:
 *   - Kun for ChestThiefEntity — andre mobs er upåvirkede
 *   - Lava-kløfter undgås (for farligt)
 *   - Nodes der allerede er evalueret (closed = true) springes over
 *   - Naboarrayet har max 32 pladser; vi stopper hvis det er fyldt
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {

    /**
     * Injectes i slutningen ("RETURN") af WalkNodeEvaluator.getNeighbors().
     * Kører EFTER at den normale nabogenerering er færdig.
     *
     * mob-feltet og getNode()-metoden tilgås via NodeEvaluatorAccessor fordi begge
     * er defineret i NodeEvaluator (superklassen) og ikke overskrevet i WalkNodeEvaluator.
     * @Shadow kan ikke finde inherited members der ikke er redefineret i target-klassen.
     *
     * @param neighbors  det præallokerede array af noder (størrelse 32) der udfyldes
     * @param node       den aktuelle node hvorfra naboer beregnes
     * @param cir        Mixin-callback med returværdien (antal gyldige noder i arrayet)
     */
    @Inject(method = "getNeighbors", at = @At("RETURN"), cancellable = true)
    private void injectGapJumpNeighbors(Node[] neighbors, Node node,
                                         CallbackInfoReturnable<Integer> cir) {
        // Hent mob via accessor — @Shadow kan ikke finde inherited fields fra NodeEvaluator
        NodeEvaluatorAccessor accessor = (NodeEvaluatorAccessor)(Object)this;
        Mob mob = accessor.getMob();

        // Kun for Chest Thief — alle andre mobs kører standard pathfinding
        if (!(mob instanceof ChestThiefEntity)) return;

        int count = cir.getReturnValue();
        Level level = mob.level();

        // De fire kardinalretninger i XZ-planet
        // Diagonale kløfter håndteres ikke — de kræver et skråt spring der er upålideligt
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] dir : dirs) {
            // Stop hvis naboarrayet er fyldt (max 32 pladser i vanilla)
            if (count >= neighbors.length) break;

            int dx = dir[0], dz = dir[1];

            // ── KLØFT-START-TJEK (position 1 fremme) ────────────────────────
            // Blokken 1 blok fremme på gulvniveau (node.y - 1) skal mangle fast
            // top-flade — ellers er der ikke en kløft og vi springer over.
            BlockPos pos1Floor = new BlockPos(node.x + dx, node.y - 1, node.z + dz);
            if (level.getBlockState(pos1Floor).isFaceSturdy(level, pos1Floor, Direction.UP)) continue;
            if (level.getFluidState(pos1Floor).is(FluidTags.LAVA)) continue;
            // Trap-doors: en åben horisontal trap-door har ingen fast topflade og
            // ville fejlagtigt blive opfattet som en kløft. Vi springer dem over —
            // vanilla's pathfinder (og OpenGateGoal-logikken) håndterer dem korrekt.
            if (level.getBlockState(pos1Floor).getBlock() instanceof TrapDoorBlock) continue;

            // Luft-tjek ved position 1 (mob'ens fødder-niveau)
            BlockPos pos1Air = new BlockPos(node.x + dx, node.y, node.z + dz);
            if (!level.getBlockState(pos1Air).getCollisionShape(level, pos1Air).isEmpty()) continue;

            // ── FIND LANDINGSPOSITION ────────────────────────────────────────
            // 1-bloks kløft: underlag ved position 2 → landing er ved position 2
            // 2-bloks kløft: ingen underlag ved position 2, underlag ved position 3 → landing ved 3
            //
            // Fejlen i den tidligere version: vi tjekkede KUN position 2 som landing.
            // For en 2-bloks kløft er position 2 stadig i kløften (luft) — underlag
            // findes først ved position 3. Tjekket fejlede, og ingen node blev tilføjet.
            int landX, landZ, landY;

            BlockPos pos2Floor = new BlockPos(node.x + 2 * dx, node.y - 1, node.z + 2 * dz);
            if (level.getBlockState(pos2Floor).isFaceSturdy(level, pos2Floor, Direction.UP)) {
                // 1-bloks kløft — land ved position 2, samme højde
                landX = node.x + 2 * dx;
                landZ = node.z + 2 * dz;
                landY = node.y;
            } else {
                // Kløften fortsætter forbi position 2 — tjek om det er en 2-bloks kløft
                if (level.getFluidState(pos2Floor).is(FluidTags.LAVA)) continue;
                BlockPos pos2Air = new BlockPos(node.x + 2 * dx, node.y, node.z + 2 * dz);
                if (!level.getBlockState(pos2Air).getCollisionShape(level, pos2Air).isEmpty()) continue;

                // Tjek position 3 på SAMME niveau (node.y - 1)
                BlockPos pos3Floor = new BlockPos(node.x + 3 * dx, node.y - 1, node.z + 3 * dz);
                if (level.getBlockState(pos3Floor).isFaceSturdy(level, pos3Floor, Direction.UP)) {
                    // 2-bloks kløft — land ved position 3, samme højde
                    landX = node.x + 3 * dx;
                    landZ = node.z + 3 * dz;
                    landY = node.y;
                } else {
                    // Tjek position 3 ÉT NIVEAU HØJERE (node.y) — f.eks. en kiste der
                    // står på den anden side af kløften. Mob'ens JUMP_STRENGTH = 0.5 giver
                    // ~1.7 blokkes maks. stighøjde — tilstrækkeligt til at rydde +1 blok.
                    BlockPos pos3FloorHigh = new BlockPos(node.x + 3 * dx, node.y, node.z + 3 * dz);
                    if (!level.getBlockState(pos3FloorHigh).isFaceSturdy(level, pos3FloorHigh, Direction.UP)) continue;
                    landX = node.x + 3 * dx;
                    landZ = node.z + 3 * dz;
                    landY = node.y + 1;
                }
            }

            // ── LANDINGS-TJEK ────────────────────────────────────────────────
            // Plads til mob'ens fødder og hoved på landingspositionen.
            //
            // Særtilfælde: kisten kan stå oven på den faste undergrund ved position 3
            // (f.eks. landY=64, undergrund ved 63, kiste ved 64). Kistens blok fylder
            // landFeet-positionen og vi kan ikke lande DER — men vi kan lande OVENPÅ.
            // Hvis landFeet er blokeret men landY+1 er fri og undergrunden holder
            // (landFeet er fast = mob kan stå på den), løfter vi landY med 1.
            BlockPos landFeet = new BlockPos(landX, landY, landZ);
            if (!level.getBlockState(landFeet).getCollisionShape(level, landFeet).isEmpty()) {
                // landFeet er blokeret — tjek om vi kan lande ét niveau højere
                BlockPos landFeetHigh = new BlockPos(landX, landY + 1, landZ);
                if (!level.getBlockState(landFeetHigh).getCollisionShape(level, landFeetHigh).isEmpty()) continue;
                // Blokken ved landFeet er "underlaget" vi lander på — det er OK
                landY = landY + 1;
            }
            BlockPos landHead = new BlockPos(landX, landY + 1, landZ);
            if (!level.getBlockState(landHead).getCollisionShape(level, landHead).isEmpty()) continue;

            // ── OPRET NODE OG TILFØJ TIL NABOER ────────────────────────────
            Node jumpNode = accessor.invokeGetNode(landX, landY, landZ);
            if (jumpNode != null && !jumpNode.closed) {
                if (jumpNode.type == PathType.BLOCKED) {
                    jumpNode.type = PathType.WALKABLE;
                    jumpNode.costMalus = PathType.WALKABLE.getMalus();
                }
                neighbors[count++] = jumpNode;
            }
        }


        // Returner det opdaterede antal noder til pathfinderen
        if (count != cir.getReturnValue()) {
            cir.setReturnValue(count);
        }
    }
}
