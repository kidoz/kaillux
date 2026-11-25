package su.kidoz.kaillera.access;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.NoSuchElementException;

import su.kidoz.util.EmuLinkerExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import su.kidoz.kaillera.access.parser.AccessConfigParser;
import su.kidoz.kaillera.access.parser.ParseResult;
import su.kidoz.kaillera.access.rule.AddressRule;
import su.kidoz.kaillera.access.store.AccessRuleStore;
import su.kidoz.kaillera.access.store.TemporaryRuleStore;

/**
 * File-based access manager that loads rules from a configuration file and
 * supports hot-reload. Delegates to specialized stores for rule management.
 *
 * <p>
 * Runs a background thread that periodically:
 * <ul>
 * <li>Checks for config file changes and reloads them</li>
 * <li>Purges expired temporary rules</li>
 * <li>Refreshes DNS resolutions</li>
 * </ul>
 * The access checks themselves never touch the disk or the resolver.
 */
public class FileBasedAccessManager implements AccessManager, Runnable, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FileBasedAccessManager.class);
    private static final String DEFAULT_ACCESS_FILE = "access.cfg";

    /** How often the access file's modification time is compared. */
    private static final long RELOAD_CHECK_INTERVAL_MS = 1_000;

    /**
     * How many reload checks pass between purges of temporary rules and DNS
     * refreshes.
     */
    private static final int CHECKS_PER_MAINTENANCE = 60;

    private static final String IPV4_LOOPBACK = "127.0.0.1";
    private static final String IPV6_LOOPBACK = "0:0:0:0:0:0:0:1";

    private final EmuLinkerExecutor threadPool;
    private final AccessConfigParser parser;
    private final AccessRuleStore ruleStore;
    private final TemporaryRuleStore tempStore;

    private volatile boolean isRunning = false;
    private volatile boolean stopFlag = false;

    private File accessFile;

    /**
     * Modification time of the rule set that is loaded. Read and written by the
     * manager thread, and once by the constructor.
     */
    private volatile long lastLoadModifiedTime = -1;

    public FileBasedAccessManager(EmuLinkerExecutor threadPool)
            throws NoSuchElementException, FileNotFoundException {
        this(threadPool, null);
    }

    public FileBasedAccessManager(EmuLinkerExecutor threadPool, String accessFilePath)
            throws NoSuchElementException, FileNotFoundException {
        this.threadPool = threadPool;
        this.parser = new AccessConfigParser();
        this.ruleStore = new AccessRuleStore();
        this.tempStore = new TemporaryRuleStore();

        resolveAccessFile(accessFilePath);
        loadAccess(true);
        // Thread is started via SmartLifecycle.start(), not in constructor
    }

    private void resolveAccessFile(String accessFilePath) throws FileNotFoundException {
        if (accessFilePath != null && !accessFilePath.isEmpty()) {
            accessFile = new File(accessFilePath);
        } else {
            File cwdFile = new File(DEFAULT_ACCESS_FILE);
            if (cwdFile.exists() && cwdFile.canRead()) {
                accessFile = cwdFile;
                log.info("Using access file from current directory: {}", cwdFile.getAbsolutePath());
            } else {
                URL url = FileBasedAccessManager.class.getResource("/" + DEFAULT_ACCESS_FILE);
                if (url == null) {
                    throw new FileNotFoundException("Access file not found. Place "
                            + DEFAULT_ACCESS_FILE
                            + " in the application directory or specify path via configuration.");
                }
                try {
                    if ("file".equals(url.getProtocol())) {
                        accessFile = new File(url.toURI());
                    } else {
                        // Nested-jar URLs are not hierarchical, so new File(uri) throws
                        // IllegalArgumentException. Extract the packaged resource to a
                        // temporary file that the parser can read.
                        accessFile = extractClasspathAccessFile(url);
                    }
                } catch (URISyntaxException e) {
                    throw new FileNotFoundException(
                            "Cannot read access file from classpath in packaged deployment. "
                                    + "Place " + DEFAULT_ACCESS_FILE
                                    + " in the application directory.");
                }
            }
        }

        if (!accessFile.exists()) {
            throw new FileNotFoundException("Access file not found: " + accessFile.getPath());
        }
        if (!accessFile.canRead()) {
            throw new FileNotFoundException("Cannot read access file: " + accessFile.getPath());
        }

        log.info("Loading access rules from: {}", accessFile.getAbsolutePath());
    }

    private File extractClasspathAccessFile(URL url) throws FileNotFoundException {
        try (InputStream in = url.openStream()) {
            Path tempFile = Files.createTempFile("kaillux-access-", ".cfg");
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            File file = tempFile.toFile();
            file.deleteOnExit();
            log.info("Extracted packaged access file to: {}", file.getAbsolutePath());
            return file;
        } catch (IOException e) {
            FileNotFoundException failure = new FileNotFoundException(
                    "Cannot read access file from classpath in packaged deployment. Place "
                            + DEFAULT_ACCESS_FILE + " in the application directory.");
            failure.initCause(e);
            throw failure;
        }
    }

    @Override
    public synchronized void start() {
        if (isRunning) {
            log.debug("FileBasedAccessManager start request ignored: already running!");
            return;
        }

        log.debug("FileBasedAccessManager thread starting (ThreadPool: {}/{})",
                threadPool.getActiveCount(), threadPool.getPoolSize());
        stopFlag = false;
        threadPool.execute(this);
        log.info("FileBasedAccessManager started");
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public synchronized void stop() {
        log.debug("FileBasedAccessManager thread received stop request!");

        if (!isRunning()) {
            log.debug("FileBasedAccessManager thread stop request ignored: not running!");
            return;
        }

        stopFlag = true;
        ruleStore.clear();
        tempStore.clear();
        log.info("FileBasedAccessManager stopped");
    }

    @Override
    public int getPhase() {
        // Phase 0: Must be ready first (access rules needed by other components)
        return 0;
    }

    @Override
    public void run() {
        isRunning = true;
        log.debug("FileBasedAccessManager thread running...");

        try {
            int checksUntilMaintenance = CHECKS_PER_MAINTENANCE;
            while (!stopFlag) {
                try {
                    Thread.sleep(RELOAD_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (stopFlag) {
                    break;
                }

                reloadIfChanged();

                if (--checksUntilMaintenance <= 0) {
                    checksUntilMaintenance = CHECKS_PER_MAINTENANCE;
                    // Neither step takes the manager monitor: a slow name lookup must
                    // not stall the HELLO, login and chat checks that other threads make.
                    tempStore.purgeExpired();
                    ruleStore.refreshDns();
                }
            }
        } catch (Throwable e) {
            if (!stopFlag) {
                log.error("FileBasedAccessManager thread caught unexpected exception: " + e, e);
            }
        } finally {
            isRunning = false;
            log.debug("FileBasedAccessManager thread exiting...");
        }
    }

    /**
     * Reloads the access file when its modification time is newer than that of the
     * loaded rule set.
     *
     * @return true if the file had changed and a reload was attempted
     */
    boolean reloadIfChanged() {
        File file = accessFile;
        if (file == null || file.lastModified() <= lastLoadModifiedTime) {
            return false;
        }
        loadAccess(false);
        return true;
    }

    /**
     * Parses the access file and swaps the result in. Runs without the manager
     * monitor, so file and DNS access never block the access checks.
     *
     * @param initial
     *            true for the load at startup, when there is no earlier rule set to
     *            fall back on
     */
    private void loadAccess(boolean initial) {
        File file = accessFile;
        if (file == null) {
            return;
        }

        log.info("Reloading permissions...");
        // The time is read before and after parsing. A difference means the file was
        // being written while it was read, so what was parsed may be incomplete.
        long modifiedBefore = file.lastModified();
        ParseResult result;
        try {
            result = parser.parse(file);
        } catch (IOException e) {
            log.error("Failed to load access file, keeping the previous rules: {}", e.getMessage(),
                    e);
            return;
        }
        long modifiedAfter = file.lastModified();

        if (!initial && modifiedAfter != modifiedBefore) {
            // lastLoadModifiedTime is left alone, so the next check reads the file again.
            log.warn("Access file changed while it was being read; the previous rules stay "
                    + "active until it can be read in one piece");
            return;
        }

        if (!initial && result.errorCount() > 0) {
            // Lines that fail to parse are dropped. Applying the rest could silently
            // remove a DENY rule, so the last rule set that parsed cleanly stays.
            log.error("Keeping the previous access rules: {} line(s) in {} could not be parsed",
                    result.errorCount(), file.getPath());
            lastLoadModifiedTime = modifiedAfter;
            return;
        }

        ruleStore.update(result);
        lastLoadModifiedTime = modifiedBefore;
        log.info("Loaded {} rules ({} errors)", result.totalRules(), result.errorCount());
        warnAboutPrivilegedLoopbackRules(result);
    }

    /**
     * A relay, proxy or port forwarder on the same host makes every client arrive
     * from localhost, so a privileged rule for localhost hands that privilege to
     * all of them.
     *
     * @param result
     *            the rule set that was just loaded
     */
    private void warnAboutPrivilegedLoopbackRules(ParseResult result) {
        for (AddressRule rule : result.userRules()) {
            if (rule.getAccessLevel() > AccessManager.ACCESS_NORMAL
                    && (rule.matches(IPV4_LOOPBACK) || rule.matches(IPV6_LOOPBACK))) {
                log.warn("Access rule \"{}\" gives {} access to connections from localhost. "
                        + "Every client comes from localhost when the server is reached through "
                        + "a relay, proxy or port forwarder on the same host.", rule,
                        AccessManager.getAccessName(rule.getAccessLevel()));
            }
        }
    }

    @Override
    public void addTempBan(String addressPattern, int minutes) {
        tempStore.addBan(addressPattern, minutes);
    }

    @Override
    public void addTempAdmin(String addressPattern, int minutes) {
        tempStore.addAdmin(addressPattern, minutes);
    }

    @Override
    public void addSilenced(String addressPattern, int minutes) {
        tempStore.addSilence(addressPattern, minutes);
    }

    @Override
    public synchronized String getAnnouncement(InetAddress address) {
        return ruleStore.getUserAnnouncement(address.getHostAddress());
    }

    @Override
    public synchronized int getAccess(InetAddress address) {
        String userAddress = address.getHostAddress();

        // A temporary ban counts here as well as in isAddressAllowed; otherwise the
        // other sessions an address already has keep their normal access.
        if (tempStore.isBanned(userAddress)) {
            return AccessManager.ACCESS_BANNED;
        }

        if (tempStore.hasTemporaryAdmin(userAddress)) {
            return AccessManager.ACCESS_ADMIN;
        }

        return ruleStore.getUserAccessLevel(userAddress);
    }

    @Override
    public synchronized boolean clearTemp(InetAddress address) {
        return tempStore.clearForAddress(address.getHostAddress());
    }

    @Override
    public synchronized boolean isSilenced(InetAddress address) {
        return tempStore.isSilenced(address.getHostAddress());
    }

    @Override
    public synchronized boolean isAddressAllowed(InetAddress address) {
        String userAddress = address.getHostAddress();

        if (tempStore.isBanned(userAddress)) {
            return false;
        }

        return ruleStore.isAddressAllowed(userAddress);
    }

    @Override
    public synchronized boolean isEmulatorAllowed(String emulator) {
        return ruleStore.isEmulatorAllowed(emulator);
    }

    @Override
    public synchronized boolean isGameAllowed(String game) {
        return ruleStore.isGameAllowed(game);
    }
}
