import android.os.SystemClock;

/** Shell-only clock sampling, outside the reader measurement. Does not attach to an app. */
public final class ClockSample {
    public static void main(String[] args) {
        for (int i = 0; i < 16; i++) {
            long before = SystemClock.elapsedRealtimeNanos();
            long monotonic = System.nanoTime();
            long after = SystemClock.elapsedRealtimeNanos();
            System.out.println(before + "," + monotonic + "," + after);
        }
    }
}
