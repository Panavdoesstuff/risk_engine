package riskengine.replayer.bhavcopy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import riskengine.ref.InstrumentType;
import riskengine.replayer.CsvSupport;

/**
 * Parses NSE F&O bhavcopy files, keeping index futures and options for the requested symbols.
 *
 * <p>Two formats are detected from the header:
 * <ul>
 *   <li><b>Legacy</b> ({@code foDDMMMYYYYbhav.csv}, used for June 2024): INSTRUMENT, SYMBOL, EXPIRY_DT,
 *       STRIKE_PR, OPTION_TYP, CLOSE, SETTLE_PR, OPEN_INT, TIMESTAMP. No lot size column, so a
 *       contract-lot file is required alongside it.</li>
 *   <li><b>UDiFF</b> ({@code BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv}): FinInstrmTp, TckrSymb, XpryDt,
 *       StrkPric, OptnTp, ClsPric, SttlmPric, OpnIntrst, TradDt, NewBrdLotQty.</li>
 * </ul>
 */
public final class BhavcopyParser {
    private static final DateTimeFormatter LEGACY_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH);

    private BhavcopyParser() {}

    public static List<BhavRow> parse(Path file, Set<String> symbols) {
        List<String> lines = CsvSupport.lines(file);
        if (lines.isEmpty()) throw new IllegalArgumentException(file + ": empty file");
        Map<String, Integer> idx = CsvSupport.index(CsvSupport.split(lines.get(0)));
        if (idx.containsKey("TCKRSYMB")) return parseUdiff(file, lines, idx, symbols);
        if (idx.containsKey("SYMBOL") && idx.containsKey("OPTION_TYP")) return parseLegacy(file, lines, idx, symbols);
        throw new IllegalArgumentException(file + ": unrecognised bhavcopy header: " + lines.get(0));
    }

    private static List<BhavRow> parseLegacy(Path f, List<String> lines, Map<String, Integer> idx, Set<String> symbols) {
        int cInst = CsvSupport.require(idx, "INSTRUMENT", f), cSym = CsvSupport.require(idx, "SYMBOL", f),
            cExp = CsvSupport.require(idx, "EXPIRY_DT", f), cStrike = CsvSupport.require(idx, "STRIKE_PR", f),
            cOpt = CsvSupport.require(idx, "OPTION_TYP", f), cClose = CsvSupport.require(idx, "CLOSE", f),
            cSettle = CsvSupport.require(idx, "SETTLE_PR", f), cOi = CsvSupport.require(idx, "OPEN_INT", f),
            cTs = CsvSupport.require(idx, "TIMESTAMP", f);
        List<BhavRow> out = new ArrayList<>();
        for (int n = 1; n < lines.size(); n++) {
            if (lines.get(n).isBlank()) continue;
            String[] c = CsvSupport.split(lines.get(n));
            String inst = c[cInst];
            if (!inst.equals("FUTIDX") && !inst.equals("OPTIDX")) continue;
            if (!symbols.contains(c[cSym])) continue;
            try {
                InstrumentType type = switch (c[cOpt]) {
                    case "XX" -> InstrumentType.FUT;
                    case "CE" -> InstrumentType.CE;
                    case "PE" -> InstrumentType.PE;
                    default -> throw new IllegalArgumentException("unknown OPTION_TYP " + c[cOpt]);
                };
                if ((type == InstrumentType.FUT) != inst.equals("FUTIDX")) {
                    throw new IllegalArgumentException("INSTRUMENT/OPTION_TYP mismatch");
                }
                out.add(new BhavRow(
                        LocalDate.parse(c[cTs], LEGACY_DATE), c[cSym], type, LocalDate.parse(c[cExp], LEGACY_DATE),
                        type == InstrumentType.FUT ? null : new BigDecimal(c[cStrike]),
                        new BigDecimal(c[cClose]), new BigDecimal(c[cSettle]), Long.parseLong(c[cOi]), null));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(f + ":" + (n + 1) + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static List<BhavRow> parseUdiff(Path f, List<String> lines, Map<String, Integer> idx, Set<String> symbols) {
        int cTp = CsvSupport.require(idx, "FinInstrmTp", f), cSym = CsvSupport.require(idx, "TckrSymb", f),
            cExp = CsvSupport.require(idx, "XpryDt", f), cStrike = CsvSupport.require(idx, "StrkPric", f),
            cOpt = CsvSupport.require(idx, "OptnTp", f), cClose = CsvSupport.require(idx, "ClsPric", f),
            cSettle = CsvSupport.require(idx, "SttlmPric", f), cOi = CsvSupport.require(idx, "OpnIntrst", f),
            cTd = CsvSupport.require(idx, "TradDt", f), cLot = CsvSupport.require(idx, "NewBrdLotQty", f);
        List<BhavRow> out = new ArrayList<>();
        for (int n = 1; n < lines.size(); n++) {
            if (lines.get(n).isBlank()) continue;
            String[] c = CsvSupport.split(lines.get(n));
            String tp = c[cTp];
            if (!tp.equals("IDF") && !tp.equals("IDO")) continue;
            if (!symbols.contains(c[cSym])) continue;
            try {
                InstrumentType type = tp.equals("IDF") ? InstrumentType.FUT : InstrumentType.valueOf(c[cOpt]);
                out.add(new BhavRow(
                        LocalDate.parse(c[cTd]), c[cSym], type, LocalDate.parse(c[cExp]),
                        type == InstrumentType.FUT ? null : new BigDecimal(c[cStrike]),
                        new BigDecimal(c[cClose]), new BigDecimal(c[cSettle]), Long.parseLong(c[cOi]),
                        Integer.valueOf(c[cLot])));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(f + ":" + (n + 1) + ": " + e.getMessage(), e);
            }
        }
        return out;
    }
}
