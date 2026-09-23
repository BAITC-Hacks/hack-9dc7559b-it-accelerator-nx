package com.hackalem.domain.catalog;

/**
 * Состояние индексации версии. Отдельно от статуса версии: точный поиск по
 * артикулу работает без провайдера, семантический — только при READY.
 */
public enum EmbeddingStatus {
    PENDING,
    RUNNING,
    READY,
    FAILED,
    SKIPPED
}
