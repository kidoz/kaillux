package su.kidoz.kaillera.controller.v086;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import su.kidoz.kaillera.controller.v086.action.ActionRouter;
import su.kidoz.kaillera.controller.v086.action.FatalActionException;
import su.kidoz.kaillera.controller.v086.action.V086Action;
import su.kidoz.kaillera.controller.v086.action.V086GameEventHandler;
import su.kidoz.kaillera.controller.v086.action.V086ServerEventHandler;
import su.kidoz.kaillera.controller.v086.action.V086UserEventHandler;
import su.kidoz.kaillera.controller.v086.protocol.ClientACK;
import su.kidoz.kaillera.controller.v086.protocol.Quit;
import su.kidoz.kaillera.controller.v086.protocol.UserInformation;
import su.kidoz.kaillera.controller.v086.protocol.V086Bundle;
import su.kidoz.kaillera.controller.v086.protocol.V086BundleFormatException;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.event.DefaultEventDispatcher;
import su.kidoz.kaillera.model.event.EventDispatcher;
import su.kidoz.kaillera.model.event.GameEvent;
import su.kidoz.kaillera.model.event.KailleraEvent;
import su.kidoz.kaillera.model.event.KailleraEventListener;
import su.kidoz.kaillera.model.event.ServerEvent;
import su.kidoz.kaillera.model.event.UserEvent;
import su.kidoz.kaillera.model.validation.InputValidator;
import su.kidoz.net.BindException;
import su.kidoz.net.PrivateUDPServer;
import su.kidoz.util.ClientGameDataCache;
import su.kidoz.util.EmuLang;
import su.kidoz.util.EmuLinkerExecutor;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.GameDataCache;
import su.kidoz.util.ServerGameDataCache;
import su.kidoz.util.ThrottledLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles communication with a single V086 protocol client. Manages the UDP
 * connection, message buffering, and event dispatching for one user session.
 */
public final class V086ClientHandler extends PrivateUDPServer implements KailleraEventListener {
    private static final Logger log = LoggerFactory.getLogger(V086ClientHandler.class);
    private static final int MAX_BUNDLE_SIZE = 5;
    // A client chooses how many bad bundles and failing commands it sends, so
    // these events may not be written once per packet.
    private static final ThrottledLogger MALFORMED_BUNDLE_LOG = new ThrottledLogger(log,
            "malformed client bundle");
    private static final ThrottledLogger UNEXPECTED_BUNDLE_LOG = new ThrottledLogger(log,
            "unexpected client bundle exception");
    private static final ThrottledLogger ACTION_FAILURE_LOG = new ThrottledLogger(log,
            "client action failure");

    private final V086Controller controller;
    private final int bufferSize;
    private final EmuLinkerExecutor threadPool;
    private final PortAllocator portAllocator;
    private final KailleraServer server;
    private final ActionRouter actionRouter;
    private final EventDispatcher eventDispatcher;

    private volatile KailleraUser user;
    private int messageNumberCounter = 0;
    private int prevMessageNumber = -1;
    private int lastMessageNumber = -1;
    private GameDataCache clientCache = null;
    private GameDataCache serverCache = null;

    private LastMessageBuffer lastMessageBuffer = new LastMessageBuffer(MAX_BUNDLE_SIZE);
    private V086Message[] outMessages = new V086Message[MAX_BUNDLE_SIZE];

    private ByteBuffer inBuffer;
    private ByteBuffer outBuffer;

    private final Object inSynch = new Object();
    private final Object outSynch = new Object();

    private long testStart;
    private long lastMeasurement;
    private int measurementCount = 0;
    private int bestTime = Integer.MAX_VALUE;

    private int clientRetryCount = 0;
    private long lastResend = 0;

    /**
     * Creates a new V086ClientHandler for a client at the given address.
     *
     * @param remoteSocketAddress
     *            the client's socket address
     * @param controller
     *            the parent controller
     * @param bufferSize
     *            the buffer size for messages
     * @param threadPool
     *            the thread pool for async operations
     * @param portAllocator
     *            the port allocator for releasing ports
     * @param server
     *            the Kaillera server instance
     * @param actionRouter
     *            the action router for message handling
     */
    public V086ClientHandler(InetSocketAddress remoteSocketAddress, V086Controller controller,
            int bufferSize, EmuLinkerExecutor threadPool, PortAllocator portAllocator,
            KailleraServer server, ActionRouter actionRouter) {
        super(false, remoteSocketAddress.getAddress());

        if (controller == null) {
            throw new IllegalArgumentException("controller cannot be null");
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }

        this.controller = controller;
        this.bufferSize = bufferSize;
        this.threadPool = threadPool;
        this.portAllocator = portAllocator;
        this.server = server;
        this.actionRouter = actionRouter;

        // Create event dispatcher and register this handler as the listener
        this.eventDispatcher = new DefaultEventDispatcher();
        this.eventDispatcher.setListener(this);

        this.inBuffer = ByteBuffer.allocateDirect(bufferSize);
        this.outBuffer = ByteBuffer.allocateDirect(bufferSize);

        inBuffer.order(ByteOrder.LITTLE_ENDIAN);
        outBuffer.order(ByteOrder.LITTLE_ENDIAN);

        resetGameDataCache();
    }

    public String toString() {
        if (getBindPort() > 0)
            return "V086ClientHandler(" + getBindPort() + ")";
        else
            return "V086ClientHandler(unbound)";
    }

    public V086Controller getController() {
        return controller;
    }

    public EventDispatcher getEventDispatcher() {
        return eventDispatcher;
    }

    public KailleraUser getUser() {
        return user;
    }

    public synchronized int getNextMessageNumber() {
        if (messageNumberCounter > 0xFFFF)
            messageNumberCounter = 0;

        return messageNumberCounter++;
    }

    public int getPrevMessageNumber() {
        return prevMessageNumber;
    }

    public int getLastMessageNumber() {
        return lastMessageNumber;
    }

    public GameDataCache getClientGameDataCache() {
        return clientCache;
    }

    public GameDataCache getServerGameDataCache() {
        return serverCache;
    }

    public void resetGameDataCache() {
        clientCache = new ClientGameDataCache(256);
        serverCache = new ServerGameDataCache(256);
    }

    // The speed test keeps plain longs and ints. Its accessors synchronize on the
    // handler so that the values are consistent whichever thread reads them.
    public synchronized void startSpeedTest() {
        long now = System.currentTimeMillis();
        testStart = now;
        lastMeasurement = now;
        measurementCount = 0;
    }

    public synchronized void addSpeedMeasurement() {
        int et = (int) (System.currentTimeMillis() - lastMeasurement);
        if (et < bestTime)
            bestTime = et;
        measurementCount++;
        lastMeasurement = System.currentTimeMillis();
    }

    public synchronized int getSpeedMeasurementCount() {
        return measurementCount;
    }

    public synchronized int getBestNetworkSpeed() {
        return bestTime;
    }

    public synchronized int getAverageNetworkSpeed() {
        if (measurementCount <= 0) {
            return 0;
        }
        return (int) ((lastMeasurement - testStart) / measurementCount);
    }

    public void bind(int port, InetAddress address) throws BindException {
        super.bind(port, address);
    }

    public void start(KailleraUser user) {
        this.user = user;
        log.debug(toString() + " thread starting (ThreadPool:" + threadPool.getActiveCount() + "/"
                + threadPool.getPoolSize() + ")");
        threadPool.execute(this);

        log.debug(toString() + " thread started (ThreadPool:" + threadPool.getActiveCount() + "/"
                + threadPool.getPoolSize() + ")");
        controller.registerClientHandler(user.getID(), this);
    }

    @Override
    public void closeConnection() {
        closeTransport();
    }

    @Override
    public void stop() {
        closeTransport();

        KailleraUser localUser = user;
        if (localUser != null) {
            controller.unregisterClientHandler(localUser.getID());
            localUser.stop();
            user = null;
        }
    }

    /**
     * Closes the datagram channel and returns the port to the allocator. Does not
     * stop the user thread. Safe to call more than once.
     */
    private void closeTransport() {
        synchronized (this) {
            if (getStopFlag())
                return;

            int port = -1;
            if (isBound())
                port = getBindPort();
            log.debug(this.toString() + " Stopping!");
            super.stop();

            if (port > 0) {
                log.debug(toString() + " returning port " + port + " to available port queue: "
                        + (portAllocator.availableCount() + 1) + " available");
                portAllocator.release(port);
            }
        }
    }

    protected ByteBuffer getBuffer() {
        inBuffer.clear();
        return inBuffer;
    }

    protected void releaseBuffer(ByteBuffer buffer) {
        // Buffer is reused, no release needed
    }

    protected void handleReceived(ByteBuffer buffer) {
        KailleraUser localUser = user;
        if (localUser == null) {
            return;
        }

        V086Bundle inBundle = null;

        try {
            inBundle = V086Bundle.parse(buffer, lastMessageNumber);
        } catch (ParseException | V086BundleFormatException | MessageFormatException e) {
            logRejectedBundle(buffer, e);
            return;
        } catch (RuntimeException e) {
            // A malformed packet must not terminate this client's receive loop.
            logRejectedBundle(buffer, e);
            return;
        }

        if (inBundle.getNumMessages() == 0) {
            log.debug(toString() + " received bundle of " + inBundle.getNumMessages()
                    + " messages from " + localUser);
            clientRetryCount++;
            resend(clientRetryCount);
            return;
        } else {
            clientRetryCount = 0;
        }

        try {
            synchronized (inSynch) {
                // read the bundle from back to front to process the oldest messages first
                V086Message[] messages = inBundle.getMessages();
                for (int i = (inBundle.getNumMessages() - 1); i >= 0; i--) {
                    if (isNewerMessage(messages[i].getNumber(), lastMessageNumber)) {
                        prevMessageNumber = lastMessageNumber;
                        lastMessageNumber = messages[i].getNumber();

                        if (prevMessageNumber >= 0) {
                            int expected = (prevMessageNumber + 1) & 0xFFFF;
                            if (expected != lastMessageNumber) {
                                log.warn(localUser + " dropped a packet! (" + prevMessageNumber
                                        + " to " + lastMessageNumber + ")");
                                localUser.droppedPacket();
                            }
                        }

                        V086Action action = actionRouter.getAction(messages[i].getID());
                        if (action == null) {
                            // Only the server sends these message types. A client that
                            // sends one is confused or probing, not something to alert on.
                            log.debug("{}: no action defined for client message: {}", this,
                                    messages[i]);
                            continue;
                        }

                        if (!localUser.isLoggedIn() && !isLoginPhaseMessage(messages[i])) {
                            // Every other command assumes a logged-in user: some change
                            // state (PlayerDrop moves a connecting user to IDLE) and some
                            // trust fields that are null until UserInformation arrives.
                            log.debug("{}: dropped message {} from a client that is not logged in",
                                    this, messages[i].getID());
                            continue;
                        }

                        try {
                            action.performAction(messages[i], this);
                        } catch (RuntimeException e) {
                            // Keep the receive loop alive even if a single action fails.
                            ACTION_FAILURE_LOG.error("{}: action failed: {}", this,
                                    InputValidator.sanitizeForLog(String.valueOf(messages[i])), e);
                        }
                    }
                }
            }
        } catch (FatalActionException e) {
            log.warn(toString() + " fatal action, closing connection: " + e.getMessage());
            stop();
        }
    }

    /**
     * Records a bundle the client sent that could not be parsed.
     *
     * @param buffer
     *            the rejected bundle
     * @param cause
     *            why it was rejected
     */
    private void logRejectedBundle(ByteBuffer buffer, Exception cause) {
        buffer.rewind();
        if (cause instanceof RuntimeException) {
            // Unexpected exceptions carry a stack trace; keep it, but only for the
            // first few.
            UNEXPECTED_BUNDLE_LOG.error("{}: failed to parse message bundle ({} bytes)", this,
                    buffer.remaining(), cause);
        } else {
            MALFORMED_BUNDLE_LOG.warn("{}: rejected a malformed message bundle ({} bytes)", this,
                    buffer.remaining());
        }
        if (log.isDebugEnabled()) {
            log.debug("{}: rejected bundle contents: {}", this, EmuUtil.dumpBuffer(buffer));
        }
    }

    /**
     * True for the only messages a client may send before its login completes.
     *
     * @param message
     *            the inbound message
     * @return true if the message belongs to the login handshake or leaves the
     *         server
     */
    static boolean isLoginPhaseMessage(V086Message message) {
        byte id = message.getID();
        return id == UserInformation.ID || id == ClientACK.ID || id == Quit.ID;
    }

    public void actionPerformed(KailleraEvent event) {
        if (event instanceof GameEvent gameEvent) {
            V086GameEventHandler eventHandler = findGameEventHandler(event.getClass());
            if (eventHandler == null) {
                log.error(toString()
                        + " found no GameEventHandler registered to handle game event: " + event);
                return;
            }

            eventHandler.handleEvent(gameEvent, this);
        } else if (event instanceof ServerEvent serverEvent) {
            V086ServerEventHandler eventHandler = findServerEventHandler(event.getClass());
            if (eventHandler == null) {
                log.error(toString()
                        + " found no ServerEventHandler registered to handle server event: "
                        + event);
                return;
            }

            eventHandler.handleEvent(serverEvent, this);
        } else if (event instanceof UserEvent userEvent) {
            V086UserEventHandler eventHandler = findUserEventHandler(event.getClass());
            if (eventHandler == null) {
                log.error(toString()
                        + " found no UserEventHandler registered to handle user event: " + event);
                return;
            }

            eventHandler.handleEvent(userEvent, this);
        }
    }

    private V086GameEventHandler findGameEventHandler(Class<?> eventClass) {
        Class<?> current = eventClass;
        while (current != null) {
            V086GameEventHandler handler = actionRouter.getGameEventHandler(current);
            if (handler != null) {
                return handler;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private V086ServerEventHandler findServerEventHandler(Class<?> eventClass) {
        Class<?> current = eventClass;
        while (current != null) {
            V086ServerEventHandler handler = actionRouter.getServerEventHandler(current);
            if (handler != null) {
                return handler;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private V086UserEventHandler findUserEventHandler(Class<?> eventClass) {
        Class<?> current = eventClass;
        while (current != null) {
            V086UserEventHandler handler = actionRouter.getUserEventHandler(current);
            if (handler != null) {
                return handler;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public void resend(int timeoutCounter) {
        synchronized (outSynch) {
            if ((System.currentTimeMillis() - lastResend) > server.getMaxPing()) {
                int numToSend = (3 * timeoutCounter);
                if (numToSend > MAX_BUNDLE_SIZE)
                    numToSend = MAX_BUNDLE_SIZE;
                log.debug(this + ": resending last " + numToSend + " messages");
                send(null, numToSend);
                lastResend = System.currentTimeMillis();
            } else {
                log.debug("Skipping resend...");
            }
        }
    }

    public void send(V086Message outMessage) {
        send(outMessage, 3);
    }

    public void send(V086Message outMessage, int numToSend) {
        synchronized (outSynch) {
            if (outMessage != null)
                lastMessageBuffer.add(outMessage);

            numToSend = lastMessageBuffer.fill(outMessages, numToSend);
            V086Bundle outBundle = new V086Bundle(outMessages, numToSend);
            try {
                outBundle.writeTo(outBuffer);
                outBuffer.flip();
                super.send(outBuffer);
            } catch (RuntimeException e) {
                // A message that does not fit must cost that message only. Letting the
                // exception escape reaches UDPServer.run(), which stops this client.
                UNEXPECTED_BUNDLE_LOG.error(
                        "{}: failed to write a bundle of {} message(s), dropping them", this,
                        numToSend, e);
            } finally {
                // A failed write leaves a partially written buffer behind.
                outBuffer.clear();
            }
        }
    }

    /**
     * Compares 16-bit message numbers with wrap-around: true when {@code candidate}
     * is up to half the number space ahead of {@code last}.
     *
     * @param candidate
     *            the number of the arrived message
     * @param last
     *            the number of the newest message handled so far, or -1
     * @return true if the candidate is newer
     */
    static boolean isNewerMessage(int candidate, int last) {
        if (last < 0) {
            return true;
        }

        int diff = (candidate - last) & 0xFFFF;
        return diff > 0 && diff < 0x8000;
    }

    @Override
    protected void afterUnexpectedExit() {
        // The receive loop is gone, so this session cannot continue. Leave through the
        // normal quit path: that removes the user, drops them from any game they were
        // holding up, and tells the other players.
        KailleraUser localUser = user;
        if (localUser != null && localUser.isLoggedIn()) {
            try {
                localUser.quit(EmuLang.getString("KailleraServerImpl.ForcedQuitConnectionError"));
            } catch (Exception e) {
                log.error("Failed to quit {} after its receive loop stopped", localUser, e);
            }
        }
        stop();
    }
}
