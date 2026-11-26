package su.kidoz.kaillera.model;

/**
 * Detects rapid alternating input that suggests an auto-fire controller.
 *
 * <p>
 * The detector lives in the model package so the domain interfaces do not have
 * to depend on an implementation package.
 */
public interface AutoFireDetector {
    void start(int numPlayers);
    void addPlayer(KailleraUser user, int playerNumber);
    void addData(int playerNumber, byte[] data, int bytesPerAction);
    void stop(int playerNumber);
    void stop();

    void setSensitivity(int sensitivity);
    int getSensitivity();
}
