public class SegmentationFaultException extends Exception {
    public SegmentationFaultException(String s, Throwable ex) {
        super(s, ex);
    }

    public SegmentationFaultException(String s) {
        super(s);
    }
}
