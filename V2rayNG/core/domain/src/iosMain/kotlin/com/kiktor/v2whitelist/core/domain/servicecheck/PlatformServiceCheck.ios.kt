package com.kiktor.v2whitelist.core.domain.servicecheck

import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckResult

actual suspend fun rawServiceHttpCheck(
    service: ServiceCheckItem,
    socksPort: Int?,
    timeoutMs: Long
): ServiceCheckResult {
    return ServiceCheckResult(
        service = service,
        success = false,
        errorMessage = "Not supported on iOS"
    )
}

