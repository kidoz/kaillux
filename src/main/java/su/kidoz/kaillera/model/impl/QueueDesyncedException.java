package su.kidoz.kaillera.model.impl;

/**
 * Raised when a player queue has no committed input because that player is no
 * longer synched. Callers must not treat this as a successful read.
 */
public class QueueDesyncedException extends RuntimeException {
    private final int playerNumber;

    public QueueDesyncedException(int playerNumber) {
        super("Player " + playerNumber + " queue is not synched");
        this.playerNumber = playerNumber;
    }

    public int getPlayerNumber() {
        return playerNumber;
    }
}
