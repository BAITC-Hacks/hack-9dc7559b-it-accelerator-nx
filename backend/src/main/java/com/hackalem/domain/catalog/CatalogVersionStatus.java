package com.hackalem.domain.catalog;

/** Жизненный цикл версии каталога: PENDING → READY → ACTIVE → SUPERSEDED, либо FAILED. */
public enum CatalogVersionStatus {
    PENDING,
    READY,
    ACTIVE,
    SUPERSEDED,
    FAILED
}
