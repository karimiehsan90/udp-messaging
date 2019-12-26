import java.io.IOException;
import java.util.Objects;

public class Timer extends Thread {
    private Segment segment;
    private boolean killed;

    public Timer(Segment segment) {
        this.segment = segment;
        this.killed = false;
    }

    void kill() {
        this.killed = true;
    }

    @Override
    public void run() {
        while (!killed && !UDPClient.received[segment.getSequenceNumber()]) {
            try {
                UDPClient.sendUnreliable(segment.serialize());
                Thread.sleep(2000);
            } catch (IOException | InterruptedException e) {
                break;
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Timer timer = (Timer) o;
        return Objects.equals(segment, timer.segment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(segment);
    }
}
