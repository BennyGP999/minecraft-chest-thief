package email.pedersen.syndicate.mixin;

import email.pedersen.syndicate.SyndicateMod;
import email.pedersen.syndicate.config.SyndicateConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Mixin på WanderingTrader — tilføjer Syndikatskort til handelslisten.
 *
 * WanderingTrader.updateTrades(ServerLevel) kaldes ved spawn og fylder handlerlisten
 * (getOffers()) med tilfældige trades. Vi injicerer RETURN i denne metode for at
 * tilføje vores handel *efter* at de normale trades er oprettet — så vi aldrig
 * overskriver nogen af dem, og vores handel er altid det sidste tilbud på listen.
 *
 * Handel: 32 emeralds → 1 Syndikatskort
 *   - maxUses=1: hvert Syndikatskort svarer til én base; handles genbruges ikke
 *   - xpValue=0: ingen XP til spilleren (kortet er en service, ikke en normal handel)
 *   - priceMultiplier=0.0: prisen er fast — ej påvirket af handelsmønsteret (demand)
 *
 * Sandsynlighed styres af SyndicateConfig.mapTraderChance (standard 0.50 = 50 %).
 * Bruger level.getRandom() fremfor en lokal Random for at respektere serverens seed-baserede
 * RNG-stream — giver reproducerbare resultater ved samme world seed.
 */
@Mixin(WanderingTrader.class)
public abstract class WanderingTraderMixin {

    /**
     * Injicerer ved RETURN af updateTrades() — efter at alle normale trades er opsat.
     * CallbackInfo er nødvendig selv om vi ikke canceller — det er Mixin's API-krav.
     *
     * @param level serverniveauet — bruges til RNG via level.getRandom()
     * @param ci    callback-info (ubrugt men påkrævet af Mixin)
     */
    @Inject(method = "updateTrades", at = @At("RETURN"))
    private void onUpdateTrades(ServerLevel level, CallbackInfo ci) {
        WanderingTrader self = (WanderingTrader) (Object) this;
        SyndicateConfig config = SyndicateConfig.getInstance();

        // Træk loddet — kun den konfigurerede andel af Wandering Traders sælger kortet
        if (level.getRandom().nextFloat() >= config.getMapTraderChance()) return;

        // Opret handlen: 32 emeralds → 1 Syndikatskort
        // ItemCost(emerald, 32) er betalingen; Optional.empty() = ingen sekundær betaling
        // maxUses=1 sikrer at kortet kan handles præcis én gang pr. trader
        MerchantOffer offer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 32),
                Optional.empty(),
                new ItemStack(SyndicateMod.SYNDICATE_MAP),
                1,   // maxUses
                0,   // xpValue
                0.0f // priceMultiplier
        );

        self.getOffers().add(offer);
        SyndicateMod.LOGGER.debug("Wandering Trader at {} is offering a Syndicate Map", self.blockPosition());
    }
}
