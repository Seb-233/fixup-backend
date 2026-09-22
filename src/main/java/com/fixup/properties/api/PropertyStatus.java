package com.fixup.properties.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-12: ciclo de vida de la publicación de un inmueble. Un borrador todavía no se ofrece a
 * nadie; uno publicado sí; uno retirado deja de ofrecerse pero conserva su historia de publicación.
 */
@Schema(enumAsRef = true)
public enum PropertyStatus {
    DRAFT, PUBLISHED, UNLISTED
}
