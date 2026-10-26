package com.fixup.media.api;

/**
 * Escritura directa de objetos en el almacén, sin URL firmada. La usa solamente el cargador de datos
 * de demostración, que sube fotos propias del repositorio; el contenido de los usuarios sigue
 * entrando siempre por el flujo de subida, confirmación y adjunto.
 */
public interface MediaObjectWriter {
    boolean exists(String objectKey);

    void put(String objectKey, String contentType, byte[] bytes);
}
