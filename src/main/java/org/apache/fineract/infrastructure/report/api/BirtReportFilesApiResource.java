/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.report.data.BirtReportFileUploadData;
import org.apache.fineract.infrastructure.report.service.BirtReportUploadService;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.springframework.stereotype.Component;

/**
 * Installs Eclipse BIRT® report designs into the authenticated tenant's reports directory.
 *
 * <p>Registered automatically: Apache Fineract®'s {@code JerseyConfig} registers every Spring bean
 * annotated {@code @Path}, and this plugin is inside its component scan, so no wiring in the
 * platform is needed.
 *
 * <p>The request carries a file and nothing else. There is deliberately no parameter for the
 * destination, the tenant or any directory: those are the server's to decide.
 */
@Path("/v1/birt/reports")
@Component
@Tag(
        name = "BIRT Report Files",
        description = "Upload the Eclipse BIRT report designs (.rptdesign) a tenant's reports run from.")
@RequiredArgsConstructor
public class BirtReportFilesApiResource {

    private final BirtReportUploadService uploadService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Upload a BIRT report design", description = """
                    Stores a .rptdesign file in the reports directory of the tenant the request authenticated \
                    as, replacing any design already stored under the same name.

                    Body part:

                    file : the .rptdesign to install

                    Requires the CREATE_REPORT permission. Registering the report in the report catalogue is \
                    a separate step; this resource only installs the design file.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "OK",
                content = @Content(schema = @Schema(implementation = BirtReportFileUploadData.class))),
        @ApiResponse(responseCode = "400", description = "The file is not a valid BIRT report design"),
        @ApiResponse(responseCode = "403", description = "The user does not have the CREATE_REPORT permission")
    })
    public BirtReportFileUploadData uploadReportDesign(
            @FormDataParam("file") final InputStream inputStream,
            @FormDataParam("file") final FormDataContentDisposition fileDetails) {

        /*
         * No Content-Length check here. It measures the whole request rather
         * than the file, and a client sets it, so the size limit belongs where
         * the bytes are actually counted, in the upload service.
         */
        return uploadService.upload(fileDetails == null ? null : fileDetails.getFileName(), inputStream);
    }
}
