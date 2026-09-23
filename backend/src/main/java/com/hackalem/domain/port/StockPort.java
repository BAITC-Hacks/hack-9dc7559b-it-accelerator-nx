package com.hackalem.domain.port;
import static com.hackalem.domain.port.Contracts.*;
import java.util.List;
public interface StockPort {
    /** Must return canonical base-unit buckets, fresh quantitative offers, never boolean stock. */
    List<OfferSnapshot> getOffers(List<Selection> items, TrustedScope scope);
}
