/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mifos.birt")
public class BirtPluginProperties {

    /** HikariCP's own floor for a connection or validation timeout. */
    private static final long MINIMUM_TIMEOUT_MILLIS = 250L;

    // Path containing reports
    private String reportsPath;
    // Default Locale
    private String defaultLocale = "en";
    // Default Output Type
    private boolean embedHtml = true;
    // For report design cache
    private int cacheTtlMinutes = 60;
    // Directory containing custom TTF fonts
    private String fontsPath;
    // Optional Custom fontsConfig.xml location
    private String fontsConfigPath;
    // Connections the tenant's read-only report principal may hold at once
    private int readOnlyPoolMaxSize = 5;
    // How long a report waits for one of them before failing
    private long readOnlyConnectionTimeoutMillis = 10_000L;

    /**
     * Both values are handed to HikariCP, which refuses a maximum pool size below 1 and any of its
     * timeouts below 250ms. Left to Hikari these fail when the first report builds the pool, which
     * is a running deployment discovering its configuration is wrong; failing here is the same
     * refusal at startup instead.
     *
     * <p>Hikari reads a connection timeout of 0 as "wait forever", but the same value also becomes
     * the pool's validation timeout, which has no such reading, so 0 is refused here too.
     */
    @PostConstruct
    void validate() {
        if (readOnlyPoolMaxSize < 1) {
            throw new IllegalStateException(
                    "mifos.birt.read-only-pool-max-size must be at least 1, but is " + readOnlyPoolMaxSize);
        }
        if (readOnlyConnectionTimeoutMillis < MINIMUM_TIMEOUT_MILLIS) {
            throw new IllegalStateException("mifos.birt.read-only-connection-timeout-millis must be at least "
                    + MINIMUM_TIMEOUT_MILLIS + "ms, but is " + readOnlyConnectionTimeoutMillis);
        }
    }
}
