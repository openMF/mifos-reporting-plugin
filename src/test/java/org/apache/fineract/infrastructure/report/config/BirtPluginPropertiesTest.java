/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pool settings reach HikariCP, which refuses them below its own floors. Refusing them here
 * means a deployment finds out at startup rather than when someone runs the first report.
 */
@DisplayName("BirtPluginProperties Tests")
class BirtPluginPropertiesTest {

    @Test
    @DisplayName("Should accept the shipped defaults")
    void shouldAcceptTheDefaults() {
        assertDoesNotThrow(() -> new BirtPluginProperties().validate());
    }

    @Test
    @DisplayName("Should refuse a pool that cannot hold a connection")
    void shouldRefuseAPoolSizeBelowOne() {
        final BirtPluginProperties properties = new BirtPluginProperties();
        properties.setReadOnlyPoolMaxSize(0);

        final IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(ex.getMessage().contains("mifos.birt.read-only-pool-max-size"), ex.getMessage());
    }

    /** Hikari reads 0 as "wait forever" for a connection timeout, but refuses it as a validation one. */
    @Test
    @DisplayName("Should refuse a timeout below Hikari's floor, zero included")
    void shouldRefuseATimeoutBelowTheFloor() {
        final BirtPluginProperties properties = new BirtPluginProperties();
        properties.setReadOnlyConnectionTimeoutMillis(249L);

        final IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(ex.getMessage().contains("mifos.birt.read-only-connection-timeout-millis"), ex.getMessage());

        properties.setReadOnlyConnectionTimeoutMillis(0L);
        assertThrows(IllegalStateException.class, properties::validate);

        properties.setReadOnlyConnectionTimeoutMillis(250L);
        assertDoesNotThrow(properties::validate);
    }
}
