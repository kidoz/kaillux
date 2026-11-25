package su.kidoz.kaillera.release;

import su.kidoz.release.ReleaseInfo;
import su.kidoz.util.EmuUtil;

/**
 * Provides release and build information for the Kaillux project. This class
 * also formats a welcome message for printing at server startup.
 */
public final class KailleraServerReleaseInfo implements ReleaseInfo {
    private static final String PRODUCT_NAME = "Kaillux Kaillera Server";

    private static final int MAJOR_VERSION = 1;
    private static final int MINOR_VERSION = 4;
    private static final int BUILD_NUMBER = 0;

    private static final String RELEASE_DATE = "10/21/2026";
    private static final String LICENSE_INFO = "Usage of this software is subject to the terms found in the included license";
    private static final String WEBSITE = "https://github.com/kidoz/kaillux";

    public KailleraServerReleaseInfo() {
        // generic constructor
    }

    public String getProductName() {
        return PRODUCT_NAME;
    }

    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    public String getReleaseDate() {
        return RELEASE_DATE;
    }

    public String getLicenseInfo() {
        return LICENSE_INFO;
    }

    public String getWebsiteString() {
        return WEBSITE;
    }

    public int getBuildNumber() {
        // TODO: modify this to pull from an Ant build version file
        return BUILD_NUMBER;
    }

    /**
     * Returns the version number for the Kaillux server in the form
     * <p>
     * <i>major</i>.<i>minor</i>
     * </p>
     */
    public String getVersionString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getMajorVersion());
        sb.append(".");
        sb.append(getMinorVersion());
        sb.append(".");
        sb.append(getBuildNumber());
        return sb.toString();
    }

    /**
     * Formats release information into a welcome message. This message is printed
     * by the server at server startup.
     */
    public String getWelcome() {
        StringBuilder sb = new StringBuilder();
        sb.append("// " + getProductName() + " version " + getVersionString() + " ("
                + getReleaseDate() + ") ");
        sb.append(EmuUtil.LB);
        sb.append("// " + getLicenseInfo());
        sb.append(EmuUtil.LB);
        sb.append("// For the most up-to-date information please visit: " + getWebsiteString());
        return sb.toString();
    }
}
