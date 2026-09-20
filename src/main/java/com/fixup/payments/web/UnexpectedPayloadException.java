package com.fixup.payments.web;

/**
 * El cliente mandó un cuerpo en una operación que no lo lleva.
 *
 * <p>No es un detalle de forma. La transferencia se hace por el saldo disponible que calcula el
 * backend, así que un cuerpo con un monto solo puede significar que quien llama cree estar
 * decidiendo cuánto se transfiere. Callar y transferir otra cifra sería la peor respuesta.
 */
class UnexpectedPayloadException extends RuntimeException {
    UnexpectedPayloadException() {
        super("This operation does not accept a request body");
    }
}
