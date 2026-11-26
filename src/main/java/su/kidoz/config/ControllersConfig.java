package su.kidoz.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Controllers configuration properties.
 *
 * <p>
 * Maps to properties with prefix "controllers." in application.properties.
 */
@ConfigurationProperties(prefix = "controllers")
@Validated
public class ControllersConfig {

    @NotEmpty
    private List<String> bindAddresses = List.of("0.0.0.0", "::");

    @Valid
    private Connect connect = new Connect();

    @Valid
    private V086 v086 = new V086();

    public List<String> getBindAddresses() {
        return bindAddresses;
    }

    public void setBindAddresses(List<String> bindAddresses) {
        this.bindAddresses = bindAddresses;
    }

    /**
     * Parses bind addresses into InetAddress objects.
     *
     * @return list of parsed InetAddress objects
     * @throws IllegalStateException
     *             if any address cannot be parsed
     */
    public List<InetAddress> getParsedBindAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        for (String addr : bindAddresses) {
            try {
                addresses.add(InetAddress.getByName(addr));
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid bind address: " + addr, e);
            }
        }
        return addresses;
    }

    public Connect getConnect() {
        return connect;
    }

    public void setConnect(Connect connect) {
        this.connect = connect;
    }

    public V086 getV086() {
        return v086;
    }

    public void setV086(V086 v086) {
        this.v086 = v086;
    }

    /**
     * Connect controller configuration.
     */
    public static class Connect {

        @Min(1)
        @Max(65535)
        private int port = 27888;

        @Min(1)
        private int bufferSize = 1024;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getBufferSize() {
            return bufferSize;
        }

        public void setBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
        }
    }

    /**
     * V086 controller configuration.
     */
    public static class V086 {

        @Min(1)
        @Max(65535)
        private int portRangeStart = 27889;

        @Min(0)
        private int extraPorts = 10;

        /**
         * Explicit data-socket pool size. Zero derives it from server.max-users,
         * server.max-connecting, and extra-ports.
         */
        @Min(0)
        private int maxSessions = 0;

        @Min(1)
        private int bufferSize = 2048;

        @NotEmpty
        private List<String> clientTypes = List.of("0.83");

        public int getPortRangeStart() {
            return portRangeStart;
        }

        public void setPortRangeStart(int portRangeStart) {
            this.portRangeStart = portRangeStart;
        }

        public int getExtraPorts() {
            return extraPorts;
        }

        public void setExtraPorts(int extraPorts) {
            this.extraPorts = extraPorts;
        }

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
        }

        public int getBufferSize() {
            return bufferSize;
        }

        public void setBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
        }

        public List<String> getClientTypes() {
            return clientTypes;
        }

        public void setClientTypes(List<String> clientTypes) {
            this.clientTypes = clientTypes;
        }
    }
}
