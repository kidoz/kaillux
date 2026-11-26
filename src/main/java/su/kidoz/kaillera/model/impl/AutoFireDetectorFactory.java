package su.kidoz.kaillera.model.impl;

import su.kidoz.kaillera.model.AutoFireDetector;
import su.kidoz.kaillera.model.KailleraGame;

public interface AutoFireDetectorFactory {
    AutoFireDetector getInstance(KailleraGame game, int defaultSensitivity);
}
