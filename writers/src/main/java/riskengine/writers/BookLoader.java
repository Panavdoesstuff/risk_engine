package riskengine.writers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import riskengine.host.BookCsv;
import riskengine.margin.AccountBook;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentCsv;

/**
 * Loads the start-of-day book (the same files the shard host reads) into Postgres: instruments, accounts
 * and opening positions. Orders, positions and margin events all point at these rows.
 *
 * <p>Existing rows are left alone (ON CONFLICT DO NOTHING), so loading again is harmless and never
 * overwrites what the writers have projected since.
 */
public final class BookLoader {
    private BookLoader() {}

    public record Loaded(int instruments, int accounts, int positions) {}

    public static Loaded load(String jdbcUrl, String user, String password, Path instrumentsFile, Path bookDir, LocalDate tradeDate) throws SQLException {
        List<Instrument> instruments = InstrumentCsv.read(instrumentsFile);
        List<AccountBook> books = BookCsv.read(bookDir);
        int positions = 0;
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, password)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO instruments (id, underlying, type, expiry, strike, lot_size, trade_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING""")) {
                for (Instrument i : instruments) {
                    ps.setString(1, i.id());
                    ps.setString(2, i.underlying());
                    ps.setString(3, i.type().name());
                    ps.setObject(4, i.expiry());
                    if (i.strike() == null) ps.setNull(5, Types.NUMERIC);
                    else ps.setBigDecimal(5, i.strike());
                    ps.setInt(6, i.lotSize());
                    ps.setObject(7, tradeDate);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO accounts (id, cash_balance, collateral_value) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING""")) {
                for (AccountBook b : books) {
                    ps.setString(1, b.id());
                    ps.setBigDecimal(2, b.cash());
                    ps.setBigDecimal(3, b.collateral());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO positions (account_id, instrument_id, net_qty, avg_price) VALUES (?, ?, ?, ?)
                    ON CONFLICT (account_id, instrument_id) DO NOTHING""")) {
                for (AccountBook b : books) {
                    for (var e : b.positions().entrySet()) {
                        ps.setString(1, b.id());
                        ps.setString(2, e.getKey());
                        ps.setLong(3, e.getValue().qty());
                        ps.setBigDecimal(4, e.getValue().avgPrice());
                        ps.addBatch();
                        positions++;
                    }
                }
                ps.executeBatch();
            }
            c.commit();
        }
        return new Loaded(instruments.size(), books.size(), positions);
    }
}
