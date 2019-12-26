import java.io.IOException;

public class Timer extends Thread {
    private Segment segment;

    public Timer(Segment segment) {
        this.segment = segment;
    }

    @Override
    public void run() {
        while (!UDPClient.received[segment.getSequenceNumber()]) {
            try {
                UDPClient.sendUnreliable(segment.serialize());
                Thread.sleep(2000);
            } catch (IOException | InterruptedException e) {
                break;
            }
        }
    }
}
