/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.config;

import java.util.List;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
public class BirtPluginLiquibaseConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(BirtPluginLiquibaseConfiguration.class);

    @Autowired
    private TenantDetailsService tenantDetailsService;

    @Autowired
    private DataSource routingDataSource;

    @Bean
    @DependsOn("tenantDatabaseUpgradeService") // Must run AFTER Fineract core DB is set up
    public String runSelfServicePluginMigrations() {
        LOG.info("******************************************************");
        LOG.info("*     Mifos X Reporting Plugin Migrations Started    *");
        LOG.info("******************************************************");

        List<FineractPlatformTenant> tenants = tenantDetailsService.findAllTenants();

        for (FineractPlatformTenant tenant : tenants) {
            LOG.info("Running Mifos X Reporting Plugin default reports for tenant: {}", tenant.getTenantIdentifier());
            try {
                // 1. Force the database connection to route to THIS specific tenant
                ThreadLocalContextUtil.setTenant(tenant);

                // 2. Initialize Liquibase for the tenant
                SpringLiquibase liquibase = new SpringLiquibase();
                liquibase.setDataSource(routingDataSource);

                // Ensure this matches the exact path of the plugin's master changelog
                liquibase.setChangeLog("classpath:/db/changelog/tenant/module/reporting/module-changelog-master.xml");
                liquibase.setShouldRun(true);

                // 3. Execute the migration
                liquibase.afterPropertiesSet();

                LOG.info(
                        "Successfully migrated Mifos X Reporting Plugin default reports for tenant: {}",
                        tenant.getTenantIdentifier());
            } catch (Exception e) {
                LOG.error(
                        "Failed to migrate Mifos X Reporting Plugin default reports for tenant: {}",
                        tenant.getTenantIdentifier(),
                        e);
                throw new RuntimeException("Mifos X Reporting Plugin Failed", e);
            } finally {
                // 4. Always clear the context so we don't leak connections
                ThreadLocalContextUtil.clearTenant();
            }
        }
        LOG.info("******************************************************");
        LOG.info("*   Mifos X Reporting Plugin Migrations Completed    *");
        LOG.info("******************************************************");
        return "Mifos X Reporting Plugin Migrations Completed";
    }
}
