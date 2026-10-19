package su.kidoz.kaillera.master;

import java.util.List;

import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraServer;

public interface StatsCollector {
    void gameStarted(KailleraServer server, KailleraGame game);

    List<String> getStartedGamesList();

    void clearStartedGamesList();

    /**
     * Atomically returns the games started since the last drain and clears the
     * list. Implementations that only support snapshots fall back to a
     * copy-then-clear sequence.
     */
    default List<String> drainStartedGamesList() {
        List<String> drained = List.copyOf(getStartedGamesList());
        clearStartedGamesList();
        return drained;
    }
}
