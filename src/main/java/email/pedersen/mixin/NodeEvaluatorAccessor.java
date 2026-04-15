package email.pedersen.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor-interface der eksponerer den beskyttede getNode()-metode fra NodeEvaluator.
 *
 * Hvorfor er dette nødvendigt?
 *   @Shadow i et Mixin kan kun referere til metoder der er direkte defineret i
 *   target-klassen — ikke nedarvet fra en superklasse. getNode() er defineret
 *   i NodeEvaluator men kun nedarvet (ikke overskrevet) i WalkNodeEvaluator.
 *   Derfor kan WalkNodeEvaluatorMixin ikke @Shadow den direkte.
 *
 *   @Invoker løser dette ved at definere et interface på NodeEvaluator selv,
 *   som ved kørsel caster til NodeEvaluator og kalder den beskyttede metode.
 *   WalkNodeEvaluatorMixin caster (NodeEvaluatorAccessor)(Object)this for at
 *   kalde invokeGetNode().
 *
 * getNode() og mob-feltet er begge i NodeEvaluator men ikke overskrevet/gentaget
 * i WalkNodeEvaluator — @Shadow i WalkNodeEvaluatorMixin kan derfor ikke finde dem.
 * Dette interface eksponerer begge via @Accessor og @Invoker direkte på NodeEvaluator.
 */
@Mixin(NodeEvaluator.class)
public interface NodeEvaluatorAccessor {

    /**
     * Læser NodeEvaluator.mob — den Mob-instans der navigerer.
     * Bruges i WalkNodeEvaluatorMixin til at tjekke om mob'en er en ChestThiefEntity.
     */
    @Accessor("mob")
    Mob getMob();

    /**
     * Kalder NodeEvaluator.getNode(int x, int y, int z).
     * Henter fra cachen eller opretter en ny Node for de givne koordinater.
     */
    @Invoker("getNode")
    Node invokeGetNode(int x, int y, int z);
}
