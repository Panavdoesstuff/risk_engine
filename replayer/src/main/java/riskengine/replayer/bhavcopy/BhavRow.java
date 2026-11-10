package riskengine.replayer.bhavcopy;

import java.math.BigDecimal;
import java.time.LocalDate;
import riskengine.ref.InstrumentType;

/**
 * One index-derivative row from an NSE F&O bhavcopy.
 *
 * @param lotSize present in UDiFF files (NewBrdLotQty); null in the legacy format
 */
public record BhavRow(
        LocalDate tradeDate,
        String symbol,
        InstrumentType type,
        LocalDate expiry,
        BigDecimal strike,
        BigDecimal close,
        BigDecimal settle,
        long openInterest,
        Integer lotSize) {}
