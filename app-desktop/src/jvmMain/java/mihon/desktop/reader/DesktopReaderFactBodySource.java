package mihon.desktop.reader;

import java.io.IOException;
import okio.Buffer;
import okio.BufferedSource;
import okio.RealBufferedSource;
import okio.Source;
import okio.Timeout;

/** JVM adapter for bounding actual response reads before Okio's refill buffer. */
public final class DesktopReaderFactBodySource {
    private DesktopReaderFactBodySource() {}

    public static Source unbuffered(BufferedSource body) throws IOException {
        if (!(body instanceof RealBufferedSource buffered)) {
            throw new IOException("Optional page facts require an unbuffered response read boundary");
        }
        return new Source() {
            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                if (byteCount == 0L) return 0L;
                if (buffered.getBuffer().size() > 0L) {
                    return buffered.getBuffer().read(sink, byteCount);
                }
                return buffered.source.read(sink, byteCount);
            }

            @Override
            public Timeout timeout() {
                return buffered.timeout();
            }

            @Override
            public void close() throws IOException {
                buffered.close();
            }
        };
    }
}
