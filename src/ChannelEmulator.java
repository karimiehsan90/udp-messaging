//package PA2;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;


/* This ChannelEmulator class implements a UDP "chat server" that can
 * (1) echo messages, (2) accept control commands to register a user name
 * or change channel properties like loss, corruption probability, or delay,
 * and (3) relay messages to other users. It internally invokes a Timer
 * thread to delay messages and to periodically garbage collect state
 * left by users after a period of inactivity.
 *
 * Supports the following optional command-line arguments:
 * -PM privileged_user_name -P port_number - L loss_rate
 * -D delay_secs -R delay_dev_ratio -C corruption_rate
 */

public class ChannelEmulator {

    private static int PORT = 4353;
    private static final int MAX_MSG_SIZE = 2048;
    private static final long MAX_INACTIVE_TIME = 1800000; // milliseconds after which user state is garbage collected
    private static final int MAX_Q_SIZE = 8; // max number of outstanding segments per client
    private static final int MAX_TQ_SIZE = 10000; // max number of total outstanding segments
    private static final int MAX_MAP_SIZE = 1000000; // max number of client state entries

    // Default channel parameters
    private static double LOSS = 0.1; // loss rate
    private static double DELAY = 0.1; // delay in seconds
    private static double DELAY_DEV_RATIO = 1.0; // ratio of deviation to average delay
    private static double CORRUPTION = 0.01; // probability of corruption of exactly one byte in each 100 byte block
    private static boolean PRIVILEGED_MODE = false; // If true, only privileged_user can set CHNL parameters
    private static String privileged_user = null;

    private DatagramSocket udpsock = null;
    private int TQSize = 0; // total number of outstanding segments

    /* The two hashmaps below need to be ConcurrentHashMaps as they are modified by
     * the main thread as well as the garbage collector thread.
     */
    private ConcurrentHashMap<InetSocketAddress, ChannelInfo> sockToCinfo = null; // [IP,port] -> channel info
    private ConcurrentHashMap<String, InetSocketAddress> nameToSock = null; // name -> [IP,port]
    private GarbageCollector GC = null; // Garbage collects state left by ungraceful client exits
    private Timer timer = null; // Used by GarbageCollector and Delayer to schedule future events
    private Random random = null; // Used for implementing channel characteristics

    private int MAX_LOGMSG_SIZE = 64;
    private static Logger log = Logger.getLogger(ChannelEmulator.class.getName());

    /* Local structure to store info about user name or customized
     * channel info (e.g., loss, delay, peer relaying).
     */
    private class ChannelInfo {
        public double loss = LOSS;
        public double delay = DELAY;
        public double delay_dev_ratio = DELAY_DEV_RATIO; // ratio of deviation to average delay
        public double corruption = CORRUPTION;
        public String name = "DEFAULT"; // Name of user
        public String peer = null; // Name of peer to which relaying
        public long lastActive; // time in milliseconds, used for garbage collection upon inactivity
        private int qSize = 0; // number of buffered packets at server

        public synchronized int incrQSize() {
            return ++qSize;
        } // called by main and Delayer threads

        public synchronized int decrQSize() {
            return --qSize;
        } // called by main and Delayer threads

        public synchronized int getQSize() {
            return qSize;
        }

    }

    /* This timertask periodically cleans up state left by users who did
     * not cleanly send a QUIT command before exiting.
     */
    private class GarbageCollector extends TimerTask {
        public void run() {
            log.fine(printStats());
            for (InetSocketAddress isaddr : sockToCinfo.keySet()) {
                ChannelInfo cinfo = sockToCinfo.get(isaddr);
                if (cinfo != null && cinfo.lastActive < System.currentTimeMillis() - MAX_INACTIVE_TIME)
                    cleanup(isaddr);
            }
        }
    }

    /* This timertask is used to introduce channel delay */
    private class Delayer extends TimerTask {
        DatagramPacket dgram = null;
        ChannelInfo srcCinfo = null;

        Delayer(DatagramPacket d, ChannelInfo s) {
            dgram = d;
            srcCinfo = s;
        }

        public void run() {
            try {
                send(dgram);
                decrTQSize();
                if (srcCinfo != null) srcCinfo.decrQSize();
            } catch (IOException e) {
                // Do nothing coz it's just like a loss or really high delay
                log.warning("IOException while sending delayed datagram: " + e);
            }
        }
    }

    // Constructor processes args, opens a UDP socket, and allocates members.
    ChannelEmulator(String[] args) throws SocketException {
        processArgs(args);
        udpsock = new DatagramSocket(PORT);
        sockToCinfo = new ConcurrentHashMap<>();
        nameToSock = new ConcurrentHashMap<>();
        timer = new Timer();
        random = new Random();
        GC = new GarbageCollector();
    }

    /* Called by both the main thread and the GC thread. Needs to be
     * synchronized as, otherwise, interleaving between the GC calling
     * this method and the main thread calling processAsControlMessage's
     * setName method below to assign a name can leave inconsistent
     * entries in sockToCinfo and nameToSock.
     */
    private synchronized void cleanup(InetSocketAddress isaddr) {
        ChannelInfo cinfo = isaddr != null ? sockToCinfo.get(isaddr) : null;
        if (cinfo != null) {
            sockToCinfo.remove(isaddr);
            if (cinfo.name != null) nameToSock.remove(cinfo.name);
        }
    }

    /* Synchronization here as well as in the cleanup method ensures the invariant
     * that for any sockaddr, if sockToCinfo.get(sockaddr).name = name, then
     * nameToSock.get(name) = sockaddr, and vice versa.
     */
    private synchronized void setName(String name, InetSocketAddress isaddr) {
        assert (isaddr != null && name != null);
        ChannelInfo cinfo = sockToCinfo.get(isaddr);
        if (cinfo == null) cinfo = new ChannelInfo();
        else nameToSock.remove(cinfo.name);
        nameToSock.put(name, isaddr);
        cinfo.name = name;
        sockToCinfo.put(isaddr, cinfo);
    }

    private synchronized int incrTQSize() {
        return ++TQSize;
    }

    private synchronized int decrTQSize() {
        return --TQSize;
    }

    private synchronized int getTQSize() {
        return TQSize;
    }

    private synchronized void clearMaps() {
        sockToCinfo.clear();
        nameToSock.clear();
    }

    /* Start of methods to mangle packets being relayed by the channel. */
    private byte[] lose(byte[] msg, ChannelInfo cinfo) {
        if (msg == null) return null;
        double loss = (cinfo != null ? cinfo.loss : LOSS);
        if (random.nextDouble() < loss) msg = null;
        return msg;
    }

    // Randomly corrupts a byte in each 100 byte block with corruption probability
    private byte[] corrupt(byte[] msg, ChannelInfo cinfo) {
        if (msg == null) return null;
        double corruption = (cinfo != null ? cinfo.corruption : CORRUPTION);
        for (int i = 0; i < msg.length; i += 100) {
            if (random.nextDouble() < corruption) {
                int j = (int) (random.nextDouble() * (Math.min(i + 100, msg.length - 1)));
                msg[j] = (byte) (random.nextDouble() * 256);
            }
        }
        return msg;
    }

    /* Introduces src channel delay plus destination channel delay. Each
     * delay is computed as delay*(1 +/- variance) in milliseconds and must
     * be at least 0.
     */
    private void delay(DatagramPacket dgram, ChannelInfo srcCinfo) {
        if (dgram == null) return;
        Delayer task = new Delayer(dgram, srcCinfo);
        ChannelInfo dstCinfo = getChannelInfo(dgram);
        double src_delay = (srcCinfo != null ? srcCinfo.delay : DELAY);
        double src_delay_dev_ratio = (srcCinfo != null ? srcCinfo.delay : DELAY);
        double dst_delay = dstCinfo != null ? dstCinfo.delay : DELAY;
        double dst_delay_dev_ratio = (dstCinfo != null ? dstCinfo.delay_dev_ratio : DELAY_DEV_RATIO);
        timer.schedule(task,
                Math.max(0, (long) ((dst_delay * (1 + dst_delay_dev_ratio * (2 * random.nextDouble() - 1))) * 1000)) +
                        Math.max(0, (long) ((src_delay * (1 + src_delay_dev_ratio * (2 * random.nextDouble() - 1))) * 1000)));
    }

    /* Apply loss, corruption, and delay each for the src channel as well as
     * the destination channel respectively.
     */
    private void mangle(DatagramPacket dgram, InetSocketAddress srcAddr) throws IOException {
        if (dgram == null || srcAddr == null) return; // dgram can be null if getRelayMessage returns null
        byte[] msg = Arrays.copyOfRange(dgram.getData(), 0, dgram.getLength());
        ChannelInfo srcCinfo = sockToCinfo.get(srcAddr);
        ChannelInfo dstCinfo = getChannelInfo(dgram);
        msg = lose(msg, srcCinfo);
        msg = lose(msg, dstCinfo);
        msg = corrupt(msg, srcCinfo);
        msg = corrupt(msg, dstCinfo);
        if (msg != null && srcCinfo.getQSize() < MAX_Q_SIZE && getTQSize() < MAX_TQ_SIZE) {
            srcCinfo.incrQSize();
            this.incrTQSize();
            dgram.setData(msg);
            delay(dgram, srcCinfo);
        } else log.info("Dropping message from " + getNameString(srcAddr) +
                " to " + getNameString(dgram) + " : " + truncate(dgram));
    }
    /* End of methods to mangle packets being relayed by the channel. */

    /* This method attempts to process a datagram as a QUIT, ., NAME,
     * CONN, LIST, or CHNL control message.
     */
    private String processAsControlMessage(DatagramPacket dgram) {
        String response = null;
        String msg = (new String(dgram.getData())).trim();
        String[] parts = msg.split("\\s");
        String cmd = parts[0];
        boolean room = true;

        InetSocketAddress isaddr = (InetSocketAddress) (dgram.getSocketAddress());
        if (isaddr == null) return null;
        ChannelInfo cinfo = sockToCinfo.get(isaddr);

        // if in relaying mode, check for stopping relaying
        if (isRelaying(dgram)) {
            if (cmd.equals(".") && parts.length == 1) {
                if (cinfo != null) cinfo.peer = null;
                response = "OK Not relaying";
            }
        }
        // else not relaying, try processing as control message
        // first check for QUIT and happily clear away any state
        else if (cmd.equals("QUIT") && parts.length == 1) {
            response = "OK Bye";
            cleanup(isaddr);
        } else if (cmd.equals("NAME") && parts.length == 2 && (room = haveRoom(cinfo))) {
            if (!nameToSock.containsKey(parts[1])) {
                setName(parts[1], isaddr);
                response = "OK Hello " + parts[1];
            }
            else {
                response = "!OK Already taken";
            }
        } else if (cmd.equals("CONN") && parts.length > 1 && (room = haveRoom(cinfo))) {
            if (cinfo == null) cinfo = new ChannelInfo();
            cinfo.peer = (msg.split("\\s", 2))[1];
            InetSocketAddress peerSock = getPeerSock(cinfo.peer);
            /* If no sockaddr stored for peer but can parse sockaddr
             * in peer, use the parsed sockaddr as peer instead. That
             * is, peer is either a name that is mapped to a sockaddr
             * or it is a sockaddr itself.
             */
            if (nameToSock.get(cinfo.peer) == null && peerSock != null)
                cinfo.peer = peerSock.toString();
            sockToCinfo.put(isaddr, cinfo);
            response = "OK Relaying to " + cinfo.peer +
                    ((peerSock == null || sockToCinfo.get(peerSock) == null) ?
                            " who is probably offline" :
                            (!cinfo.peer.equals(peerSock.toString()) ?
                                    " at " + peerSock : ""));
        }
        /* The privileged_mode allows only the privileged_user to set channel
         * parameters and sets it for all users.
         */
        else if (cmd.equals("CHNL") && parts.length >= 3 && allowCHNL(dgram) && (room = haveRoom(cinfo))) {
            if (cinfo == null) cinfo = new ChannelInfo();
            boolean parsed = true;
            for (int i = 1; i < parts.length - 1 && parsed; i += 2) {
                try {
                    /* No checks for valid values. Probabilities below 0 or above 1 will be
                     * respectively treated as 0 and 1, and negative delays will be treated as
                     * 0 as per the implementation of lose, corrupt, and delay methods above.
                     */
                    if (parts[i].equals("LOSS")) {
                        cinfo.loss = Double.valueOf(parts[i + 1]);
                        log.fine("Setting LOSS to " + cinfo.loss);
                    } else if (parts[i].equals("DELAY")) cinfo.delay = Double.valueOf(parts[i + 1]);
                    else if (parts[i].equals("DELAY_DEV_RATIO")) cinfo.delay_dev_ratio = Double.valueOf(parts[i + 1]);
                    else if (parts[i].equals("CORRUPTION")) cinfo.corruption = Double.valueOf(parts[i + 1]);
                    else parsed = false;
                } catch (NumberFormatException nfe) {
                    parsed = false;
                }
            }
            if (parsed) {
                response = "OK " + msg;
                sockToCinfo.put(isaddr, cinfo);
            }
            if (PRIVILEGED_MODE) { // will behave fine even with bad CHNL commands
                LOSS = cinfo.loss;
                CORRUPTION = cinfo.corruption;
                DELAY = cinfo.delay;
                DELAY_DEV_RATIO = cinfo.delay_dev_ratio;
            }
        } else if (cmd.equals("CHNL") && parts.length == 2 && parts[1].equals("PARAMS") &&
                allowCHNL(dgram) && (room = haveRoom(cinfo))) {
            response = "OK " + "CHNL " + printParams(cinfo);

        } else if (cmd.equals("LIST") && parts.length == 1 && (room = haveRoom(cinfo))) {
            response = "OK LIST = ";
            for (String name : nameToSock.keySet()) {
                response += (name + nameToSock.get(name) + " ");
            }
        }
        if (!room) response = "!OK: chat server too crowded, try again later";
        return (response != null ? response + "\n" : null);
    }

    private boolean haveRoom(ChannelInfo cinfo) {
        if (cinfo != null || sockToCinfo.size() <= MAX_MAP_SIZE) return true;
        return false;
    }

    /* If privileged mode is enabled, CHNL commands are accepted
     * only from the privileged user.
     */
    private boolean allowCHNL(DatagramPacket dgram) {
        if (!PRIVILEGED_MODE) return true;
        ChannelInfo cinfo = getChannelInfo(dgram);
        if (cinfo != null && cinfo.name.equals(privileged_user))
            return true;
        return false;
    }

    /* Start of utility methods*/
    // non-null sender in name/IP:port format
    private String getNameString(DatagramPacket dgram) {
        ChannelInfo cinfo = getChannelInfo(dgram);
        String name = (cinfo != null ? cinfo.name : "");
        return (name != null ? name : "") + dgram.getSocketAddress();
    }

    private String getNameString(InetSocketAddress isaddr) {
        ChannelInfo cinfo = isaddr != null ? sockToCinfo.get(isaddr) : null;
        String name = (cinfo != null ? cinfo.name : "");
        return (name != null ? name : "") + nameToSock.get(name);
    }

    // non-null peer in name/IP:port format
    private String getPeerString(DatagramPacket dgram) {
        ChannelInfo cinfo = getChannelInfo(dgram);
        String peer = (cinfo != null ? (cinfo.peer != null ? cinfo.peer : "") : "");
        InetSocketAddress peerSock = getPeerSock(peer);
        return peer + (peerSock != null ? peerSock : "");
    }

    private ChannelInfo getChannelInfo(DatagramPacket dgram) {
        InetSocketAddress isaddr = (InetSocketAddress) (dgram.getSocketAddress());
        ChannelInfo cinfo = isaddr != null ? sockToCinfo.get(isaddr) : null;
        return cinfo;
    }

    private boolean isRelaying(DatagramPacket dgram) {
        ChannelInfo cinfo = getChannelInfo(dgram);
        if (cinfo != null) return cinfo.peer != null;
        return false;
    }

    /* Tries to recognize name by keying into nameToSock or by
     * decomposing name into IP:port pair. Accepts name or "IP:port"
     * or "IP port" or "name/IP:port" or "name IP port". The name
     * as in the latter two will be ignored when IP,port is parsed.
     */
    private InetSocketAddress getPeerSock(String name) {
        InetSocketAddress isaddr = null;
        if (name != null && (isaddr = nameToSock.get(name)) == null) {
            name = name.replace('/', ' ').replace(':', ' ').trim();
            String[] parts = name.split("\\s");
            if (parts.length > 1) {
                try {
                    InetAddress IP = InetAddress.getByName(parts[parts.length - 2]);
                    int port = Integer.valueOf(parts[parts.length - 1]);
                    isaddr = new InetSocketAddress(IP, port);
                } catch (Exception e) {
                    // do nothing as null will (and should) be returned anyway
                    log.finer("Incorrectly formatter peer name: " + name);
                }
            }
        }
        return isaddr;
    }

    // Affix peer IP:port to received datagram to make relayable datagram
    private DatagramPacket getRelayMessage(DatagramPacket dgram) {
        InetSocketAddress sndsock = (InetSocketAddress) (dgram.getSocketAddress());
        ChannelInfo cinfo = sndsock != null ? sockToCinfo.get(sndsock) : null;
        if (cinfo != null && cinfo.peer != null) {
            InetSocketAddress rcvsock = getPeerSock(cinfo.peer);
            if (rcvsock != null) {
                dgram.setAddress(rcvsock.getAddress());
                dgram.setPort(rcvsock.getPort());
            } else dgram = null;
        }
        return dgram;
    }

    private String truncate(DatagramPacket dgram) {
        return truncate(new String(dgram.getData(), 0, dgram.getLength()));
    }

    private String truncate(String msg) {
        int length = Math.min(MAX_LOGMSG_SIZE, msg.length());
        return msg.substring(0, length) + (length < msg.length() ? "...\n" : "");
    }
    /* End of utility methods */


    // Send
    private void send(DatagramPacket dgram) throws IOException {
        assert (dgram != null);
        udpsock.send(dgram);
    }

    // Receive
    private DatagramPacket readMessage() throws IOException {
        byte[] msg = new byte[MAX_MSG_SIZE];
        DatagramPacket dgram = new DatagramPacket(msg, msg.length);
        udpsock.receive(dgram);
        return dgram;
    }

    /* Upon IOException, try closing and reopening socket a few times
     * with increasing timeouts. Also try refreshing the hashmaps so the
     * old ones can be garbage collected in case they have grown too big.
     */
    private void tryRecover() throws IOException {
        long retry = 5000; //milliseconds
        for (int i = 0; i < 5; i++) {
            if (!udpsock.isClosed()) udpsock.close();
            clearMaps();
            timer.cancel();
            try {
                Thread.sleep((retry *= 2));
            } catch (InterruptedException ie) {
                log.severe("Sleep interrupt during recovery: " + ie);
            }
            try {
                udpsock = new DatagramSocket(PORT);
            } catch (IOException ioe) {
                log.severe("Exception # " + i + ": " + ioe);
                continue;
            } catch (OutOfMemoryError ome) {
                log.severe("Exception # " + i + ": " + ome);
                continue;
            }
        }
    }

    // Command-line argument processing
    private void processArgs(String[] args) {
        for (int i = 0; i + 1 < args.length; i += 2) {
            args[i] = args[i].toUpperCase();
            if (args[i].equals("-PRIVILEGED_MODE") || args[i].equals("-PM")) {
                PRIVILEGED_MODE = true;
                privileged_user = args[i + 1];
            }
            try {
                if (args[i].equals("-PORT") || args[i].equals("-P")) {
                    PORT = Integer.valueOf(args[i + 1]);
                } else {
                    double val = Double.valueOf(args[i + 1]);
                    if (args[i].equals("-LOSS") || args[i].equals("-L")) {
                        LOSS = val;
                    } else if (args[i].equals("-DELAY") || args[i].equals("-D")) {
                        DELAY = val;
                    } else if (args[i].equals("-DELAY_DEV_RATIO") || args[i].equals("-R")) {
                        DELAY_DEV_RATIO = val;
                    } else if (args[i].equals("-CORRUPTION") || args[i].equals("-C")) {
                        LOSS = val;
                    }
                }
            } catch (NumberFormatException nfe) {
                log.warning("Bad " + args[i] + " input, ignoring or using default value");
                continue;
            }
        }
    }

    // Printing command-line configurable parameters
    private String printParams(ChannelInfo cinfo) {
        return " [ LOSS " + (cinfo != null ? cinfo.loss : LOSS) +
                "  DELAY " + (cinfo != null ? cinfo.delay : DELAY) +
                "  DELAY_DEV_RATIO " + (cinfo != null ? cinfo.delay_dev_ratio : DELAY_DEV_RATIO) +
                "  CORRUPTION " + (cinfo != null ? cinfo.corruption : CORRUPTION) +
                (PRIVILEGED_MODE ? "  privileged_user " + privileged_user : "") + " ]";
    }

    // Printing overall stats
    private String printStats() {
        return "ChannelEmulator: |sockToCinfo| = " + sockToCinfo.size() + " , |nameToSock| = " + nameToSock.size() +
                " , TQSize = " + TQSize + "\n";
    }

    /* This method first tries to process a packet as a control message, then
     * checks for relaying state, and otherwise simply echoes back to the sender.
     */
    public void run() {
        int num_excepts = 0;
        timer.schedule(GC, 0, MAX_INACTIVE_TIME);
        while (true) {
            try {
                DatagramPacket dgram = readMessage();
                String sender = getNameString(dgram);
                ChannelInfo cinfo = getChannelInfo(dgram);
                if (cinfo != null) cinfo.lastActive = System.currentTimeMillis();

                /* Only correctly formatted control messages elicit a response from
                 * the server beginning with "OK". All other messages are either
                 * relayed or echoed back. In particular, there is no special "error"
                 * message returned for incorrectly formatted control commands.
                 */
                // Try processing as control message first.
                String response = null;
                if ((response = processAsControlMessage(dgram)) != null) {
                    log.fine("Control message from/to " + sender + " : " +
                            truncate(dgram).trim() + " -> " + truncate(response));
                    dgram.setData(response.getBytes());
                    send(dgram);
                }
                // else check for relaying
                else if (isRelaying(dgram)) {
                    log.fine("Relay message from " + sender + " to " + getPeerString(dgram) + " : " + truncate(dgram));
                    InetSocketAddress isaddr = (InetSocketAddress) dgram.getSocketAddress();
                    mangle(getRelayMessage(dgram), isaddr);
                }
                // else simply echo back to sender
                else {
                    send(dgram); // simply echo datagram by default
                    log.fine("Echo message from/to " + sender + ": " + truncate(dgram));
                }
            } catch (Exception e) {
                log.warning("Exception #" + num_excepts + ": " + e);
                try {
                    if (++num_excepts > 3) tryRecover();
                } // A hail mary pass before giving up
                catch (Exception efatal) {
                    log.severe("Unable to recover from Exception, giving up: " + efatal);
                }
            }
        }
    }

    /* Sets logging level and invokes run() */
    public static void main(String[] args) throws SocketException, IOException {
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.FINEST);
        log.addHandler(ch);
        log.setLevel(Level.FINEST);
        log.setUseParentHandlers(false);

        ChannelEmulator chem = new ChannelEmulator(args);
        log.info("Starting chat server at " + InetAddress.getLocalHost() + ":" + PORT + chem.printParams(null) + "\n");
        chem.run();
    }
}

