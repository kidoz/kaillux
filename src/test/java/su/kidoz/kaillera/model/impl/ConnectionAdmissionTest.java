package su.kidoz.kaillera.model.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;

import su.kidoz.config.ServerConfig;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.model.exception.ServerFullException;

import org.junit.jupiter.api.Test;

class ConnectionAdmissionTest {

    @Test
    void loggedInUsersDoNotConsumeTheConnectingBudget() throws Exception {
        ServerConfig config = config(1, 2, 2, 0);
        ConnectionAdmission admission = new ConnectionAdmission();
        InetAddress address = InetAddress.getByName("10.1.1.1");

        assertThrows(ServerFullException.class,
                () -> admission.admit(address, AccessManager.ACCESS_NORMAL, 1, 0, 0, config));
        assertDoesNotThrow(
                () -> admission.admit(address, AccessManager.ACCESS_ADMIN, 1, 0, 0, config));
        assertThrows(ServerFullException.class,
                () -> admission.admit(address, AccessManager.ACCESS_ADMIN, 0, 2, 0, config));
    }

    @Test
    void oneAddressCannotFillEveryConnectingSlot() throws Exception {
        ServerConfig config = config(25, 10, 1, 0);
        ConnectionAdmission admission = new ConnectionAdmission();
        InetAddress address = InetAddress.getByName("10.2.2.2");

        assertDoesNotThrow(
                () -> admission.admit(address, AccessManager.ACCESS_NORMAL, 0, 0, 0, config));
        assertThrows(ServerFullException.class,
                () -> admission.admit(address, AccessManager.ACCESS_ADMIN, 0, 1, 1, config));
    }

    @Test
    void helloRateAppliesToAdminAddresses() throws Exception {
        ServerConfig config = config(25, 10, 8, 1);
        ConnectionAdmission admission = new ConnectionAdmission();
        InetAddress address = InetAddress.getByName("10.3.3.3");

        assertDoesNotThrow(
                () -> admission.admit(address, AccessManager.ACCESS_ADMIN, 0, 0, 0, config));
        assertThrows(ServerFullException.class,
                () -> admission.admit(address, AccessManager.ACCESS_ADMIN, 0, 0, 0, config));
    }

    private static ServerConfig config(int maxUsers, int maxConnecting, int perAddress,
            int helloRate) {
        ServerConfig config = new ServerConfig();
        config.setMaxUsers(maxUsers);
        config.setMaxConnecting(maxConnecting);
        config.setMaxConnectingPerAddress(perAddress);
        config.setHelloRatePerSecond(helloRate);
        return config;
    }
}
