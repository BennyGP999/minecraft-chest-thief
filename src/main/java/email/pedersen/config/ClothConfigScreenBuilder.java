package email.pedersen.config;

import email.pedersen.chestthief.config.ChestThiefConfig;
import email.pedersen.syndicate.config.SyndicateConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Bygger Cloth Config-skærmen med alle indstillinger fra ChestThiefConfig og SyndicateConfig.
 *
 * Klassen er adskilt fra ClothConfigIntegration for at udnytte JVM'ens lazy class loading:
 * ClothConfigScreenBuilder indlæses kun når brugeren faktisk åbner skærmen,
 * ikke ved modopstart. Det sikrer at modden starter normalt selv uden Cloth Config.
 *
 * Skærmen har to kategorier (faner):
 *   "Chest Thief" — adfærd, spawn, usynlighed, afgang og lyd
 *   "Syndicate"   — verdensgeneration, vagter, loot og opdagelse
 *
 * Alle labels og tooltips er lokaliserede via Component.translatable() —
 * nøglerne er defineret i assets/chest_thief/lang/ og assets/syndicate/lang/.
 * Felter med kompleks type (biome-liste, item-values-map) vises ikke —
 * de redigeres direkte i JSON-filerne i config/-mappen.
 */
public class ClothConfigScreenBuilder {

    /**
     * Opretter og returnerer config-skærmen.
     * Kaldes af ClothConfigIntegration's lambda når brugeren klikker "Config" i Mod Menu.
     *
     * @param parent skærmen der åbnede config — bruges som "tilbage"-destination
     * @return den færdigbyggede Cloth Config-skærm
     */
    public static Screen build(Screen parent) {
        ChestThiefConfig ct  = ChestThiefConfig.getInstance();
        SyndicateConfig  syn = SyndicateConfig.getInstance();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.chest_thief.title"))
                // Gemmer begge configs til disk når brugeren trykker "Gem".
                // validate() kaldes inden skrivning for at normalisere eventuelle
                // inkonsistente værdier (f.eks. min > max).
                .setSavingRunnable(() -> {
                    ct.save();
                    syn.save();
                });

        ConfigEntryBuilder e = builder.entryBuilder();

        buildChestThiefCategory(builder, e, ct);
        buildSyndicateCategory(builder, e, syn);

        return builder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chest Thief-kategorien
    // ─────────────────────────────────────────────────────────────────────────

    private static void buildChestThiefCategory(ConfigBuilder builder, ConfigEntryBuilder e,
                                                 ChestThiefConfig ct) {
        ConfigCategory cat = builder.getOrCreateCategory(
                Component.translatable("config.chest_thief.category"));

        // ── Kistesøgning ─────────────────────────────────────────────────────
        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.chest_interaction_interval"),
                        ct.getChestInteractionIntervalTicks(), 1, 300)
                .setDefaultValue(60)
                .setSaveConsumer(ct::setChestInteractionIntervalTicks)
                .setTooltip(Component.translatable("config.chest_thief.chest_interaction_interval.tooltip"))
                .build());

        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.chest_thief.chest_detection_radius"),
                        ct.getChestDetectionRadius())
                .setDefaultValue(100.0)
                .setMin(1.0).setMax(500.0)
                .setSaveConsumer(ct::setChestDetectionRadius)
                .setTooltip(Component.translatable("config.chest_thief.chest_detection_radius.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.chest_detection_max_vertical_dist"),
                        ct.getChestDetectionMaxVerticalDist(), 1, 256)
                .setDefaultValue(16)
                .setSaveConsumer(ct::setChestDetectionMaxVerticalDist)
                .setTooltip(Component.translatable("config.chest_thief.chest_detection_max_vertical_dist.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.max_carry_slots"),
                        ct.getMaxCarrySlots(), 1, 27)
                .setDefaultValue(5)
                .setSaveConsumer(ct::setMaxCarrySlots)
                .setTooltip(Component.translatable("config.chest_thief.max_carry_slots.tooltip"))
                .build());

        // ── Spawn ─────────────────────────────────────────────────────────────
        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.spawn_weight"),
                        ct.getSpawnWeight(), 1, 200)
                .setDefaultValue(20)
                .setSaveConsumer(ct::setSpawnWeight)
                .setTooltip(Component.translatable("config.chest_thief.spawn_weight.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.spawn_min_group"),
                        ct.getSpawnMinGroup(), 1, 10)
                .setDefaultValue(1)
                .setSaveConsumer(ct::setSpawnMinGroup)
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.spawn_max_group"),
                        ct.getSpawnMaxGroup(), 1, 10)
                .setDefaultValue(2)
                .setSaveConsumer(ct::setSpawnMaxGroup)
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.spawn_min_nearby_chests"),
                        ct.getSpawnMinNearbyChests(), 0, 20)
                .setDefaultValue(2)
                .setSaveConsumer(ct::setSpawnMinNearbyChests)
                .setTooltip(Component.translatable("config.chest_thief.spawn_min_nearby_chests.tooltip"))
                .build());

        // ── Panik og berserk ─────────────────────────────────────────────────
        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.chest_thief.panic_chance"),
                        ct.getPanicChance())
                .setDefaultValue(0.4)
                .setMin(0.0).setMax(1.0)
                .setSaveConsumer(ct::setPanicChance)
                .setTooltip(Component.translatable("config.chest_thief.panic_chance.tooltip"))
                .build());

        cat.addEntry(e.startIntField(
                        Component.translatable("config.chest_thief.panic_duration_ticks"),
                        ct.getPanicDurationTicks())
                .setDefaultValue(200)
                .setMin(1).setMax(2400)
                .setSaveConsumer(ct::setPanicDurationTicks)
                .setTooltip(Component.translatable("config.chest_thief.panic_duration_ticks.tooltip"))
                .build());

        cat.addEntry(e.startIntField(
                        Component.translatable("config.chest_thief.leave_duration_ticks"),
                        ct.getLeaveDurationTicks())
                .setDefaultValue(200)
                .setMin(1).setMax(2400)
                .setSaveConsumer(ct::setLeaveDurationTicks)
                .setTooltip(Component.translatable("config.chest_thief.leave_duration_ticks.tooltip"))
                .build());

        cat.addEntry(e.startIntField(
                        Component.translatable("config.chest_thief.berserk_duration_ticks"),
                        ct.getBerserkDurationTicks())
                .setDefaultValue(300)
                .setMin(1).setMax(6000)
                .setSaveConsumer(ct::setBerserkDurationTicks)
                .build());

        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.chest_thief.berserk_speed_multiplier"),
                        ct.getBerserkSpeedMultiplier())
                .setDefaultValue(1.5)
                .setMin(1.0).setMax(5.0)
                .setSaveConsumer(ct::setBerserkSpeedMultiplier)
                .setTooltip(Component.translatable("config.chest_thief.berserk_speed_multiplier.tooltip"))
                .build());

        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.chest_thief.berserk_follow_range"),
                        ct.getBerserkFollowRange())
                .setDefaultValue(50.0)
                .setMin(10.0).setMax(200.0)
                .setSaveConsumer(ct::setBerserkFollowRange)
                .setTooltip(Component.translatable("config.chest_thief.berserk_follow_range.tooltip"))
                .build());

        // ── Nat og usynlighed ────────────────────────────────────────────────
        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.chest_thief.night_speed_bonus"),
                        ct.getNightSpeedBonus())
                .setDefaultValue(0.12)
                .setMin(0.0).setMax(1.0)
                .setSaveConsumer(ct::setNightSpeedBonus)
                .setTooltip(Component.translatable("config.chest_thief.night_speed_bonus.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.stealth_min_ticks"),
                        ct.getStealthMinTicks(), 1, 600)
                .setDefaultValue(60)
                .setSaveConsumer(ct::setStealthMinTicks)
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.stealth_max_ticks"),
                        ct.getStealthMaxTicks(), 1, 600)
                .setDefaultValue(140)
                .setSaveConsumer(ct::setStealthMaxTicks)
                .build());

        cat.addEntry(e.startIntField(
                        Component.translatable("config.chest_thief.stealth_cooldown_min_ticks"),
                        ct.getStealthCooldownMinTicks())
                .setDefaultValue(200)
                .setMin(1).setMax(4800)
                .setSaveConsumer(ct::setStealthCooldownMinTicks)
                .build());

        cat.addEntry(e.startIntField(
                        Component.translatable("config.chest_thief.stealth_cooldown_max_ticks"),
                        ct.getStealthCooldownMaxTicks())
                .setDefaultValue(400)
                .setMin(1).setMax(4800)
                .setSaveConsumer(ct::setStealthCooldownMaxTicks)
                .build());

        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.chest_thief.stealth_chance"),
                        ct.getStealthChance())
                .setDefaultValue(0.85)
                .setMin(0.0).setMax(1.0)
                .setSaveConsumer(ct::setStealthChance)
                .build());

        // ── Afgang ───────────────────────────────────────────────────────────
        cat.addEntry(e.startIntField(
                        Component.translatable("config.chest_thief.depart_delay_ticks"),
                        ct.getDepartDelayTicks())
                .setDefaultValue(400)
                .setMin(0).setMax(9600)
                .setSaveConsumer(ct::setDepartDelayTicks)
                .setTooltip(Component.translatable("config.chest_thief.depart_delay_ticks.tooltip"))
                .build());

        cat.addEntry(e.startIntField(
                        Component.translatable("config.chest_thief.depart_duration_ticks"),
                        ct.getDepartDurationTicks())
                .setDefaultValue(600)
                .setMin(20).setMax(9600)
                .setSaveConsumer(ct::setDepartDurationTicks)
                .setTooltip(Component.translatable("config.chest_thief.depart_duration_ticks.tooltip"))
                .build());

        cat.addEntry(e.startIntField(
                        Component.translatable("config.chest_thief.max_age_ticks"),
                        ct.getMaxAgeTicks())
                .setDefaultValue(48000)
                .setMin(1200).setMax(480000)
                .setSaveConsumer(ct::setMaxAgeTicks)
                .setTooltip(Component.translatable("config.chest_thief.max_age_ticks.tooltip"))
                .build());

        // ── Lyd ──────────────────────────────────────────────────────────────
        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.leaving_sound_min_ticks"),
                        ct.getLeavingSoundMinTicks(), 1, 600)
                .setDefaultValue(80)
                .setSaveConsumer(ct::setLeavingSoundMinTicks)
                .setTooltip(Component.translatable("config.chest_thief.leaving_sound_min_ticks.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.chest_thief.leaving_sound_max_ticks"),
                        ct.getLeavingSoundMaxTicks(), 1, 600)
                .setDefaultValue(160)
                .setSaveConsumer(ct::setLeavingSoundMaxTicks)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Syndicate-kategorien
    // ─────────────────────────────────────────────────────────────────────────

    private static void buildSyndicateCategory(ConfigBuilder builder, ConfigEntryBuilder e,
                                                SyndicateConfig syn) {
        ConfigCategory cat = builder.getOrCreateCategory(
                Component.translatable("config.syndicate.category"));

        // ── Verdensgeneration ────────────────────────────────────────────────
        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.syndicate.base_spacing_chunks"),
                        syn.getBaseSpacingChunks(), 16, 128)
                .setDefaultValue(32)
                .setSaveConsumer(syn::setBaseSpacingChunks)
                .setTooltip(Component.translatable("config.syndicate.base_spacing_chunks.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.syndicate.base_separation_chunks"),
                        syn.getBaseSeparationChunks(), 1, 60)
                .setDefaultValue(12)
                .setSaveConsumer(syn::setBaseSeparationChunks)
                .setTooltip(Component.translatable("config.syndicate.base_separation_chunks.tooltip"))
                .build());

        // ── Vagter ───────────────────────────────────────────────────────────
        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.syndicate.guard_arrow_damage"),
                        syn.getGuardArrowDamage())
                .setDefaultValue(2.3)
                .setMin(0.5).setMax(20.0)
                .setSaveConsumer(syn::setGuardArrowDamage)
                .setTooltip(Component.translatable("config.syndicate.guard_arrow_damage.tooltip"))
                .build());

        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.syndicate.guards_per_item"),
                        syn.getGuardsPerItem())
                .setDefaultValue(0.5)
                .setMin(0.0).setMax(10.0)
                .setSaveConsumer(syn::setGuardsPerItem)
                .setTooltip(Component.translatable("config.syndicate.guards_per_item.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.syndicate.min_guards"),
                        syn.getMinGuards(), 0, 50)
                .setDefaultValue(4)
                .setSaveConsumer(syn::setMinGuards)
                .setTooltip(Component.translatable("config.syndicate.min_guards.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.syndicate.max_guards"),
                        syn.getMaxGuards(), 0, 50)
                .setDefaultValue(16)
                .setSaveConsumer(syn::setMaxGuards)
                .setTooltip(Component.translatable("config.syndicate.max_guards.tooltip"))
                .build());

        cat.addEntry(e.startIntField(
                        Component.translatable("config.syndicate.guard_respawn_interval_ticks"),
                        syn.getGuardRespawnIntervalTicks())
                .setDefaultValue(6000)
                .setMin(200).setMax(72000)
                .setSaveConsumer(syn::setGuardRespawnIntervalTicks)
                .setTooltip(Component.translatable("config.syndicate.guard_respawn_interval_ticks.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.syndicate.guard_respawn_player_buffer"),
                        syn.getGuardRespawnPlayerBuffer(), 0, 100)
                .setDefaultValue(8)
                .setSaveConsumer(syn::setGuardRespawnPlayerBuffer)
                .setTooltip(Component.translatable("config.syndicate.guard_respawn_player_buffer.tooltip"))
                .build());

        // ── Loot ─────────────────────────────────────────────────────────────
        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.syndicate.starter_loot_total"),
                        syn.getStarterLootTotal(), 0, 27)
                .setDefaultValue(10)
                .setSaveConsumer(syn::setStarterLootTotal)
                .setTooltip(Component.translatable("config.syndicate.starter_loot_total.tooltip"))
                .build());

        cat.addEntry(e.startIntSlider(
                        Component.translatable("config.syndicate.starter_valuable_count"),
                        syn.getStarterValuableCount(), 0, 27)
                .setDefaultValue(3)
                .setSaveConsumer(syn::setStarterValuableCount)
                .setTooltip(Component.translatable("config.syndicate.starter_valuable_count.tooltip"))
                .build());

        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.syndicate.raid_threshold"),
                        syn.getRaidThreshold())
                .setDefaultValue(0.3)
                .setMin(0.0).setMax(1.0)
                .setSaveConsumer(syn::setRaidThreshold)
                .setTooltip(Component.translatable("config.syndicate.raid_threshold.tooltip"))
                .build());

        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.syndicate.loot_delivery_radius"),
                        syn.getLootDeliveryRadius())
                .setDefaultValue(1000000.0)
                .setMin(1.0).setMax(1000000.0)
                .setSaveConsumer(syn::setLootDeliveryRadius)
                .setTooltip(Component.translatable("config.syndicate.loot_delivery_radius.tooltip"))
                .build());

        // ── Opdagelse ────────────────────────────────────────────────────────
        cat.addEntry(e.startDoubleField(
                        Component.translatable("config.syndicate.map_trader_chance"),
                        syn.getMapTraderChance())
                .setDefaultValue(0.25)
                .setMin(0.0).setMax(1.0)
                .setSaveConsumer(syn::setMapTraderChance)
                .setTooltip(Component.translatable("config.syndicate.map_trader_chance.tooltip"))
                .build());

        cat.addEntry(e.startBooleanToggle(
                        Component.translatable("config.syndicate.create_xaero_waypoints"),
                        syn.isCreateXaeroWaypoints())
                .setDefaultValue(false)
                .setSaveConsumer(syn::setCreateXaeroWaypoints)
                .setTooltip(Component.translatable("config.syndicate.create_xaero_waypoints.tooltip"))
                .build());
    }
}
