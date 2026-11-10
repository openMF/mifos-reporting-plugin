/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.service.ClientIdentifierReadPlatformService;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.springframework.stereotype.Component;

/**
 * Supplies an execution-local snapshot to the native BIRT KYC designs. This is deliberately not a
 * ReportingProcessService: both reports use the existing BIRT registration and PDF emitter.
 */
@Component
@RequiredArgsConstructor
public class KycReportDataService {

    public static final String APP_CONTEXT_KEY = "mifos.kyc.rows";
    private final PlatformSecurityContext context;
    private final ClientReadPlatformService clients;
    private final ClientIdentifierReadPlatformService identifiers;
    private final KycReportRepository repository;

    public static boolean isKycReport(String reportName) {
        return "KYCNaturalPerson".equals(reportName) || "KYCLegalPerson".equals(reportName);
    }

    public static Map<String, String> validateParameters(MultivaluedMap<String, String> params) {
        if (params == null
                || params.get("R_clientId") == null
                || params.get("R_clientId").size() != 1
                || params.getFirst("R_clientId") == null
                || !params.getFirst("R_clientId").matches("[1-9][0-9]{0,18}")) {
            throw new BadRequestException("A single positive R_clientId is required");
        }
        try {
            Long.parseLong(params.getFirst("R_clientId"));
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid R_clientId");
        }
        if (params.keySet().stream().anyMatch(key -> key.startsWith("R_") && !key.equals("R_clientId"))) {
            throw new BadRequestException("Unknown KYC report parameter");
        }
        if (params.containsKey("output-type")
                && (params.get("output-type") == null
                        || params.get("output-type").size() != 1
                        || !"PDF".equalsIgnoreCase(params.getFirst("output-type")))) {
            throw new BadRequestException("KYC reports support PDF output only");
        }
        return Map.of("clientId", params.getFirst("R_clientId"));
    }

    public List<Map<String, String>> snapshot(String reportName, long clientId) {
        if (!isKycReport(reportName) || clientId <= 0) {
            throw new BadRequestException("Invalid KYC report or client ID");
        }
        var user = context.authenticatedUser();
        user.validateHasReadPermission("CLIENT");
        if (user.hasNotPermissionForReport(reportName)) {
            throw new NoAuthorizationException("Not authorized to read this report");
        }
        // No fallback tenant, global cache or caller-supplied connection is allowed for KYC reads.
        if (ThreadLocalContextUtil.getTenant() == null) {
            throw new NoAuthorizationException("A tenant context is required for KYC reporting");
        }
        // Core retrieveOne enforces the current office and transfer-office hierarchy, using the
        // tenant-routed repositories. Do this before touching identifiers or optional KYC tables.
        var client = clients.retrieveOne(clientId);
        boolean entity = "KYCLegalPerson".equals(reportName);
        Long legalForm =
                client.getLegalForm() == null ? null : client.getLegalForm().getId();
        if (legalForm == null || legalForm != (entity ? 2L : 1L)) {
            throw new BadRequestException("Report does not match the client's legal form");
        }

        List<Map<String, String>> rows = new ArrayList<>();
        add(rows, "Report", "Generated (UTC)", Instant.now());
        add(rows, "Client information", "Client name", client.getDisplayName());
        add(rows, "Client information", "Client ID", client.getId());
        add(rows, "Client information", "Account number", client.getAccountNo());
        add(rows, "Client information", "Legal form", entity ? "ENTITY" : "PERSON");
        add(rows, "Client information", "Office", client.getOfficeName());
        add(
                rows,
                "Client information",
                "Status",
                client.getStatus() == null ? null : client.getStatus().getValue());
        add(rows, "Client information", "Activation date", client.getActivationDate());
        add(rows, "Client information", "Date of birth", client.getDateOfBirth());
        add(rows, "Client information", "Mobile number", client.getMobileNo());
        add(rows, "Client information", "Email", client.getEmailAddress());
        if (entity) {
            var details = client.getClientNonPersonDetails();
            add(rows, "Client information", "Incorporation number", details == null ? null : details.getIncorpNumber());
            add(
                    rows,
                    "Client information",
                    "Incorporation valid until",
                    details == null ? null : details.getIncorpValidityTillDate());
        }

        if (user.hasNotPermissionForAnyOf("ALL_FUNCTIONS", "ALL_FUNCTIONS_READ", "READ_CLIENTIDENTIFIER")) {
            add(rows, "Identity documents", "Availability", "Not included: identity read permission is required.");
        } else {
            var identities = identifiers.retrieveClientIdentifiers(clientId);
            if (identities.isEmpty()) {
                add(rows, "Identity documents", "Availability", "No identity documents recorded.");
            }
            int number = 0;
            for (var identity : identities) {
                String section = "Identity document " + ++number;
                add(
                        rows,
                        section,
                        "Type",
                        identity.getDocumentType() == null
                                ? null
                                : identity.getDocumentType().getName());
                add(rows, section, "Document key", identity.getDocumentKey());
                add(rows, section, "Status", identity.getStatus());
                add(rows, section, "Issued", identity.getIssuanceDate());
                add(rows, section, "Expires", identity.getExpiryDate());
            }
        }

        var verification = repository.latestVerification(clientId);
        add(rows, "External KYC verification", "Latest saved status", verification.get("kyc_status"));
        add(rows, "External KYC verification", "Recorded at (UTC)", verification.get("created_on_utc"));

        String table = entity ? "dt_kyc_validation_entity" : "dt_kyc_validation";
        if (user.hasNotPermissionForDatatable(table, "READ")) {
            add(rows, "Document validation", "Availability", "Not included: validation read permission is required.");
        } else {
            var validation = repository.validation(table, clientId);
            if (validation.isEmpty()) {
                add(rows, "Document validation", "Availability", "No document validation recorded.");
            } else {
                add(rows, "Document validation", "Saved validation status", validation.get("validation_status"));
                String[] keys = {"nid", "legal_id", "proof", "score"};
                String[] labels = {"National ID", "Legal ID", "Address proof", "Credit score"};
                for (int i = 0; i < keys.length; i++) {
                    for (String check : List.of("selected", "missing", "illegible", "invalid", "expired")) {
                        add(rows, labels[i], check, flag(validation.get(keys[i] + "_" + check)));
                    }
                }
            }
        }
        return List.copyOf(rows);
    }

    private static String flag(Object value) {
        if (Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value))) return "Yes";
        if (Boolean.FALSE.equals(value) || "0".equals(String.valueOf(value))) return "No";
        return value == null ? null : value.toString();
    }

    private static void add(List<Map<String, String>> rows, String section, String label, Object value) {
        rows.add(Map.of(
                "section",
                section,
                "label",
                label,
                "value",
                value == null || value.toString().isBlank() ? "Not recorded" : value.toString()));
    }
}
