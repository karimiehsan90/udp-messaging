import javax.xml.bind.DatatypeConverter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author V. Arun
 */

public class UDPClient {
    private static final int MAX_MSG_SIZE = 128;
    private static final int ALGORITHM_CHECKSUM_LENGTH = 64;
    private static final int SEQUENCE_NUMBER_LENGTH = 8;
    private static final int SEQUENCE_COUNT_LENGTH = 8;
    private static final int CHAR_LENGTH = 2;
    private static final int WHITE_SPACE_LENGTH = 10;
    private static final int IS_ACK_LENGTH = 2;
    private static final int DATA_MAX_LENGTH = (MAX_MSG_SIZE - ALGORITHM_CHECKSUM_LENGTH
            - SEQUENCE_COUNT_LENGTH - SEQUENCE_NUMBER_LENGTH - WHITE_SPACE_LENGTH - IS_ACK_LENGTH) / CHAR_LENGTH;
    private static final int MAX_WINDOW_SIZE = 16;
    private static final String SERVER = "127.0.1.1";
    private static final int PORT = 4353;
    private static DatagramSocket udpSocket = null;
    private static MessageDigest messageDigest;
    private static List<Segment> sendingSegments;
    private static Segment[] receivingSegments;
    private static int receivedCount = 0;
    private static String myUsername = null;
    private static Step step = Step.ECHO;
    private static String relayingTo;

    // Receives datagram and write to standard output
    public static class UDPReader extends Thread {
        public void run() {
            while (true) {
                byte[] msg = new byte[MAX_MSG_SIZE];
                DatagramPacket recvDgram = new DatagramPacket(msg, msg.length);
                try {
                    udpSocket.receive(recvDgram);
                    String packet = new String(recvDgram.getData(),
                            0, recvDgram.getLength(), StandardCharsets.UTF_8);
                    if (packet.startsWith("OK") || packet.startsWith("!OK")) {
                        if (packet.startsWith("OK Hello ")) {
                            myUsername = packet.substring("OK Hello ".length(), packet.length() - 1);
                        } else if (packet.startsWith("OK Relaying to")) {
                            String content = packet.substring("OK Relaying to".length());
                            relayingTo = content.split(" ")[0];
                            step = Step.RELAYING;
                        }
                        else if (packet.startsWith("OK Not relaying")) {
                            step = Step.ECHO;
                        }
                        System.out.print(packet);
                    } else {
                        try {
                            Segment segment = Segment.deserialize(packet);
                            if (!segment.isAck()) {
                                String exceptedChecksum = createChecksum(segment.getData());
                                if (exceptedChecksum.equals(segment.getChecksum())) {
                                    if (receivingSegments == null) {
                                        receivingSegments = new Segment[segment.getSegmentCount()];
                                    }
                                    if (receivingSegments[segment.getSequenceNumber()] == null) {
                                        receivingSegments[segment.getSequenceNumber()] = segment;
                                        receivedCount++;
                                        if (receivedCount == receivingSegments.length) {
                                            printData(receivingSegments);
                                            receivingSegments = null;
                                            receivedCount = 0;
                                        }
                                    }
                                    sendAck(true, segment.getSequenceNumber(), segment.getSender());
                                } else {
                                    sendAck(false, segment.getSequenceNumber(), segment.getSender());
                                }
                            }
                        } catch (SegmentationFaultException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (IOException e) {
                    System.out.println(e);
                    continue;
                }
            }
        }
    }

    private static void printData(Segment[] segments) {
        for (Segment segment : segments) {
            System.out.print(segment.getData());
        }
        System.out.println();
    }

    private static void sendAck(boolean ack, int sequenceNumber, String user) throws IOException {
        boolean wasRelaying = step == Step.RELAYING;
        String wasRelayingTo = relayingTo;
        if (wasRelaying) {
            sendUnreliable(".");
        }
        sendUnreliable("CONN " + user + "\n");
        String data = createChecksum(ack ? "1" : "0");
        Segment segment = new Segment(data, sequenceNumber, data, true, myUsername);
        sendUnreliable(segment.serialize());
        sendUnreliable("." + "\n");
        if (wasRelaying) {
            sendUnreliable("CONN " + wasRelayingTo + "\n");
        }
    }

    private static String createChecksum(String data) {
        byte[] result = messageDigest.digest(data.getBytes());
        return DatatypeConverter.printHexBinary(result);
    }

    // Reads from standard input and sends datagram
    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        String test = generateRandom(5000);
        messageDigest = MessageDigest.getInstance("MD5");
        udpSocket = new DatagramSocket();
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        (new UDPReader()).start();
        String input;
        while ((input = stdin.readLine()) != null) {
            if (step == Step.ECHO) {
                switch (input.split(" ")[0]) {
                    case "CONN":
                    case "NAME":
                    case "QUIT":
                    case "CHNL":
                    case "LIST":
                    case ".":
                        sendUnreliable(input + "\n");
                        break;
                    default:
                        sendReliable(input);
                }
            } else {
                sendReliable(input);
            }
        }
    }

    private static List<Segment> convertDataToSegments(String data, boolean isAck) {
        int start = 0;
        List<Segment> segments = new ArrayList<>();
        while (start < data.length()) {
            int end = Math.min(data.length(), DATA_MAX_LENGTH + start);
            String segmentData = data.substring(start, end);
            String checksum = createChecksum(segmentData);
            int seqNum = segments.size();
            segments.add(new Segment(checksum, seqNum, segmentData, isAck, myUsername));
            start = end;
        }
        segments.forEach(segment -> segment.setSegmentCount(segments.size()));
        return segments;
    }

    private static String generateRandom(int count) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < count; i++) {
            s.append((char) ((i % 26) + 'a'));
        }
        return s.toString();
    }

    private static void sendReliable(String input) throws IOException {
        sendingSegments = convertDataToSegments(input, false);
        for (Segment segment : sendingSegments) {
            sendUnreliable(segment.serialize());
        }
    }

    private static void sendUnreliable(String packet) throws IOException {
        DatagramPacket sendDgram = new DatagramPacket(packet.getBytes(),
                Math.min(packet.length(), MAX_MSG_SIZE),
                InetAddress.getByName(SERVER), PORT);
        udpSocket.send(sendDgram);
    }
}

