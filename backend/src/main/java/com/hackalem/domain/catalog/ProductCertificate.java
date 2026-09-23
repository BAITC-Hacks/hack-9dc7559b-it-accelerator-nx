package com.hackalem.domain.catalog;

/** Ссылка на сертификат товара. Файл и версия — часть данных источника. */
public record ProductCertificate(String id, String url, String file, String version, Boolean synthetic) {
}
