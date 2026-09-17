package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.content.PinOffers;
import dev.andre.homecontrol.core.playback.ServiceLinks;

/** The play sheet's "paste a link to open this title directly" offer, if any. */
public record PinOfferView(String upgradeOf, String service, String serviceName) {

    static PinOfferView of(PinOffers.Offer offer) {
        String serviceName = offer.service() == null ? null : ServiceLinks.displayName(offer.service()).orElse(null);
        return new PinOfferView(offer.upgradeOf(), offer.service(), serviceName);
    }
}
