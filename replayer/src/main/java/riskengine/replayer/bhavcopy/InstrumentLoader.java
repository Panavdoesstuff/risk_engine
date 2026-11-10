package riskengine.replayer.bhavcopy;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import riskengine.ref.Instrument;

/** Bhavcopy (+ contract-lot file for the legacy format) to normalised instruments. */
public final class InstrumentLoader {
    private InstrumentLoader() {}

    /**
     * @param lots         required for legacy bhavcopies; for UDiFF it is optional and, if given,
     *                     cross-checked against NewBrdLotQty
     * @param expectedDate the trade date the bhavcopy must be for (guards against loading the wrong day)
     */
    public static List<Instrument> load(Path bhavcopy, LotSizeTable lots, Set<String> symbols, LocalDate expectedDate) {
        List<BhavRow> rows = BhavcopyParser.parse(bhavcopy, symbols);
        if (rows.isEmpty()) throw new IllegalArgumentException(bhavcopy + ": no rows for symbols " + symbols);
        List<Instrument> out = new ArrayList<>(rows.size());
        Set<String> seen = new HashSet<>();
        for (BhavRow r : rows) {
            if (!r.tradeDate().equals(expectedDate)) {
                throw new IllegalArgumentException(bhavcopy + ": row dated " + r.tradeDate() + ", expected " + expectedDate);
            }
            int lotSize;
            if (r.lotSize() != null) {
                lotSize = r.lotSize();
                if (lots != null && lots.lotSize(r.symbol(), r.expiry()) != lotSize) {
                    throw new IllegalArgumentException("lot size mismatch for " + r.symbol() + " " + r.expiry()
                            + ": bhavcopy " + lotSize + ", lot file " + lots.lotSize(r.symbol(), r.expiry()));
                }
            } else if (lots != null) {
                lotSize = lots.lotSize(r.symbol(), r.expiry());
            } else {
                throw new IllegalArgumentException(bhavcopy + " has no lot sizes; pass the contract-lot file (fo_mktlots.csv)");
            }
            Instrument i = Instrument.of(r.symbol(), r.type(), r.expiry(), r.strike(), lotSize,
                    r.close(), r.settle(), r.openInterest());
            if (!seen.add(i.id())) throw new IllegalArgumentException(bhavcopy + ": duplicate contract " + i.id());
            out.add(i);
        }
        out.sort(Instrument.CANONICAL);
        return out;
    }
}
