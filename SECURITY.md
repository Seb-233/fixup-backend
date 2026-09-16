# Política de seguridad

## Reportar una vulnerabilidad

Contactar de forma privada a los mantenedores del repositorio mediante el canal privado acordado con el equipo. Si GitHub ofrece la opción «Report a vulnerability» en la pestaña Security, utilizarla. No publicar detalles explotables, secretos ni datos personales en Issues o pull requests públicos.

Incluir una descripción, el componente afectado y pasos de reproducción sanitizados. No adjuntar credenciales reales.

## Archivos y datos excluidos

No versionar archivos `.env` ni sus variantes, salvo `.env.example` con valores ficticios; configuraciones locales con secretos; claves privadas, certificados privados y almacenes de claves; service accounts; credenciales de Auth0, PostgreSQL, Firebase/FCM o nube; dumps, respaldos, datos personales o logs sensibles.

Nunca publicar secretos en commits, logs, Issues o PR. No registrar tokens, contraseñas, Authorization, documentos de identidad, datos de pago ni variables de entorno. Revisar el contenido de los cambios: `.gitignore` no sustituye esa revisión.

## Exposición de credenciales

Detener la publicación, informar de forma privada indicando el archivo y commit sin repetir el valor, y revocar o rotar la credencial inmediatamente. No reutilizarla.

Borrar el valor en un commit posterior no lo elimina del historial. Coordinar cualquier limpieza de historia con los mantenedores antes de ejecutarla y verificar nuevamente el repositorio y los artefactos publicados.
