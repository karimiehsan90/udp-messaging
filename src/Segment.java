public class Segment {
    private String checksum;
    private int sequenceNumber;
    private String data;
    private int segmentCount;
    private boolean isAck;
    private static final int firstLineItemsCount = 5;
    private String sender;

    public Segment(String checksum, int sequenceNumber, String data, boolean isAck, String sender) {
        this.checksum = checksum;
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.isAck = isAck;
        this.sender = sender;
    }

    public boolean isAck() {
        return isAck;
    }

    public void setSegmentCount(int segmentCount) {
        this.segmentCount = segmentCount;
    }

    public String getChecksum() {
        return checksum;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public String getData() {
        return data;
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    public String getSender() {
        return sender;
    }

    public String serialize() {
        return checksum + " " + sequenceNumber + " " + segmentCount + " "
                + (isAck ? "1" : "0") + " " + sender + "\n" + data;
    }

    public static Segment deserialize(String packet) throws SegmentationFaultException {
        try {
            String[] lines = packet.split("\n");
            if (lines.length != 2) {
                throw new SegmentationFaultException("Could not parse packet");
            }
            String[] firstLineItems = lines[0].split(" ");
            if (firstLineItems.length != firstLineItemsCount) {
                throw new SegmentationFaultException("Could not parse packet");
            }
            String checksum = firstLineItems[0];
            int seqNum = Integer.parseInt(firstLineItems[1]);
            int seqCount = Integer.parseInt(firstLineItems[2]);
            boolean isAck = firstLineItems[3].equals("1");
            String sender = firstLineItems[4];
            String data = lines[1];
            Segment segment = new Segment(checksum, seqNum, data, isAck, sender);
            segment.setSegmentCount(seqCount);
            return segment;
        } catch (Exception ex) {
            throw new SegmentationFaultException("Could not parse packet", ex);
        }
    }
}
