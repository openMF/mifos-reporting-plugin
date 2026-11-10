/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.data.ClientIdentifierData;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.client.service.ClientIdentifierReadPlatformService;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class KycReportDataServiceTest {
    private final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    private final AppUser user = mock(AppUser.class);
    private final ClientReadPlatformService clients = mock(ClientReadPlatformService.class);
    private final ClientIdentifierReadPlatformService identifiers = mock(ClientIdentifierReadPlatformService.class);
    private final KycReportRepository repository = mock(KycReportRepository.class);
    private final KycReportDataService service = new KycReportDataService(context, clients, identifiers, repository);

    @BeforeEach
    void setup() {
        when(context.authenticatedUser()).thenReturn(user);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
    }

    @AfterEach
    void clearTenant() {
        ThreadLocalContextUtil.setTenant(null);
    }

    private ClientData client(long legalForm) {
        var client = new ClientData();
        client.setId(7L);
        client.setDisplayName("María Müller <script>alert(1)</script>");
        client.setAccountNo("000000007");
        client.setLegalForm(new EnumOptionData(legalForm, "", ""));
        return client;
    }

    static MultivaluedHashMap<String, String> params(String id) {
        var params = new MultivaluedHashMap<String, String>();
        params.add("R_clientId", id);
        params.add("output-type", "PDF");
        return params;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "01", "1.0", "1 OR 1=1", "9223372036854775808", " 1", "1 ", "١"})
    void rejectsInvalidClientIds(String id) {
        assertThrows(BadRequestException.class, () -> KycReportDataService.validateParameters(params(id)));
        verifyNoInteractions(clients, identifiers, repository);
    }

    @Test
    void rejectsMissingDuplicateAndUnknownParameters() {
        assertThrows(BadRequestException.class, () -> KycReportDataService.validateParameters(null));
        assertThrows(
                BadRequestException.class, () -> KycReportDataService.validateParameters(new MultivaluedHashMap<>()));
        var p = params("7");
        p.add("R_clientId", "8");
        assertThrows(BadRequestException.class, () -> KycReportDataService.validateParameters(p));
        var unknown = params("7");
        unknown.add("R_userhierarchy", ".");
        assertThrows(BadRequestException.class, () -> KycReportDataService.validateParameters(unknown));
    }

    @Test
    void supportsPdfOnlyAndDefaultsToPdf() {
        var p = params("9223372036854775807");
        assertEquals(Map.of("clientId", "9223372036854775807"), KycReportDataService.validateParameters(p));
        p.remove("output-type");
        assertEquals(
                "9223372036854775807",
                KycReportDataService.validateParameters(p).get("clientId"));
        for (String output : List.of("HTML", "", " PDF", "CSV")) {
            p.putSingle("output-type", output);
            assertThrows(BadRequestException.class, () -> KycReportDataService.validateParameters(p));
        }
        p.putSingle("output-type", "PDF");
        p.add("output-type", "PDF");
        assertThrows(BadRequestException.class, () -> KycReportDataService.validateParameters(p));
    }

    @Test
    void rejectsMissingReportAndClientPermissionsBeforeDataAccess() {
        when(user.hasNotPermissionForReport("KYCNaturalPerson")).thenReturn(true);
        assertThrows(NoAuthorizationException.class, () -> service.snapshot("KYCNaturalPerson", 7));
        verifyNoInteractions(clients, identifiers, repository);
        reset(user);
        doThrow(new NoAuthorizationException("denied")).when(user).validateHasReadPermission("CLIENT");
        assertThrows(NoAuthorizationException.class, () -> service.snapshot("KYCNaturalPerson", 7));
        verifyNoInteractions(clients, identifiers, repository);
    }

    @Test
    void requiresAuthenticatedTenant() {
        ThreadLocalContextUtil.setTenant(null);
        assertThrows(NoAuthorizationException.class, () -> service.snapshot("KYCNaturalPerson", 7));
        verifyNoInteractions(clients, identifiers, repository);
    }

    @Test
    void inaccessibleClientStopsAllKycReads() {
        when(clients.retrieveOne(7L)).thenThrow(new ClientNotFoundException(7L));
        assertThrows(ClientNotFoundException.class, () -> service.snapshot("KYCNaturalPerson", 7));
        verifyNoInteractions(identifiers, repository);
    }

    @ParameterizedTest
    @CsvSource({"KYCNaturalPerson,2", "KYCLegalPerson,1", "KYCNaturalPerson,3"})
    void rejectsLegalFormMismatch(String report, long form) {
        when(clients.retrieveOne(7L)).thenReturn(client(form));
        assertThrows(BadRequestException.class, () -> service.snapshot(report, 7));
        verifyNoInteractions(identifiers, repository);
    }

    @Test
    void rejectsUnclassifiedClient() {
        when(clients.retrieveOne(7L)).thenReturn(new ClientData());
        assertThrows(BadRequestException.class, () -> service.snapshot("KYCNaturalPerson", 7));
        verifyNoInteractions(identifiers, repository);
    }

    @ParameterizedTest
    @CsvSource({"KYCNaturalPerson,1,dt_kyc_validation", "KYCLegalPerson,2,dt_kyc_validation_entity"})
    void readsStoredFactsForCorrectLegalForm(String report, long form, String table) {
        when(clients.retrieveOne(7L)).thenReturn(client(form));
        when(repository.latestVerification(7)).thenReturn(Map.of("kyc_status", "PENDING"));
        when(repository.validation(table, 7))
                .thenReturn(Map.of("validation_status", "INCOMPLETE", "nid_missing", 1, "nid_selected", false));
        when(identifiers.retrieveClientIdentifiers(7L))
                .thenReturn(List.of(ClientIdentifierData.singleItem(
                        3L, 7L, null, "ABC123", "", "Active", LocalDate.of(2025, 1, 1), null)));
        String text = service.snapshot(report, 7).toString();
        assertTrue(text.contains("ABC123"));
        assertTrue(text.contains("PENDING"));
        assertTrue(text.contains("INCOMPLETE"));
        assertTrue(text.contains("Yes"));
        assertTrue(text.contains("No"));
        assertTrue(text.contains("Not recorded"));
        assertFalse(text.contains("APPROVED"));
        verify(repository).validation(table, 7);
    }

    @Test
    void missingDataNeverImpliesSuccess() {
        when(clients.retrieveOne(7L)).thenReturn(client(1));
        String text = service.snapshot("KYCNaturalPerson", 7).toString();
        assertTrue(text.contains("Not recorded"));
        assertTrue(text.contains("No identity documents recorded"));
        assertTrue(text.contains("No document validation recorded"));
        assertFalse(text.contains("Yes"));
        assertFalse(text.contains("COMPLETE"));
    }

    @Test
    void deniedSectionsAreNotQueried() {
        when(clients.retrieveOne(7L)).thenReturn(client(1));
        when(user.hasNotPermissionForAnyOf(any(String[].class))).thenReturn(true);
        when(user.hasNotPermissionForDatatable("dt_kyc_validation", "READ")).thenReturn(true);
        String text = service.snapshot("KYCNaturalPerson", 7).toString();
        assertTrue(text.contains("identity read permission is required"));
        assertTrue(text.contains("validation read permission is required"));
        verifyNoInteractions(identifiers);
        verify(repository, never()).validation(anyString(), anyLong());
    }

    @Test
    void snapshotsDoNotRetainAnotherTenantsClientWithSameId() {
        when(clients.retrieveOne(7L)).thenAnswer(invocation -> {
            var client = client(1);
            client.setDisplayName(ThreadLocalContextUtil.getTenant().getTenantIdentifier());
            return client;
        });
        var first = service.snapshot("KYCNaturalPerson", 7);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(2L, "second", "Second", "UTC", null));
        var second = service.snapshot("KYCNaturalPerson", 7);
        assertTrue(first.toString().contains("value=default"));
        assertFalse(second.toString().contains("value=default"));
        assertTrue(second.toString().contains("value=second"));
        verify(clients, times(2)).retrieveOne(7L);
        verify(repository, times(2)).latestVerification(7);
    }
}
