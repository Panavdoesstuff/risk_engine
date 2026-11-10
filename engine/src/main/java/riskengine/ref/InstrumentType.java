package riskengine.ref;

/** Declaration order is the canonical sort order (futures, calls, puts). */
public enum InstrumentType {
    FUT, CE, PE;

    public boolean isOption() {
        return this != FUT;
    }
}
