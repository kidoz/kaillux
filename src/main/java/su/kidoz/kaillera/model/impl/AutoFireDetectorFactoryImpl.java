package su.kidoz.kaillera.model.impl;

import su.kidoz.kaillera.model.AutoFireDetector;
import su.kidoz.kaillera.model.KailleraGame;

public class AutoFireDetectorFactoryImpl implements AutoFireDetectorFactory {
    public AutoFireDetector getInstance(KailleraGame game, int defaultSensitivity) {
        return new AutoFireScanner(game, defaultSensitivity);
    }
}
