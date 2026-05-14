/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fineract.birt")
public class BirtPluginProperties {

    private String reportsPath;           // FINERACT_BIRT_REPORTS_PATH
    private String defaultLocale = "en";
    private boolean embedHtml = true;
    private int cacheTtlMinutes = 60;     // for report design cache
}