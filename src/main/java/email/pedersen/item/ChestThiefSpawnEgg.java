package email.pedersen.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Spawn egg-itemet for Chest Thief-entiteten.
 * Et spawn egg er et item man kan bruge i kreativ tilstand til at spawne en mob.
 * Man højreklikker på en blok med æget og en mob spawner.
 * I Minecraft 26.1.x er EntityType ikke længere sendt direkte til SpawnEggItem's
 * constructor — i stedet angives den via Item.Properties.spawnEgg(entityType),
 * som gøres i ChestThiefMod ved registreringen.
 * Klassen er holdt simpel fordi SpawnEggItem allerede håndterer alt:
 *   - Spawn-logik (spawn mob når brugt på en blok)
 *   - Visning i kreativ-menu (håndteres af ChestThiefMod's event-registrering)
 *   - Tekstur/ikon (defineret i items/chest_thief_spawn_egg.json)
 */
public class ChestThiefSpawnEgg extends SpawnEggItem {

    /**
     * @param settings item-egenskaber inkl. id og spawn-egg entity type binding.
     *                 Oprettes i ChestThiefMod med:
     *                 new Item.Properties().setId(...).spawnEgg(CHEST_THIEF_ENTITY_TYPE)
     */
    public ChestThiefSpawnEgg(Item.Properties settings) {
        super(settings);
    }
}
