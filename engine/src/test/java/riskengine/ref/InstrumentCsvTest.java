package riskengine.ref;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstrumentCsvTest {
    private static final LocalDate EXP = LocalDate.of(2024, 6, 6);

    @Test
    void idsAreCanonical() {
        var call = Instrument.of("NIFTY", InstrumentType.CE, EXP, new BigDecimal("22500.00"), 25,
                new BigDecimal("150.5"), new BigDecimal("150.5"), 10);
        var fut = Instrument.of("NIFTY", InstrumentType.FUT, LocalDate.of(2024, 6, 27), null, 25,
                BigDecimal.ONE, BigDecimal.ONE, 1);
        assertEquals("NIFTY-20240606-22500-CE", call.id());
        assertEquals("NIFTY-20240627-FUT", fut.id());
    }

    @Test
    void rejectsStrikeOnFutureAndMissingStrikeOnOption() {
        assertThrows(IllegalArgumentException.class, () -> Instrument.of("NIFTY", InstrumentType.FUT, EXP,
                BigDecimal.TEN, 25, BigDecimal.ONE, BigDecimal.ONE, 0));
        assertThrows(IllegalArgumentException.class, () -> Instrument.of("NIFTY", InstrumentType.PE, EXP,
                null, 25, BigDecimal.ONE, BigDecimal.ONE, 0));
    }

    @Test
    void roundTripsAndSortsCanonically(@TempDir Path dir) {
        var pe = Instrument.of("NIFTY", InstrumentType.PE, EXP, new BigDecimal("22450"), 25,
                new BigDecimal("80"), new BigDecimal("81.25"), 5);
        var ce = Instrument.of("NIFTY", InstrumentType.CE, EXP, new BigDecimal("22550.5"), 25,
                new BigDecimal("90"), new BigDecimal("91"), 6);
        var bank = Instrument.of("BANKNIFTY", InstrumentType.FUT, EXP, null, 15,
                new BigDecimal("49000"), new BigDecimal("49010.4"), 7);
        Path f = dir.resolve("i.csv");
        InstrumentCsv.write(f, List.of(pe, ce, bank));
        List<Instrument> back = InstrumentCsv.read(f);
        assertEquals(List.of(bank.id(), ce.id(), pe.id()), back.stream().map(Instrument::id).toList());
        assertEquals(0, new BigDecimal("81.25").compareTo(back.get(2).settle()));
    }
}
