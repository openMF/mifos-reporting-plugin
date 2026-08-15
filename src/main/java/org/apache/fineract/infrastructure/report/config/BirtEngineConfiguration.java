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
import lombok.extern.slf4j.Slf4j;
import org.eclipse.birt.core.exception.BirtException;
import org.eclipse.birt.core.framework.Platform;
import org.eclipse.birt.report.engine.api.EngineConfig;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportEngineFactory;
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
@Slf4j
@Configuration
public class BirtEngineConfiguration {

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
            log.info("******************************************");
            log.info("Initializing Mifos X Reporting Plugin...  ");
            log.info("******************************************");
            log.info("                                          ");
            log.info("                                          ");
            log.info("         .................                ");
            log.info("      ....:-=+*********++=:.              ");
            log.info("     . ....=************+=.......         ");
            log.info("   ...-....=**********+-.....:=++-.       ");
            log.info("  ..-++:...:*********:.....-+*****+:..    ");
            log.info(" ..=***-....+******-.....-+*********-.    ");
            log.info(" .=****+....-****=.....-+************-..  ");
            log.info(" -******=....=*+:....:+***************:.  ");
            log.info(".+*******:....:.....+*****************+.  ");
            log.info(".*********:.......-********************:. ");
            log.info(".**********:.....=*********************:. ");
            log.info(".**********......-+********************:. ");
            log.info("=*******+.........=*******************:.  ");
            log.info("..+*****+....:=:.....+****************:.  ");
            log.info("...+****:....+**=:.....=*************-.   ");
            log.info(". ..=**-....=*****=......:+********+-..   ");
            log.info("    .:=....-********+:......:=+***=...    ");
            log.info("     ......************=:............     ");
            log.info("       ...:=+************+=:......        ");
            log.info("         ....:-===+++==--:......          ");
            log.info("                                          ");
            log.info("                                          ");
            log.info("                                          ");
            log.info("(c) 2011-2026 Mifos X https://mifos.org   ");
            log.info("                                          ");
            log.info("******************************************");

            EngineConfig config = new EngineConfig();
            // Redirect BIRT internal logging to prevent console spam.
            config.setLogConfig(null, Level.WARNING);

            // Start the BIRT Platform
            Platform.startup(config);

            // Report Engine Factory
            IReportEngineFactory factory = (IReportEngineFactory)
                    Platform.createFactoryObject(IReportEngineFactory.EXTENSION_REPORT_ENGINE_FACTORY);

            // Create the Engine instance
            reportEngine = factory.createReportEngine(config);
            log.info("******************************************");
            log.info("                                          ");
            log.info("Mifos X Reporting Plugin started successfully. ");
            log.info("Mifos X Reporting Plugin is ready.");
            log.info("                                          ");
            log.info("******************************************");

        } catch (BirtException e) {
            log.error("******************************************");
            log.error("                                          ");
            log.error("Failed to start Mifos X Reporting Plugin. ");
            log.error("Reports will not function.", e);
            log.error("                                          ");
            log.error("******************************************");
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
            log.info("Destroying Mifos X Reporting Plugin...");
            reportEngine.destroy();
        }
        log.info("Shutting down Mifos X Reporting Plugin...");
        Platform.shutdown();
        log.info("Mifos X Reporting Plugin shutdown complete.");
    }
}
