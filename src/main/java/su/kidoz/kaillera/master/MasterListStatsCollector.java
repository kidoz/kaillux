package su.kidoz.kaillera.master;

import java.util.ArrayList;
import java.util.List;

import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraServer;

public class MasterListStatsCollector implements StatsCollector {
    private ArrayList<String> startedGamesList = new ArrayList<String>();

    public synchronized void gameStarted(KailleraServer server, KailleraGame game) {
        startedGamesList.add(game.getRomName());
    }

    public synchronized List<String> getStartedGamesList() {
        return new ArrayList<>(startedGamesList);
    }

    public synchronized List<String> drainStartedGamesList() {
        List<String> drained = new ArrayList<>(startedGamesList);
        startedGamesList.clear();
        return drained;
    }

    public synchronized void clearStartedGamesList() {
        startedGamesList.clear();
    }
}
