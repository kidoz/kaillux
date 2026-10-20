package su.kidoz.config;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Server configuration properties.
 *
 * <p>
 * Maps to properties with prefix "server." in application.properties.
 */
@ConfigurationProperties(prefix = "server")
@Validated
public class ServerConfig {

    @Min(1)
    @Max(999)
    private int maxPing = 250;

    @NotEmpty
    private List<Integer> allowedConnectionTypes = List.of(1, 2, 3, 4, 5, 6);

    @Min(0)
    private int maxUsers = 25;

    /**
     * In-progress handshakes allowed in addition to {@link #maxUsers}. Zero
     * disables the cap. These sessions do not consume logged-in slots.
     */
    @Min(0)
    private int maxConnecting = 0;

    /**
     * In-progress handshakes allowed from one source address. Zero disables the
     * cap. Logged-in users do not count against it.
     */
    @Min(0)
    private int maxConnectingPerAddress = 0;

    /**
     * HELLO messages accepted per source address per second. Zero disables the
     * limit.
     */
    @Min(0)
    private int helloRatePerSecond = 0;

    @Min(0)
    private int maxGames = 0;

    private boolean allowSinglePlayer = true;

    private boolean allowMultipleConnections = true;

    @Min(1)
    private int keepAliveTimeout = 190;

    @Min(1)
    private int idleTimeout = 900;

    @Min(0)
    private int chatFloodTime = 2;

    @Min(0)
    private int createGameFloodTime = 2;

    @Min(1)
    private int maxUserNameLength = 45;

    @Min(1)
    private int maxClientNameLength = 100;

    @Min(1)
    private int maxChatLength = 150;

    @Min(1)
    private int maxGameNameLength = 100;

    @Min(1)
    private int maxQuitMessageLength = 75;

    // Getters and setters

    public int getMaxPing() {
        return maxPing;
    }

    public void setMaxPing(int maxPing) {
        this.maxPing = maxPing;
    }

    public List<Integer> getAllowedConnectionTypes() {
        return allowedConnectionTypes;
    }

    public void setAllowedConnectionTypes(List<Integer> allowedConnectionTypes) {
        this.allowedConnectionTypes = allowedConnectionTypes;
    }

    public int getMaxUsers() {
        return maxUsers;
    }

    public void setMaxUsers(int maxUsers) {
        this.maxUsers = maxUsers;
    }

    public int getMaxConnecting() {
        return maxConnecting;
    }

    public void setMaxConnecting(int maxConnecting) {
        this.maxConnecting = maxConnecting;
    }

    public int getMaxConnectingPerAddress() {
        return maxConnectingPerAddress;
    }

    public void setMaxConnectingPerAddress(int maxConnectingPerAddress) {
        this.maxConnectingPerAddress = maxConnectingPerAddress;
    }

    public int getHelloRatePerSecond() {
        return helloRatePerSecond;
    }

    public void setHelloRatePerSecond(int helloRatePerSecond) {
        this.helloRatePerSecond = helloRatePerSecond;
    }

    public int getMaxGames() {
        return maxGames;
    }

    public void setMaxGames(int maxGames) {
        this.maxGames = maxGames;
    }

    public boolean isAllowSinglePlayer() {
        return allowSinglePlayer;
    }

    public void setAllowSinglePlayer(boolean allowSinglePlayer) {
        this.allowSinglePlayer = allowSinglePlayer;
    }

    public boolean isAllowMultipleConnections() {
        return allowMultipleConnections;
    }

    public void setAllowMultipleConnections(boolean allowMultipleConnections) {
        this.allowMultipleConnections = allowMultipleConnections;
    }

    public int getKeepAliveTimeout() {
        return keepAliveTimeout;
    }

    public void setKeepAliveTimeout(int keepAliveTimeout) {
        this.keepAliveTimeout = keepAliveTimeout;
    }

    public int getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(int idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public int getChatFloodTime() {
        return chatFloodTime;
    }

    public void setChatFloodTime(int chatFloodTime) {
        this.chatFloodTime = chatFloodTime;
    }

    public int getCreateGameFloodTime() {
        return createGameFloodTime;
    }

    public void setCreateGameFloodTime(int createGameFloodTime) {
        this.createGameFloodTime = createGameFloodTime;
    }

    public int getMaxUserNameLength() {
        return maxUserNameLength;
    }

    public void setMaxUserNameLength(int maxUserNameLength) {
        this.maxUserNameLength = maxUserNameLength;
    }

    public int getMaxClientNameLength() {
        return maxClientNameLength;
    }

    public void setMaxClientNameLength(int maxClientNameLength) {
        this.maxClientNameLength = maxClientNameLength;
    }

    public int getMaxChatLength() {
        return maxChatLength;
    }

    public void setMaxChatLength(int maxChatLength) {
        this.maxChatLength = maxChatLength;
    }

    public int getMaxGameNameLength() {
        return maxGameNameLength;
    }

    public void setMaxGameNameLength(int maxGameNameLength) {
        this.maxGameNameLength = maxGameNameLength;
    }

    public int getMaxQuitMessageLength() {
        return maxQuitMessageLength;
    }

    public void setMaxQuitMessageLength(int maxQuitMessageLength) {
        this.maxQuitMessageLength = maxQuitMessageLength;
    }

    /**
     * Check if a connection type is allowed.
     *
     * @param connectionType
     *            the connection type (1-6)
     * @return true if allowed
     */
    public boolean isConnectionTypeAllowed(int connectionType) {
        return allowedConnectionTypes.contains(connectionType);
    }
}
