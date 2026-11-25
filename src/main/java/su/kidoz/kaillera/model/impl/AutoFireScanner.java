package su.kidoz.kaillera.model.impl;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.util.EmuLang;
import su.kidoz.util.EmuUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoFireScanner implements AutoFireDetector {
    protected static final Logger log = LoggerFactory.getLogger(AutoFireScanner.class);

    // MAX DELAY, MIN REPEITIONS
    private static final int[][] SENSITIVITY_TABLE = {{0, 0}, // 0 means disable autofire detect
            {2, 13}, // 1 is least sensitive
            {3, 11}, {4, 9}, {5, 7}, {6, 5} // 5 is most sensitive
    };

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    protected KailleraGame game;
    protected int sensitivity;
    protected int maxDelay;
    protected int minReps;

    protected ScanningJob[] scanningJobs;

    public AutoFireScanner(KailleraGame game, int sensitivity) {
        this.game = game;
        setSensitivity(sensitivity);
    }

    public int getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(int sensitivity) {
        if (sensitivity < 0 || sensitivity > 5)
            this.sensitivity = 0;
        else {
            this.sensitivity = sensitivity;
            maxDelay = SENSITIVITY_TABLE[sensitivity][0];
            minReps = SENSITIVITY_TABLE[sensitivity][1];
        }
    }

    public void start(int numPlayers) {
        if (sensitivity <= 0)
            return;

        scanningJobs = new ScanningJob[numPlayers];
    }

    public void addPlayer(KailleraUser player, int playerNumber) {
        if (sensitivity <= 0 || scanningJobs == null)
            return;

        scanningJobs[(playerNumber - 1)] = new ScanningJob(player, playerNumber);
    }

    public void stop(int playerNumber) {
        if (sensitivity <= 0 || scanningJobs == null)
            return;

        int index = playerNumber - 1;
        if (index < 0 || index >= scanningJobs.length || scanningJobs[index] == null)
            return;

        scanningJobs[index].stop();
    }

    public void stop() {
        if (sensitivity <= 0 || scanningJobs == null)
            return;

        for (int i = 0; i < scanningJobs.length; i++) {
            if (scanningJobs[i] != null)
                scanningJobs[i].stop();
        }
    }

    public void addData(int playerNumber, byte[] data, int bytesPerAction) {
        if (sensitivity <= 0 || scanningJobs == null)
            return;

        int index = playerNumber - 1;
        if (index < 0 || index >= scanningJobs.length || scanningJobs[index] == null)
            return;

        scanningJobs[index].addData(data, bytesPerAction);
    }

    protected class ScanningJob implements Runnable {
        private KailleraUser user;

        private int bytesPerAction = -1;
        private int sizeLimit;
        private int bufferSize = 5;
        // Guarded by this: filled by addData and drained by run.
        private int size = 0;

        private byte[][] buffer;
        private int head = 0;
        private int tail = 0;
        private int pos = 0;

        private boolean running = false;
        private volatile boolean stopFlag = false;

        protected ScanningJob(KailleraUser user, int playerNumber) {
            this.user = user;
            // playerNumber is passed to keep the caller's signature; the job does not need
            // it.

            sizeLimit = ((maxDelay + 1) * minReps * 5);
            buffer = new byte[bufferSize][sizeLimit];
        }

        protected synchronized void addData(byte[] data, int bytesPerAction) {
            if (sizeLimit <= 0) {
                return;
            }

            int offset = 0;
            int remaining = data.length;

            while (remaining > 0) {
                int space = sizeLimit - pos;
                int copySize = Math.min(remaining, space);
                System.arraycopy(data, offset, buffer[tail], pos, copySize);
                pos += copySize;
                offset += copySize;
                remaining -= copySize;

                if (pos >= sizeLimit) {
                    tail = ((tail + 1) % bufferSize);
                    pos = 0;
                    size++;

                    if (this.bytesPerAction <= 0) {
                        this.bytesPerAction = bytesPerAction;
                    }

                    if (!running) {
                        EXECUTOR.submit(this);
                    }
                }
            }
        }

        protected void stop() {
            this.stopFlag = true;
        }

        public void run() {
            // long st = System.currentTimeMillis();
            synchronized (this) {
                running = true;
            }

            try {
                while (!stopFlag) {
                    byte[] data;
                    synchronized (this) {
                        // size is only ever touched under the lock.
                        if (size <= 0)
                            break;

                        data = buffer[head];
                        // log.debug("Scanning " + data.length + " bytes from buffer position " +
                        // head);
                        head = ((head + 1) % bufferSize);
                        size--;
                    }

                    // determine the number of actions in this array
                    int actionCount = (data.length / bytesPerAction);
                    byte[] thisAction = new byte[bytesPerAction];
                    byte[] lastAction = new byte[bytesPerAction];
                    byte[] actionA = new byte[bytesPerAction];
                    int aCount = 0;
                    int aSequence = 0;
                    int lastASequence = 0;
                    int aSequenceCount = 0;
                    byte[] actionB = new byte[bytesPerAction];
                    int bCount = 0;
                    int bSequence = 0;
                    int lastBSequence = 0;
                    int bSequenceCount = 0;

                    for (int i = 0; i < actionCount; i++) {
                        System.arraycopy(data, (i * bytesPerAction), thisAction, 0, bytesPerAction);
                        // log.debug("thisAction=" + EmuUtil.bytesToHex(thisAction) + " actionA=" +
                        // EmuUtil.bytesToHex(actionA) + " aCount=" + aCount + " actionB=" +
                        // EmuUtil.bytesToHex(actionB) + " bCount=" + bCount + " aSequence=" +
                        // aSequence
                        // + " aSequenceCount=" + aSequenceCount + " bSequence=" + bSequence + "
                        // bSequenceCount=" + bSequenceCount);

                        if (aCount == 0) {
                            System.arraycopy(thisAction, 0, actionA, 0, bytesPerAction);
                            aCount = 1;
                            aSequence = 1;
                        } else if (Arrays.equals(thisAction, actionA)) {
                            aCount++;
                            if (Arrays.equals(thisAction, lastAction))
                                aSequence++;
                            else {
                                if (lastASequence == aSequence && aSequence <= maxDelay)
                                    aSequenceCount++;
                                else
                                    aSequenceCount = 0;
                                lastASequence = aSequence;
                                aSequence = 1;
                            }
                        } else if (bCount == 0) {
                            System.arraycopy(thisAction, 0, actionB, 0, bytesPerAction);
                            bCount = 1;
                            bSequence = 1;
                        } else if (Arrays.equals(thisAction, actionB)) {
                            bCount++;
                            if (Arrays.equals(thisAction, lastAction))
                                bSequence++;
                            else {
                                if (lastBSequence == bSequence && bSequence <= maxDelay)
                                    bSequenceCount++;
                                else
                                    bSequenceCount = 0;
                                lastBSequence = bSequence;
                                bSequence = 1;
                            }
                        } else {
                            actionA = lastAction;
                            aCount = 1;
                            aSequence = 1;
                            aSequenceCount = 0;
                            actionB = thisAction;
                            bCount = 1;
                            bSequence = 0;
                            bSequenceCount = 0;
                        }

                        System.arraycopy(thisAction, 0, lastAction, 0, bytesPerAction);

                        // if(aSequenceCount >= 3 && bSequenceCount >= 3 && !stopFlag)
                        // {
                        // log.debug("thisAction=" + EmuUtil.bytesToHex(thisAction) + " actionA=" +
                        // EmuUtil.bytesToHex(actionA) + " aCount=" + aCount + " actionB=" +
                        // EmuUtil.bytesToHex(actionB) + " bCount=" + bCount + " aSequence=" +
                        // aSequence
                        // + " aSequenceCount=" + aSequenceCount + " bSequence=" + bSequence + "
                        // bSequenceCount=" + bSequenceCount);
                        // }

                        if (aSequenceCount >= minReps && bSequenceCount >= minReps && !stopFlag) {
                            KailleraGameImpl gameImpl = (KailleraGameImpl) game;
                            gameImpl.announce(EmuLang.getString("AutoFireScanner2.AutoFireDetected",
                                    user.getName()));
                            log.info("AUTOUSERDUMP\t" + EmuUtil.formatDate(gameImpl.getStartDate())
                                    + "\t" + (aSequence < bSequence ? aSequence : bSequence) + "\t"
                                    + game.getID() + "\t" + game.getRomName() + "\t"
                                    + user.getName() + "\t"
                                    + user.getSocketAddress().getAddress().getHostAddress());
                            // log.debug("thisAction=" + EmuUtil.bytesToHex(thisAction) + "
                            // actionA=" +
                            // EmuUtil.bytesToHex(actionA) + " aCount=" + aCount + " actionB=" +
                            // EmuUtil.bytesToHex(actionB) + " bCount=" + bCount + " aSequence=" +
                            // aSequence
                            // + " aSequenceCount=" + aSequenceCount + " bSequence=" + bSequence + "
                            // bSequenceCount=" + bSequenceCount);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("AutoFireScanner thread for " + user + " caught exception!", e);
            } finally {
                synchronized (this) {
                    running = false;
                }
            }

            // long et = (System.currentTimeMillis()-st);
            // log.debug("Scanning completed in " + et + " ms");
        }
    }
}
