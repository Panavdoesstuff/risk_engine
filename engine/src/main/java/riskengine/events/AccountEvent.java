package riskengine.events;

/** An event that belongs to one account and therefore to one partition. */
public sealed interface AccountEvent permits OrderRequest, Fill, OrderClosed, SquareOffResult {
    String accountId();
}
