public class Segment {
    private String checksum;
    private int sequenceNumber;
    private String data;
    private int segmentCount;
    private boolean isAck;
    private static final int firstLineItemsCount = 4;

    public Segment(String checksum, int sequenceNumber, String data, boolean isAck) {
        this.checksum = checksum;
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.isAck = isAck;
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

    public String serialize() {
        return checksum + " " + sequenceNumber + " " + segmentCount + " " + (isAck ? "1" : "0") + "\n" + data;
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
            String data = lines[1];
            Segment segment = new Segment(checksum, seqNum, data, isAck);
            segment.setSegmentCount(seqCount);
            return segment;
        } catch (Exception ex) {
            throw new SegmentationFaultException("Could not parse packet", ex);
        }
    }
}
