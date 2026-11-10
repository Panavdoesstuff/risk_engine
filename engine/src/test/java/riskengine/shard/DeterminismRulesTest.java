package riskengine.shard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces the "banned in shard code" list by reading the source. Anything a shard executes (its own
 * package plus pricing, margin and events) must give the same result on every run and every machine.
 */
class DeterminismRulesTest {
    static final List<String> PACKAGES = List.of("shard", "margin", "pricing", "events");

    static final Map<String, Pattern> BANNED = new TreeMap<>(Map.of(
            "wall clock", Pattern.compile("System\\.(currentTimeMillis|nanoTime)|\\b(Instant|LocalDate|LocalDateTime|OffsetDateTime|ZonedDateTime)\\.now\\b|\\bClock\\."),
            "random numbers", Pattern.compile("\\b(Random|ThreadLocalRandom|SplittableRandom|SecureRandom)\\b|Math\\.random|UUID\\.randomUUID"),
            "hash-ordered collections", Pattern.compile("\\b(HashMap|HashSet|LinkedHashMap|LinkedHashSet|IdentityHashMap|ConcurrentHashMap|Hashtable)\\b|Map\\.of\\(|Set\\.of\\("),
            "platform-dependent maths (use StrictMath)", Pattern.compile("\\bMath\\.(exp|log|log10|log1p|expm1|pow|sin|cos|tan|cbrt|sinh|cosh|tanh|atan|atan2|hypot)\\b"),
            "parallel streams", Pattern.compile("parallelStream|\\.parallel\\(\\)")));

    @Test
    void shardCodeUsesNoClockRandomnessOrUnorderedCollections() throws IOException {
        Path root = Path.of("src/main/java/riskengine");
        assertTrue(Files.isDirectory(root), "run from the engine module: " + root.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        int files = 0;
        for (String pkg : PACKAGES) {
            try (Stream<Path> walk = Files.walk(root.resolve(pkg))) {
                for (Path f : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    files++;
                    String[] lines = stripComments(Files.readString(f)).split("\n", -1);
                    for (int n = 0; n < lines.length; n++) {
                        for (var rule : BANNED.entrySet()) {
                            if (rule.getValue().matcher(lines[n]).find()) {
                                violations.add(root.relativize(f) + ":" + (n + 1) + " " + rule.getKey() + ": " + lines[n].trim());
                            }
                        }
                    }
                }
            }
        }
        assertTrue(files > 20, "scanned only " + files + " files");
        assertTrue(violations.isEmpty(), "banned in shard code:\n" + String.join("\n", violations));
    }

    @Test
    void theScannerActuallyCatchesThings() {
        String bad = """
                long t = System.currentTimeMillis();
                var m = new HashMap<String, Long>();
                double x = Math.exp(1) + new Random().nextDouble();
                // HashMap in a comment is fine
                double ok = StrictMath.exp(1) + Math.max(1, 2);
                """;
        String[] lines = stripComments(bad).split("\n", -1);
        int hits = 0;
        for (String line : lines) {
            for (Pattern p : BANNED.values()) if (p.matcher(line).find()) hits++;
        }
        assertEquals(4, hits, "clock, hash map, Math.exp and Random; nothing on the comment or StrictMath lines");
    }

    /** Removes comments but keeps line breaks, so reported line numbers stay right. */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (src.startsWith("//", i)) {
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? src.length() : end + 2;
                for (int k = i; k < end; k++) if (src.charAt(k) == '\n') out.append('\n');
                i = end;
            } else {
                out.append(src.charAt(i++));
            }
        }
        return out.toString();
    }
}
