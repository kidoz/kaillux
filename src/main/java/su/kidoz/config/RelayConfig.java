package su.kidoz.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Relay mode configuration properties.
 *
 * <p>
 * When enabled, the server acts as a transparent UDP relay/proxy, forwarding
 * Kaillera protocol traffic between clients and a backend server. This enables
 * clients behind NAT/firewalls to connect to Kaillera servers they couldn't
 * reach directly.
 *
 * <p>
 * Maps to properties with prefix "relay." in application.properties.
 */
@ConfigurationProperties(prefix = "relay")
@Validated
public class RelayConfig {

    /**
     * Whether relay mode is enabled. When true, the server will start a relay
     * controller that forwards traffic to the configured backend server.
     */
    private boolean enabled = false;

    /**
     * The port to listen on for incoming relay connections. Should be different
     * from the main server's connect port.
     */
    @Min(1)
    @Max(65535)
    private int listenPort = 27887;

    /**
     * Local address to bind relay sockets to. The wildcard default accepts every
     * interface; set a specific address when the relay is reachable only through
     * one network.
     */
    @NotBlank
    private String bindAddress = "0.0.0.0";

    /**
     * First data port the backend is allowed to assign. A compromised backend must
     * not make the relay bind arbitrary ports on the host.
     */
    @Min(1)
    @Max(65535)
    private int dataPortRangeStart = 27889;

    /**
     * Last data port the backend is allowed to assign.
     */
    @Min(1)
    @Max(65535)
    private int dataPortRangeEnd = 65535;

    /**
     * Maximum number of dynamically spawned data relays kept at once.
     */
    @Min(1)
    @Max(1000)
    private int maxRelayPorts = 100;

    /**
     * The hostname or IP address of the backend Kaillera server to relay traffic
     * to.
     */
    @NotBlank
    private String backendHost = "localhost";

    /**
     * The port of the backend Kaillera server's connect controller.
     */
    @Min(1)
    @Max(65535)
    private int backendPort = 27888;

    /**
     * Maximum number of concurrent relay connections allowed.
     */
    @Min(1)
    @Max(10000)
    private int maxConnections = 100;

    /**
     * Buffer size in bytes for UDP packets.
     */
    @Min(512)
    @Max(65536)
    private int bufferSize = 2048;

    /**
     * Idle timeout in seconds for V086 relay controllers. Relays with no traffic
     * for this duration will be automatically stopped and removed. Set to 0 to
     * disable cleanup (not recommended).
     */
    @Min(0)
    @Max(86400)
    private int idleTimeoutSeconds = 300; // 5 minutes default

    /**
     * Interval in seconds between idle relay cleanup checks.
     */
    @Min(10)
    @Max(3600)
    private int cleanupIntervalSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public int getDataPortRangeStart() {
        return dataPortRangeStart;
    }

    public void setDataPortRangeStart(int dataPortRangeStart) {
        this.dataPortRangeStart = dataPortRangeStart;
    }

    public int getDataPortRangeEnd() {
        return dataPortRangeEnd;
    }

    public void setDataPortRangeEnd(int dataPortRangeEnd) {
        this.dataPortRangeEnd = dataPortRangeEnd;
    }

    public int getMaxRelayPorts() {
        return maxRelayPorts;
    }

    public void setMaxRelayPorts(int maxRelayPorts) {
        this.maxRelayPorts = maxRelayPorts;
    }

    public String getBackendHost() {
        return backendHost;
    }

    public void setBackendHost(String backendHost) {
        this.backendHost = backendHost;
    }

    public int getBackendPort() {
        return backendPort;
    }

    public void setBackendPort(int backendPort) {
        this.backendPort = backendPort;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public int getCleanupIntervalSeconds() {
        return cleanupIntervalSeconds;
    }

    public void setCleanupIntervalSeconds(int cleanupIntervalSeconds) {
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
    }
}
