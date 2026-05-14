/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.logging.Level;
import org.eclipse.birt.core.exception.BirtException;
import org.eclipse.birt.core.framework.Platform;
import org.eclipse.birt.report.engine.api.EngineConfig;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportEngineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures and manages the lifecycle of the Eclipse BIRT Reporting Engine.
 *
 * <p>This class ensures the Platform is started exactly once when the application boots and shut
 * down gracefully when the application stops, preventing memory leaks.
 */
@Configuration
public class BirtEngineConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(BirtEngineConfiguration.class);
  private IReportEngine reportEngine;

  @PostConstruct
  public void startBirtEngine() {
    try {
      logger.info("Initializing Eclipse BIRT Platform...");

      EngineConfig config = new EngineConfig();
      // Redirect BIRT internal logging to prevent console spam.
      config.setLogConfig(null, Level.WARNING);

      // Start the BIRT Platform
      Platform.startup(config);

      // Report Engine Factory
      IReportEngineFactory factory =
          (IReportEngineFactory)
              Platform.createFactoryObject(
                  IReportEngineFactory.EXTENSION_REPORT_ENGINE_FACTORY); //

      // Create the Engine instance
      reportEngine = factory.createReportEngine(config);
      logger.info("Eclipse BIRT Platform started successfully. Engine is ready.");

    } catch (BirtException e) {
      logger.error("Failed to start Eclipse BIRT Engine. Reports will not function.", e);
      // We consciously do NOT throw a RuntimeException here.
      // As, if BIRT fails, Fineract should still start up for other operations.
    }
  }

  /**
   * Exposes the BIRT Report Engine as a Spring Bean. This allows the Service Implementation to
   * simply @Autowire it.
   */
  @Bean
  public IReportEngine reportEngine() {
    return reportEngine;
  }

  @PreDestroy
  public void stopBirtEngine() {
    if (reportEngine != null) {
      logger.info("Destroying BIRT Report Engine...");
      reportEngine.destroy();
    }
    logger.info("Shutting down BIRT Platform...");
    Platform.shutdown();
    logger.info("BIRT Platform shutdown complete.");
  }
}
