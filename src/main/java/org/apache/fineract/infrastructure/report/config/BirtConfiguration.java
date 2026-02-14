/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
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
public class BirtConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(BirtConfiguration.class);
  private IReportEngine reportEngine;

  @PostConstruct
  public void startBirtEngine() {
    try {
      LOG.info("Initializing Eclipse BIRT Platform...");

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
      LOG.info("Eclipse BIRT Platform started successfully. Engine is ready.");

    } catch (BirtException e) {
      LOG.error("Failed to start Eclipse BIRT Engine. Reports will not function.", e);
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
      LOG.info("Destroying BIRT Report Engine...");
      reportEngine.destroy();
    }
    LOG.info("Shutting down BIRT Platform...");
    Platform.shutdown();
    LOG.info("BIRT Platform shutdown complete.");
  }
}
