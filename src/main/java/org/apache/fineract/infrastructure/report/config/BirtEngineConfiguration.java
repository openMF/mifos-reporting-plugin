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
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
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

  @Bean
  public static BeanFactoryPostProcessor primaryBeanFactoryPostProcessor() {
    return beanFactory -> {
      // Check if the BeanFactory supports bean definition manipulation
      if (beanFactory instanceof BeanDefinitionRegistry registry) {
        String beanName = "birtReportingProcessServiceImpl";

        // If the plugin bean is registered, force it to be primary
        if (registry.containsBeanDefinition(beanName)) {
          registry.getBeanDefinition(beanName).setPrimary(true);
        }
      }
    };
  }

  @PostConstruct
  public void startBirtEngine() {
    try {
      logger.info("Initializing Mifos X Reporting Plugin...  ");
      logger.info("                                          ");      
      logger.info("                                          ");      
      logger.info("        ..................                ");
      logger.info("      ....:-=+*********++=:.              ");
      logger.info("     . ....=************+=.......         ");
      logger.info("   ...-....=**********+-.....:=++-.       ");
      logger.info("  ..-++:...:*********:.....-+*****+:..    ");
      logger.info(" ..=***-....+******-.....-+*********-.    ");
      logger.info(" .=****+....-****=.....-+************-..  ");
      logger.info(" -******=....=*+:....:+***************:.  ");
      logger.info(".+*******:....:.....+*****************+.  ");
      logger.info(".*********:.......-********************:. ");
      logger.info(".**********:.....=*********************:. ");
      logger.info(".**********......-+********************:. ");
      logger.info("=*******+.........=*******************:.  ");
      logger.info("..+*****+....:=:.....+****************:.  ");
      logger.info("...+****:....+**=:.....=*************-.   ");
      logger.info(". ..=**-....=*****=......:+********+-..   ");
      logger.info("    .:=....-********+:......:=+***=...    ");
      logger.info("     ......************=:............     ");
      logger.info("       ...:=+************+=:......        ");
      logger.info("         ....:-===+++==--:......          ");
      logger.info("                                          ");
      logger.info("                                          ");

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
      logger.info("******************************************");
      logger.info("                                          ");
      logger.info("Mifos X Reporting Plugin started successfully. ");
      logger.info("Mifos X Reporting Plugin is ready.");
      logger.info("                                          ");
      logger.info("******************************************");      

    } catch (BirtException e) {
      logger.error("******************************************");
      logger.error("                                          ");
      logger.error("Failed to start Mifos X Reporting Plugin. ");
      logger.error("Reports will not function.", e);
      logger.error("                                          ");
      logger.error("******************************************");      
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
      logger.info("Destroying Mifos X Reporting Plugin...");
      reportEngine.destroy();
    }
    logger.info("Shutting down Mifos X Reporting Plugin...");
    Platform.shutdown();
    logger.info("Mifos X Reporting Plugin shutdown complete.");
  }
}
