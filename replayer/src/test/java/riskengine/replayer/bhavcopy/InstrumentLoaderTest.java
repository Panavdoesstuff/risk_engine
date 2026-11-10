package riskengine.replayer.bhavcopy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import riskengine.ref.Instrument;
import riskengine.ref.InstrumentType;

/**
 * Fixtures mirror the NSE file layouts (legacy fo bhavcopy, UDiFF bhavcopy, fo_mktlots.csv).
 * Lot sizes here are illustrative; real values come from the exchange files for the replay date.
 */
class InstrumentLoaderTest {
    static final LocalDate D = LocalDate.of(2024, 6, 3);

    static final String LEGACY = """
            INSTRUMENT,SYMBOL,EXPIRY_DT,STRIKE_PR,OPTION_TYP,OPEN,HIGH,LOW,CLOSE,SETTLE_PR,CONTRACTS,VAL_INLAKH,OPEN_INT,CHG_IN_OI,TIMESTAMP,
            FUTIDX,NIFTY,27-Jun-2024,0,XX,23100,23400,23050,23337.9,23337.9,1000,1,100,1,03-JUN-2024,
            FUTIDX,NIFTY,25-Jul-2024,0,XX,23200,23500,23150,23450,23450,100,1,10,1,03-JUN-2024,
            OPTIDX,NIFTY,06-Jun-2024,23300.00,CE,200,250,150,180.5,180.5,10,1,500,1,03-JUN-2024,
            OPTIDX,NIFTY,06-Jun-2024,23300.00,PE,100,120,80,95.25,95.25,10,1,600,1,03-JUN-2024,
            OPTIDX,BANKNIFTY,05-Jun-2024,49500.00,CE,300,350,250,320,320,10,1,50,1,03-JUN-2024,
            FUTSTK,RELIANCE,27-Jun-2024,0,XX,2900,2950,2880,2930,2930,10,1,5,1,03-JUN-2024,
            OPTIDX,FINNIFTY,04-Jun-2024,22000.00,CE,1,1,1,1,1,1,1,1,1,03-JUN-2024,
            """;

    static final String MKTLOTS = """
            UNDERLYING                                        ,SYMBOL    ,JUN-24    ,JUL-24    ,AUG-24    ,
            Derivatives on Individual Securities              ,          ,          ,          ,          ,
            NIFTY 50                                          ,NIFTY     ,50        ,25        ,25        ,
            NIFTY BANK                                        ,BANKNIFTY ,15        ,15        ,15        ,
            """;

    static final String UDIFF = """
            TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,FininstrmActlXpryDt,StrkPric,OptnTp,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,LastPric,PrvsClsgPric,UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,SsnId,NewBrdLotQty,Rmks,Rsvd1,Rsvd2,Rsvd3,Rsvd4
            2024-06-03,2024-06-03,FO,NSE,IDF,1,,NIFTY,,2024-06-27,2024-06-27,,,NIFTY24JUNFUT,1,1,1,23337.90,1,1,23263.9,23337.90,100,1,1,1,1,F1,25,,,,,
            2024-06-03,2024-06-03,FO,NSE,IDO,2,,NIFTY,,2024-06-06,2024-06-06,23300,CE,NIFTY24606...,1,1,1,180.50,1,1,23263.9,180.50,500,1,1,1,1,F1,25,,,,,
            2024-06-03,2024-06-03,FO,NSE,STF,3,,RELIANCE,,2024-06-27,2024-06-27,,,X,1,1,1,1,1,1,1,1,1,1,1,1,1,F1,250,,,,,
            """;

    private static Path write(Path dir, String name, String body) throws IOException {
        return Files.writeString(dir.resolve(name), body);
    }

    @Test
    void legacyBhavcopyUsesLotSizeForEachContractsExpiryMonth(@TempDir Path dir) throws IOException {
        var lots = LotSizeTable.parseMktLots(write(dir, "lots.csv", MKTLOTS));
        List<Instrument> out = InstrumentLoader.load(write(dir, "fo.csv", LEGACY), lots, Set.of("NIFTY", "BANKNIFTY"), D);

        assertEquals(5, out.size(), "stock futures and unrequested symbols are dropped");
        Instrument junFut = find(out, "NIFTY-20240627-FUT"), julFut = find(out, "NIFTY-20240725-FUT");
        assertEquals(50, junFut.lotSize(), "June contract keeps the pre-revision lot size");
        assertEquals(25, julFut.lotSize(), "July contract gets the revised lot size");
        Instrument ce = find(out, "NIFTY-20240606-23300-CE");
        assertEquals(InstrumentType.CE, ce.type());
        assertEquals(0, ce.settle().compareTo(new java.math.BigDecimal("180.5")));
        assertEquals(15, find(out, "BANKNIFTY-20240605-49500-CE").lotSize());
    }

    @Test
    void legacyBhavcopyWithoutLotFileIsRejected(@TempDir Path dir) throws IOException {
        Path fo = write(dir, "fo.csv", LEGACY);
        var e = assertThrows(IllegalArgumentException.class, () -> InstrumentLoader.load(fo, null, Set.of("NIFTY"), D));
        assertTrue(e.getMessage().contains("fo_mktlots"));
    }

    @Test
    void wrongTradeDateIsRejected(@TempDir Path dir) throws IOException {
        var lots = LotSizeTable.parseMktLots(write(dir, "lots.csv", MKTLOTS));
        Path fo = write(dir, "fo.csv", LEGACY);
        assertThrows(IllegalArgumentException.class,
                () -> InstrumentLoader.load(fo, lots, Set.of("NIFTY"), D.plusDays(1)));
    }

    @Test
    void missingExpiryMonthInLotFileFailsLoudly(@TempDir Path dir) throws IOException {
        var lots = LotSizeTable.parseMktLots(write(dir, "lots.csv", MKTLOTS));
        var e = assertThrows(IllegalArgumentException.class, () -> lots.lotSize("NIFTY", LocalDate.of(2024, 12, 26)));
        assertTrue(e.getMessage().contains("2024-12"));
    }

    @Test
    void udiffBhavcopyCarriesItsOwnLotSizeAndIsCrossChecked(@TempDir Path dir) throws IOException {
        Path fo = write(dir, "udiff.csv", UDIFF);
        List<Instrument> out = InstrumentLoader.load(fo, null, Set.of("NIFTY"), D);
        assertEquals(List.of("NIFTY-20240606-23300-CE", "NIFTY-20240627-FUT"), out.stream().map(Instrument::id).sorted().toList());
        assertTrue(out.stream().allMatch(i -> i.lotSize() == 25));

        var lots = LotSizeTable.parseMktLots(write(dir, "lots.csv", MKTLOTS)); // says 50 for June
        var e = assertThrows(IllegalArgumentException.class, () -> InstrumentLoader.load(fo, lots, Set.of("NIFTY"), D));
        assertTrue(e.getMessage().contains("lot size mismatch"));
    }

    private static Instrument find(List<Instrument> l, String id) {
        return l.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }
}
