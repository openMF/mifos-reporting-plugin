/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mifos.birt")
public class BirtPluginProperties {

  // FINERACT_BIRT_REPORTS_PATH
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
}
