package riskengine.api.orders;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import riskengine.events.OrderRequest;

/**
 * The API's view of the {@code orders} table. Every statement is parameterised.
 *
 * <p>Two writers share a row, and they cannot get in each other's way:
 * <ul>
 *   <li>the API inserts it as PENDING just before writing the order to the log, and never touches an
 *       existing row ({@code ON CONFLICT DO NOTHING});</li>
 *   <li>the engine-out writers set ACCEPTED / REJECTED / CANCELLED once the shard has spoken.</li>
 * </ul>
 * So whichever gets there first, a decision is never overwritten by PENDING.
 *
 * <p>The foreign keys on account and instrument do one more job: an order naming something Postgres does
 * not have cannot be inserted, which is how the API learns it is unknown without holding reference data.
 */
@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param requestKey user id + request id, kept for tracing an order back to the request that made it
     * @return false if the account or the instrument does not exist
     */
    public boolean insertPending(OrderRequest o, String userId, String requestKey) {
        try {
            jdbc.update("""
                    INSERT INTO orders (id, account_id, instrument_id, side, qty, order_type, origin, status, request_id, created_by)
                    VALUES (?, ?, ?, ?, ?, 'MARKET', 'USER', 'PENDING', ?, ?)
                    ON CONFLICT (id) DO NOTHING""",
                    o.orderId(), o.accountId(), o.instrumentId(), o.side().name(), o.qty(), requestKey, "trader:" + userId);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /** Removes an order that was never written to the log. A row the engine has already decided is left alone. */
    public void deleteIfStillPending(String orderId) {
        jdbc.update("DELETE FROM orders WHERE id = ? AND status = 'PENDING' AND last_event_seq = -1", orderId);
    }

    public Optional<OrderView> find(String orderId) {
        List<OrderView> rows = jdbc.query(
                "SELECT id, account_id, instrument_id, side, qty, status, reject_reason, required_after, available_after FROM orders WHERE id = ?",
                (rs, n) -> new OrderView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7),
                        rs.getBigDecimal(8), rs.getBigDecimal(9)),
                orderId);
        return rows.stream().findFirst();
    }
}
