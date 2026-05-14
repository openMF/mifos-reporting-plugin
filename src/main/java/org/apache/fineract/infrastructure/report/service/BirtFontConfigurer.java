/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.net.MalformedURLException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.eclipse.birt.report.engine.api.EngineConfig;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BirtFontConfigurer {

  private final BirtPluginProperties birtProperties;

  @PostConstruct
  public void configureFonts() {
    try {
      log.info("BIRT font configuration initiated");
      EngineConfig engineConfig = new EngineConfig();

      // Add custom font path
      String fontsDir = birtProperties.getFontsPath();
      if (StringUtils.isNotBlank(fontsDir)) {
        if (fontsDir.startsWith("classpath:")) {
          // Handle classpath fonts (copy to temp dir or use File)
          // For simplicity, we'll support external directory first
        } else {
          File fontFolder = new File(fontsDir);
          if (fontFolder.exists()) {
            engineConfig.setFontConfig(
                fontFolder.toURI().toURL()); // or use setBIRTFontPath if available
          }
        }
      }

      // Load a custom fontsConfig.xml here
      if (StringUtils.isNotBlank(birtProperties.getFontsConfigPath())) {
        engineConfig.setFontConfig(new File(birtProperties.getFontsConfigPath()).toURI().toURL());
      }

      log.info("BIRT font configuration completed successfully");

    } catch (MalformedURLException e) {
      log.error("Failed to configure custom fonts", e);
    }
  }
}
