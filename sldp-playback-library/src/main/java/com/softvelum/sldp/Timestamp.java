package com.softvelum.sldp;

public class Timestamp {
    private static final int TIMESCALE_MS = 1_000;
    private static final int TIMESCALE_US = 1_000_000;

    private final long dts;
    private final int offset;
    private final int timescale;

    public Timestamp() {
        this.dts = 0;
        this.offset = 0;
        this.timescale = TIMESCALE_MS;
    }

    public Timestamp(long pts) {
        this.dts = pts;
        this.offset = 0;
        this.timescale = TIMESCALE_MS;
    }

    public Timestamp(long dts, int offset, int timescale) {
        this.dts = dts;
        this.offset = offset;
        this.timescale = timescale;
    }

    private long getDts(int targetTimescale) {
        if (timescale == targetTimescale) {
            return dts;
        } else {
            return (long) ((dts / (double) timescale) * targetTimescale);
        }
    }

    public long getDtsMs() {
        return getDts(TIMESCALE_MS);
    }

    public long getDtsUs() {
        return getDts(TIMESCALE_US);
    }

    private long getPts(int targetTimescale) {
        if (timescale == targetTimescale) {
            return dts + offset;
        } else {
            return (long) (((dts + offset) / (double) timescale) * targetTimescale);
        }
    }

    public long getPtsMs() {
        return getPts(TIMESCALE_MS);
    }

    public long getPtsUs() {
        return getPts(TIMESCALE_US);
    }

    public long getPts() {
        return dts + offset;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || (this.getClass() != other.getClass())) {
            return false;
        }
        Timestamp guest = (Timestamp) other;
        return this.timescale == guest.timescale
                && this.dts == guest.dts
                && this.offset == guest.offset;
    }
}
