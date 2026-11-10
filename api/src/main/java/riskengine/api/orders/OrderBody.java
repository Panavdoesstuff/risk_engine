package riskengine.api.orders;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import riskengine.events.OrderRequest;

/**
 * The JSON body of POST /orders. Orders are market orders for now, so there is no price.
 *
 * <p>The patterns are deliberately narrow. Besides catching typos, they guarantee that nothing a caller
 * sends can contain the '|' or line breaks that separate fields and events in the log.
 *
 * <p>This is validation of form only. Whether the account and instrument exist, whether the quantity is a
 * whole number of lots, and whether there is margin for it are the shard's decisions: it has the data,
 * the API does not.
 *
 * @param qty units (lots x lot size), positive; the upper bound comes from configuration
 */
public record OrderBody(
        @NotNull @Pattern(regexp = ACCOUNT_ID, message = "must be 1-32 letters, digits, '_' or '-'") String accountId,
        @NotNull @Pattern(regexp = INSTRUMENT_ID, message = "must look like NIFTY-20240627-FUT or NIFTY-20240606-22500-CE") String instrumentId,
        @NotNull OrderRequest.Side side,
        @NotNull @Min(value = 1, message = "must be at least 1") Long qty) {

    static final String ACCOUNT_ID = "[A-Za-z0-9_-]{1,32}";
    static final String INSTRUMENT_ID = "[A-Z][A-Z0-9]{0,19}-[0-9]{8}-(FUT|[0-9]{1,7}(\\.[0-9]{1,2})?-(CE|PE))";
}
