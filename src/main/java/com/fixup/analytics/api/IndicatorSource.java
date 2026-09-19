package com.fixup.analytics.api;

/**
 * FR-UC-15: de dónde salió el número. Va en la respuesta junto con la frescura porque son cosas
 * distintas: un dato sintético puede ser LIVE, y decir solo LIVE haría creer al cliente que es
 * una observación real del mercado.
 */
public enum IndicatorSource {
    /** Proveedor externo de datos inmobiliarios. Es una observación real del mercado. */
    EXTERNAL_PROVIDER,
    /**
     * Respaldo de desarrollo. El número es sintético y derivado del nombre de la zona; no
     * representa el mercado y no debe presentarse como tal. Solo existe bajo el perfil dev.
     */
    DEVELOPMENT_SYNTHETIC
}
