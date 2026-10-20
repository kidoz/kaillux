package su.kidoz.kaillera.model.event;

public interface KailleraEventListener {
    void actionPerformed(KailleraEvent event);

    void stop();

    /**
     * Closes the listener's transport without stopping the user thread.
     *
     * <p>
     * The default stops the listener entirely. Handlers that own a socket override
     * this so quit can close the port before it returns.
     */
    default void closeConnection() {
        stop();
    }
}
