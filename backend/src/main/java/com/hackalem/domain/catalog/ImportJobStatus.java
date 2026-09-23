package com.hackalem.domain.catalog;

/** Состояние задания импорта, которое отдаёт GET /api/admin/jobs/{id}. */
public enum ImportJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
