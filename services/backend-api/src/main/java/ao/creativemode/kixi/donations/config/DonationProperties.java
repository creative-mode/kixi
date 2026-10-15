package ao.creativemode.kixi.donations.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limits and secrets of the public donation form (issue #252). Bound to {@code app.donations.*}
 * ({@code APP_DONATIONS_*} in the environment).
 *
 * <p>The numbers are the abuse budget of an endpoint anyone on the internet can call: a donor
 * sending a stack of exam photos is well inside it, a script is not.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.donations")
public class DonationProperties {

    /**
     * Mixed into the hash of the caller's address so the stored {@code client_hash} cannot be
     * reversed by hashing the whole IPv4 space. Left blank it falls back to a value derived from the
     * JWT secret, which is stable across restarts and never leaves the server.
     */
    private String pepper = "";

    /** The text version the donor consents to; bump it when the consent wording changes. */
    private String consentVersion = "2026-10-v1";

    private int maxFiles = 10;
    private long maxFileBytes = 15L * 1024 * 1024;
    private long maxTotalBytes = 50L * 1024 * 1024;

    /** Submissions per client per hour and per day, and one ceiling for the whole platform per day. */
    private int perHour = 5;
    private int perDay = 20;
    private int globalPerDay = 300;

    /** A form filled faster than this is a script: it gets a convincing 201 and nothing is stored. */
    private long minFillSeconds = 3;

    /** A scanned exam has a handful of pages; a PDF with more than this is not one. */
    private int maxPdfPages = 40;

    /** Decoded pixels above this are refused: a small PNG can inflate into gigabytes of memory. */
    private long maxImagePixels = 100_000_000L;

    public String getPepper() { return pepper; }
    public void setPepper(String pepper) { this.pepper = pepper == null ? "" : pepper; }

    public String getConsentVersion() { return consentVersion; }
    public void setConsentVersion(String consentVersion) { this.consentVersion = consentVersion; }

    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }

    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }

    public long getMaxTotalBytes() { return maxTotalBytes; }
    public void setMaxTotalBytes(long maxTotalBytes) { this.maxTotalBytes = maxTotalBytes; }

    public int getPerHour() { return perHour; }
    public void setPerHour(int perHour) { this.perHour = perHour; }

    public int getPerDay() { return perDay; }
    public void setPerDay(int perDay) { this.perDay = perDay; }

    public int getGlobalPerDay() { return globalPerDay; }
    public void setGlobalPerDay(int globalPerDay) { this.globalPerDay = globalPerDay; }

    public long getMinFillSeconds() { return minFillSeconds; }
    public void setMinFillSeconds(long minFillSeconds) { this.minFillSeconds = minFillSeconds; }

    public int getMaxPdfPages() { return maxPdfPages; }
    public void setMaxPdfPages(int maxPdfPages) { this.maxPdfPages = maxPdfPages; }

    public long getMaxImagePixels() { return maxImagePixels; }
    public void setMaxImagePixels(long maxImagePixels) { this.maxImagePixels = maxImagePixels; }
}
