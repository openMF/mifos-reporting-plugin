/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportErrorHandler {

    private final MessageSource messageSource;

    public PlatformDataIntegrityException reportError(String errorCode, String defaultMessage, Throwable cause) {
        String message = messageSource.getMessage(errorCode, null, defaultMessage, LocaleContextHolder.getLocale());
        return new PlatformDataIntegrityException(errorCode, message, cause);
    }

    public PlatformDataIntegrityException reportError(String errorCode, String defaultMessage) {
        return reportError(errorCode, defaultMessage, null);
    }
}