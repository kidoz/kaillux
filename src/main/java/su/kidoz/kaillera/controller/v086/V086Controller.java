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
        int portCount = serverConfig.getMaxUsers() + extraPorts + 1;
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

        int boundPort = -1;
        int bindAttempts = 0;
        V086ClientHandler clientHandler = new V086ClientHandler(clientSocketAddress, this,
                bufferSize, threadPool, portAllocator, server, actionRouter);
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
            clientHandler.stop();
            throw new NewConnectionException("Failed to bind!");
        }

        KailleraUser user;
        try {
            user = server.newConnection(clientSocketAddress, protocol,
                    clientHandler.getEventDispatcher());
        } catch (NewConnectionException e) {
            // Catches both NewConnectionException and ServerFullException (subclass)
            clientHandler.stop();
            throw e;
        }

        clientHandler.start(user);
        return boundPort;
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
