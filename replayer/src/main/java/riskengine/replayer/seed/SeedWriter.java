package riskengine.replayer.seed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Writes accounts.csv, positions.csv and book.meta. No timestamps or host data, so reruns are byte-identical. */
public final class SeedWriter {
    public static final String ACCOUNTS_HEADER = "account_id,archetype,thin_funded,cash_balance";
    public static final String POSITIONS_HEADER = "account_id,instrument_id,lots,qty,avg_price";

    private SeedWriter() {}

    public static String accountsCsv(SeededBook book) {
        StringBuilder sb = new StringBuilder(ACCOUNTS_HEADER).append('\n');
        for (var a : book.accounts()) {
            sb.append(a.accountId()).append(',').append(a.archetype()).append(',')
              .append(a.thinFunded()).append(',').append(a.cashBalance().toPlainString()).append('\n');
        }
        return sb.toString();
    }

    public static String positionsCsv(SeededBook book) {
        StringBuilder sb = new StringBuilder(POSITIONS_HEADER).append('\n');
        for (var p : book.positions()) {
            sb.append(p.accountId()).append(',').append(p.instrumentId()).append(',')
              .append(p.lots()).append(',').append(p.qty()).append(',')
              .append(p.avgPrice().toPlainString()).append('\n');
        }
        return sb.toString();
    }

    /** @return the files written, name to SHA-256, in sorted order */
    public static Map<String, String> write(SeededBook book, String instrumentsSha256, Path dir) {
        String accounts = accountsCsv(book), positions = positionsCsv(book);
        var cfg = book.config();
        var t = cfg.targets();
        StringBuilder meta = new StringBuilder()
                .append("generator_version=2\n")
                .append("seed=").append(cfg.seed()).append('\n')
                .append("accounts=").append(cfg.accounts()).append('\n')
                .append("as_of=").append(cfg.asOf()).append('\n')
                .append("valuation=").append(cfg.asOf()).append("T15:30:00+05:30\n")
                .append("start_vix=").append(cfg.vix().toPlainString()).append('\n')
                .append("start_spot=").append(book.startSpots()).append('\n')
                .append("thin_funded_bp=").append(cfg.thinFundedBp()).append('\n')
                .append("target_utilisation_bp=thin:").append(t.thinLoBp()).append("-").append(t.thinHiBp())
                .append(",funded:").append(t.fundedLoBp()).append("-").append(t.fundedHiBp())
                .append(",spread:").append(t.spreadLoBp()).append("-").append(t.spreadHiBp()).append('\n')
                .append("archetype_weights=").append(cfg.archetypeWeights()).append('\n')
                .append("underlying_weights=").append(cfg.underlyingWeights()).append('\n')
                .append("underlyings_used=").append(String.join(",", book.underlyings())).append('\n')
                // Cash balances are sized with the margin engine: a different config gives a different book.
                .append("margin_config_sha256=").append(cfg.margin().fingerprint()).append('\n')
                .append("instruments_sha256=").append(instrumentsSha256).append('\n')
                .append("accounts_sha256=").append(sha256(accounts)).append('\n')
                .append("positions_sha256=").append(sha256(positions)).append('\n');
        Map<String, String> written = new TreeMap<>();
        try {
            Files.createDirectories(dir);
            for (var f : Map.of("accounts.csv", accounts, "positions.csv", positions, "book.meta", meta.toString()).entrySet()) {
                Files.writeString(dir.resolve(f.getKey()), f.getValue(), StandardCharsets.UTF_8);
                written.put(f.getKey(), sha256(f.getValue()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return written;
    }

    public static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
