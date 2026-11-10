package riskengine.margin;

/** Utilisation levels (fractions, 1.00 = 100%) and the confirmation rule. */
public record Thresholds(double warning, double marginCall, double squareOff, int confirmTicks) {
    public Thresholds {
        if (!(0 < warning && warning < marginCall && marginCall < squareOff)) {
            throw new IllegalArgumentException("need 0 < warning < marginCall < squareOff");
        }
        if (confirmTicks < 1) throw new IllegalArgumentException("confirmTicks must be >= 1");
    }
}
