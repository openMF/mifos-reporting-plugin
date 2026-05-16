/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(BirtPluginIsEnabledCondition.class)
@Slf4j
public class BirtPluginInformation implements InitializingBean {

  @Override
  public void afterPropertiesSet() throws Exception {
    log.warn("*******************************************************");
    log.warn("*                                                     *");
    log.warn("*            Mifos X Reporting Plugin Enabled         *");
    log.warn("*                                                     *");
    log.warn("*******************************************************");
  }
}
