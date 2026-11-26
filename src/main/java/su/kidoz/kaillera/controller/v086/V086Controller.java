package su.kidoz.kaillera.controller.v086;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import su.kidoz.config.ControllersConfig;
import su.kidoz.config.ServerConfig;
import su.kidoz.kaillera.controller.KailleraServerController;
import su.kidoz.kaillera.controller.v086.action.ActionRouter;
import su.kidoz.kaillera.controller.v086.action.V086Action;
import su.kidoz.kaillera.controller.v086.action.V086GameEventHandler;
import su.kidoz.kaillera.controller.v086.action.V086ServerEventHandler;
import su.kidoz.kaillera.controller.v086.action.V086UserEventHandler;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.exception.NewConnectionException;
import su.kidoz.kaillera.model.exception.ServerFullException;
import su.kidoz.net.BindException;
import su.kidoz.util.EmuLinkerExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class V086Controller implements KailleraServerController, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(V086Controller.class);

    private final int bufferSize;
    private volatile boolean isRunning = false;

    private final EmuLinkerExecutor threadPool;
    private final KailleraServer server;
    private final String[] clientTypes;
    private final Map<Integer, V086ClientHandler> clientHandlers = new ConcurrentHashMap<>();

    private final int portRangeStart;
    private final int extraPorts;
    private final PortAllocator portAllocator;
    private final List<InetAddress> bindAddresses;

    private final ActionRouter actionRouter;

    public V086Controller(KailleraServer server, EmuLinkerExecutor threadPool,
            ControllersConfig controllersConfig, ServerConfig serverConfig,
            ActionRouter actionRouter) {
        this.threadPool = threadPool;
        this.server = server;
        this.actionRouter = actionRouter;

        ControllersConfig.V086 v086Config = controllersConfig.getV086();
        this.bufferSize = v086Config.getBufferSize();
        this.clientTypes = v086Config.getClientTypes().toArray(new String[0]);
        this.bindAddresses = controllersConfig.getParsedBindAddresses();

        this.portRangeStart = v086Config.getPortRangeStart();
        this.extraPorts = v086Config.getExtraPorts();
        int maxUsers = serverConfig.getMaxUsers();
        int maxConnecting = serverConfig.getMaxConnecting();
        // A zero max-users means no logged-in policy cap, but every session still
        // holds one socket. Size the pool explicitly or derive it from the real
        // admission limits instead of silently capping at extra-ports.
        int baseSessions = maxUsers > 0 ? maxUsers : 1_000;
        int portCount = v086Config.getMaxSessions() > 0
                ? v086Config.getMaxSessions()
                : baseSessions + maxConnecting + extraPorts + 1;
        if (maxUsers == 0) {
            log.warn(
                    "server.max-users=0: concurrent sessions are bounded by the {} available "
                            + "data ports. Set controllers.v086.max-sessions to change the pool.",
                    portCount);
        }
        if (portCount > 65535 - portRangeStart + 1) {
            throw new IllegalStateException("Data port range " + portRangeStart + " + " + portCount
                    + " exceeds 65535; lower controllers.v086.max-sessions");
        }
        this.portAllocator = new PortAllocatorImpl(portRangeStart, portCount);
        int maxPort = portRangeStart + portCount - 1;

        log.warn(
                "Listening on UDP ports: {} to {} (addresses: {}). "
                        + "Make sure these ports are open in your firewall!",
                portRangeStart, maxPort,
                bindAddresses.stream().map(InetAddress::getHostAddress).toList());
    }

    public String getVersion() {
        return "v086";
    }

    public String[] getClientTypes() {
        return clientTypes.clone();
    }

    public KailleraServer getServer() {
        return server;
    }

    public int getNumClients() {
        return clientHandlers.size();
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public Map<Class<?>, V086ServerEventHandler> getServerEventHandlers() {
        return actionRouter.getServerEventHandlers();
    }

    public Map<Class<?>, V086GameEventHandler> getGameEventHandlers() {
        return actionRouter.getGameEventHandlers();
    }

    public Map<Class<?>, V086UserEventHandler> getUserEventHandlers() {
        return actionRouter.getUserEventHandlers();
    }

    public V086Action[] getActions() {
        return actionRouter.getActions();
    }

    public ActionRouter getActionRouter() {
        return actionRouter;
    }

    public Map<Integer, V086ClientHandler> getClientHandlers() {
        return Collections.unmodifiableMap(clientHandlers);
    }

    /**
     * Registers a client handler for the given user ID.
     *
     * @param userId
     *            the user's ID
     * @param handler
     *            the client handler
     */
    public void registerClientHandler(int userId, V086ClientHandler handler) {
        clientHandlers.put(userId, handler);
    }

    /**
     * Unregisters the client handler for the given user ID.
     *
     * @param userId
     *            the user's ID
     */
    public void unregisterClientHandler(int userId) {
        clientHandlers.remove(userId);
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    public String toString() {
        return "V086Controller[clients=" + clientHandlers.size() + " isRunning=" + isRunning + "]";
    }

    public int newConnection(InetSocketAddress clientSocketAddress, String protocol)
            throws ServerFullException, NewConnectionException {
        if (!isRunning)
            throw new NewConnectionException("Controller is not running");

        // Select bind address matching client's address family (IPv4 or IPv6)
        InetAddress bindAddress = selectBindAddress(clientSocketAddress.getAddress());

        V086ClientHandler clientHandler = new V086ClientHandler(clientSocketAddress, this,
                bufferSize, threadPool, portAllocator, server, actionRouter);

        // Run admission before allocating a socket. A spoofed HELLO that will be
        // denied must not cost a bind plus handler buffers.
        KailleraUser user;
        try {
            user = server.newConnection(clientSocketAddress, protocol,
                    clientHandler.getEventDispatcher());
        } catch (NewConnectionException e) {
            // Catches both NewConnectionException and ServerFullException
            clientHandler.stop();
            throw e;
        }

        int boundPort = -1;
        int bindAttempts = 0;
        while (bindAttempts++ < 5) {
            OptionalInt portOpt = portAllocator.allocate();
            if (portOpt.isEmpty()) {
                log.error("No ports are available to bind for: {}", clientSocketAddress);
            } else {
                int port = portOpt.getAsInt();
                log.info("Private port {} allocated to {} (bind address: {})", port,
                        clientSocketAddress, bindAddress.getHostAddress());

                try {
                    clientHandler.bind(port, bindAddress);
                    boundPort = port;
                    break;
                } catch (BindException e) {
                    log.error("Failed to bind to {}:{} for {}: {}", bindAddress.getHostAddress(),
                            port, clientSocketAddress, e.getMessage(), e);
                    log.debug("{} returning port {} to available port queue: {} available", this,
                            port, portAllocator.availableCount() + 1);
                    portAllocator.release(port);
                }
            }

            try {
                // pause very briefly to give the OS a chance to free a port
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (boundPort < 0) {
            discardConnection(user, clientHandler);
            throw new NewConnectionException("Failed to bind!");
        }

        // Serialize handler publication with stop(). Without this, a connection
        // that passed the initial isRunning check could bind and register after
        // stop() cleared the map, leaving its socket unmanaged.
        synchronized (this) {
            if (!isRunning) {
                discardConnection(user, clientHandler);
                throw new NewConnectionException("Controller is not running");
            }

            clientHandler.start(user);
        }
        return boundPort;
    }

    /**
     * Removes a user created for a connection that could not be completed. Not
     * logged in yet, so quit() only removes it from the user list.
     */
    private void discardConnection(KailleraUser user, V086ClientHandler clientHandler) {
        try {
            server.quit(user, "");
        } catch (Exception e) {
            log.debug("Failed to remove discarded connection {}: {}", user, e.getMessage());
        }
        clientHandler.stop();
    }

    private InetAddress selectBindAddress(InetAddress clientAddress) {
        boolean clientIsIPv6 = clientAddress instanceof Inet6Address;

        // First try to find matching address family
        for (InetAddress addr : bindAddresses) {
            boolean addrIsIPv6 = addr instanceof Inet6Address;
            if (clientIsIPv6 == addrIsIPv6) {
                return addr;
            }
        }

        // Fall back to first available address
        return bindAddresses.getFirst();
    }

    @Override
    public synchronized void start() {
        if (isRunning) {
            log.debug("V086Controller start request ignored: already running!");
            return;
        }

        isRunning = true;
        log.info("V086Controller started");
    }

    @Override
    public synchronized void stop() {
        if (!isRunning) {
            log.debug("V086Controller stop request ignored: not running!");
            return;
        }

        isRunning = false;

        for (V086ClientHandler clientHandler : clientHandlers.values())
            clientHandler.stop();

        clientHandlers.clear();
        log.info("V086Controller stopped");
    }

    @Override
    public int getPhase() {
        // Phase 20: Protocol handler, after core server
        return 20;
    }
}
