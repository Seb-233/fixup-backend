# Almacenamiento de medios: estado y deuda conocida

Aplica a FR-UC-16 (documentos de verificación) y FR-UC-17 (portafolio del Fixer). Ambos casos de
uso guardan únicamente una clave de objeto (`storage_key`); ningún byte de archivo cruza esta API.

## Lo que existe hoy

- El cliente envía una `storageKey` arbitraria en el cuerpo de la petición.
- El backend la valida como texto (no vacía, máximo 512 caracteres) y la persiste.
- La respuesta pública del portafolio devuelve esa misma `storageKey`.

## Lo que todavía no existe

Esto está pendiente a propósito y no debe entenderse como terminado:

1. **La interfaz de carga no está terminada.** No hay servicio de carga: el backend no emite ni
   valida URLs firmadas, así que hoy no participa en cómo el archivo llega al almacenamiento.
2. **La clave no prueba propiedad.** Nada garantiza que la `storageKey` recibida apunte a un
   objeto que subió ese Fixer. Un cliente autenticado puede enviar la clave de otro y el backend
   la aceptará.
3. **La respuesta expone la estructura interna del almacenamiento.** El frontend arma hoy la URL
   de lectura a partir de la clave, es decir, depende de cómo está organizado el bucket.

## Condiciones antes de producción

Ninguna de estas puede faltar cuando el caso de uso salga de alcance académico:

- La carga debe hacerse contra una **URL firmada** emitida por el backend, con vencimiento corto y
  tipo/tamaño de contenido acotados.
- La clave debe quedar **asociada al Fixer autenticado** en el momento de emitir la URL firmada, y
  la publicación debe aceptar solo claves que el backend mismo haya emitido para ese Fixer.
- La respuesta pública debe entregar una **URL de lectura controlada o un identificador de medio**,
  no la clave de almacenamiento, para que el contrato no dependa de la estructura del bucket.

Mientras tanto, la `storageKey` se mantiene en el contrato para no romper al cliente actual. El
cambio a identificador de medio es un cambio incompatible del contrato y debe planearse como tal.
