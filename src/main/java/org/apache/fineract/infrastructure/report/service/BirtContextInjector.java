/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.eclipse.birt.report.engine.api.IRunTask;
import org.springframework.stereotype.Component;

/** Handles authorization context injection for BIRT reports (Single Responsibility). */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtContextInjector {

  private final PlatformSecurityContext securityContext;
  private final ReportErrorHandler reportErrorHandler;

  /**
   * Injects security and tenant-specific contextual parameters into the BIRT run task. This ensures
   * row-level scoping and tenant isolation are enforced during report execution.
   *
   * @param task The BIRT run task to configure.
   */
  public void injectContextParameters(IRunTask task) {
    try {
      var currentUser = securityContext.authenticatedUser();
      var tenant = ThreadLocalContextUtil.getTenant();

      // Maintain only critical contextual row-level scoping markers
      task.setParameterValue("userhierarchy", currentUser.getOffice().getHierarchy());
      task.setParameterValue("userid", currentUser.getId());

      log.debug(
          "Security scope context parameters injected successfully for tenant: {}",
          tenant.getName());
    } catch (Exception e) {
      log.error("Failed to inject context parameters", e);
      throw reportErrorHandler.reportError(
          "error.msg.reporting.error", "Failed to inject user security context parameters", e);
    }
  }
}
