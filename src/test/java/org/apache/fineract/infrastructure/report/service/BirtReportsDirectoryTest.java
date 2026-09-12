/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BirtReportsDirectory Tests")
class BirtReportsDirectoryTest {

    private static final String TENANT_A = "tenantA";
    private static final String TENANT_B = "tenantB";

    @Mock
    private BirtPluginProperties birtProperties;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BirtReportsDirectory reportsDirectory;

    @TempDir
    Path baseDir;

    @BeforeEach
    void setUp() {
        when(birtProperties.getReportsPath()).thenReturn(baseDir.toString());
        reportsDirectory = new BirtReportsDirectory(birtProperties, jdbcTemplate);
        setTenant(TENANT_A);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.clearTenant();
    }

    private void setTenant(String identifier) {
        ThreadLocalContextUtil.setTenant(
                FineractPlatformTenant.builder().tenantIdentifier(identifier).build());
    }

    @Test
    @DisplayName("Writes go to a directory named for the authenticated tenant")
    void writeGoesToTheTenantDirectory() throws Exception {
        Path target = reportsDirectory.resolveForWrite("Active Loans.rptdesign");

        assertEquals(baseDir.toRealPath().resolve(TENANT_A).resolve("Active Loans.rptdesign"), target);
        assertTrue(Files.isDirectory(baseDir.resolve(TENANT_A)), "the tenant directory should have been created");
    }

    @Test
    @DisplayName("Two tenants resolve to two different destinations for the same file name")
    void twoTenantsGetSeparateDestinations() throws Exception {
        Path forTenantA = reportsDirectory.resolveForWrite("Active Loans.rptdesign");

        setTenant(TENANT_B);
        Path forTenantB = reportsDirectory.resolveForWrite("Active Loans.rptdesign");

        assertFalse(forTenantA.equals(forTenantB), "each tenant must get its own destination");
        assertTrue(forTenantB.startsWith(baseDir.toRealPath().resolve(TENANT_B)));
    }

    @ParameterizedTest(name = "refuses to write to \"{0}\"")
    @ValueSource(
            strings = {
                "../escaped.rptdesign",
                "../../escaped.rptdesign",
                "nested/escaped.rptdesign",
                "/etc/escaped.rptdesign"
            })
    @DisplayName("A name that leaves the tenant directory is refused")
    void refusesNamesLeavingTheTenantDirectory(String fileName) {
        assertThrows(IllegalArgumentException.class, () -> reportsDirectory.resolveForWrite(fileName));
    }

    @Test
    @DisplayName("A read finds the tenant's own design before the shared one")
    void readPrefersTheTenantDesign() throws Exception {
        Files.writeString(baseDir.resolve("Active Loans.rptdesign"), "shared");
        Path tenantDesign = Files.createDirectories(baseDir.resolve(TENANT_A)).resolve("Active Loans.rptdesign");
        Files.writeString(tenantDesign, "tenant");

        assertEquals(
                Optional.of(tenantDesign.toRealPath()), reportsDirectory.resolveExisting("Active Loans.rptdesign"));
    }

    @Test
    @DisplayName("A read falls back to the shared directory for designs installed by hand")
    void readFallsBackToTheSharedDirectory() throws Exception {
        Path shared = baseDir.resolve("Active Loans.rptdesign");
        Files.writeString(shared, "shared");

        assertEquals(Optional.of(shared.toRealPath()), reportsDirectory.resolveExisting("Active Loans.rptdesign"));
    }

    @Test
    @DisplayName("One tenant cannot read another tenant's design")
    void oneTenantCannotReadAnothersDesign() throws Exception {
        Path tenantADesign = Files.createDirectories(baseDir.resolve(TENANT_A)).resolve("Private.rptdesign");
        Files.writeString(tenantADesign, "tenant A only");

        setTenant(TENANT_B);

        assertEquals(Optional.empty(), reportsDirectory.resolveExisting("Private.rptdesign"));
        assertEquals(Optional.empty(), reportsDirectory.resolveExisting("../" + TENANT_A + "/Private.rptdesign"));
    }

    /**
     * The shared directory holds the tenant directories, so a nested name read from it names another
     * tenant: confining the result to the shared directory is not enough, the name has to land
     * directly in the directory it was resolved against.
     */
    @Test
    @DisplayName("A nested name naming another tenant's directory is refused")
    void refusesANestedNameNamingAnotherTenant() throws Exception {
        Path tenantADesign = Files.createDirectories(baseDir.resolve(TENANT_A)).resolve("Private.rptdesign");
        Files.writeString(tenantADesign, "tenant A only");

        setTenant(TENANT_B);

        assertEquals(Optional.empty(), reportsDirectory.resolveExisting(TENANT_A + "/Private.rptdesign"));
    }

    @Test
    @DisplayName("The base directory comes from the BIRT external service configuration first")
    void baseDirectoryPrefersTheExternalServiceConfiguration() throws Exception {
        Path configured = Files.createDirectories(baseDir.resolve("configured"));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(configured.toString());

        assertEquals(configured, reportsDirectory.base());
    }

    @Test
    @DisplayName("A missing design resolves to nothing")
    void missingDesignResolvesToNothing() {
        assertEquals(Optional.empty(), reportsDirectory.resolveExisting("Nowhere.rptdesign"));
    }

    @Test
    @DisplayName("Refuses to resolve anything when no tenant is bound to the thread")
    void refusesWithoutATenant() {
        ThreadLocalContextUtil.clearTenant();

        assertThrows(IllegalStateException.class, () -> reportsDirectory.resolveForWrite("Active Loans.rptdesign"));
    }
}
