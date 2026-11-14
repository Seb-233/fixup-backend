#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# INT-01: deja configurada la pasarela Wompi del backend con tus llaves.
#
#   ./scripts/configurar-wompi.sh                 te pide las 4 llaves (las secretas no se ven)
#   ./scripts/configurar-wompi.sh --env-file F    además las copia a otro .env (Docker, VM)
#
# También acepta las llaves por variables de entorno (WOMPI_PUBLIC_KEY, WOMPI_PRIVATE_KEY,
# WOMPI_INTEGRITY_SECRET, WOMPI_EVENTS_SECRET) para no escribirlas en pantalla.
#
# Las llaves están en el panel de comercios de Wompi, en Desarrolladores > Programación de
# llaves y secretos: llave pública (pub_test_…), llave privada (prv_test_…), secreto de
# eventos (test_events_…) y secreto de integridad (test_integrity_…).
#
# Escribe fixup-backend/.env.wompi (permisos 600, ignorado por git). Spring lo lee al arrancar
# desde la carpeta del backend; no hay que tocar nada más. Si las llaves son de producción
# (pub_prod_…), el backend usa production.wompi.co; si son de prueba, sandbox.wompi.co.
# ---------------------------------------------------------------------------
set -euo pipefail

backend="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
target="$backend/.env.wompi"
extra_files=()
# Wompi rechaza (403) el regreso a localhost o a una IP; en local se usa http://fixup.localhost:<puerto>.
default_origins="https://fix-rent.tailb7a45b.ts.net,http://fixup.localhost:4200,http://fixup.localhost:4300,http://fixup.localhost:4400"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) [[ $# -ge 2 ]] || { echo "Falta la ruta después de --env-file" >&2; exit 2; }
                extra_files+=("$2"); shift 2 ;;
    -h|--help)  sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)          echo "Opción desconocida: $1 (usa --help)" >&2; exit 2 ;;
  esac
done

ask() {  # ask VAR "Etiqueta" secreto(0|1)
  local var="$1" label="$2" secret="$3" value="${!1:-}"
  while [[ -z "$value" ]]; do
    if [[ "$secret" == 1 ]]; then
      read -r -s -p "$label: " value; echo
    else
      read -r -p "$label: " value
    fi
    value="$(printf '%s' "$value" | tr -d '[:space:]')"
  done
  printf -v "$var" '%s' "$value"
}

echo "Configuración de Wompi para FixUp"
echo
ask WOMPI_PUBLIC_KEY       "Llave pública   (pub_test_…)"        0
ask WOMPI_PRIVATE_KEY      "Llave privada   (prv_test_…)"        1
ask WOMPI_INTEGRITY_SECRET "Secreto de integridad (test_integrity_…)" 1
ask WOMPI_EVENTS_SECRET    "Secreto de eventos    (test_events_…)"    1

case "$WOMPI_PUBLIC_KEY" in
  pub_test_*) env=test; api="https://sandbox.wompi.co/v1" ;;
  pub_prod_*) env=prod; api="https://production.wompi.co/v1" ;;
  *) echo "La llave pública debe empezar por pub_test_ o pub_prod_" >&2; exit 1 ;;
esac
problems=()
[[ "$WOMPI_PRIVATE_KEY" == "prv_${env}_"* ]]          || problems+=("la llave privada debe empezar por prv_${env}_")
[[ "$WOMPI_INTEGRITY_SECRET" == "${env}_integrity_"* ]] || problems+=("el secreto de integridad debe empezar por ${env}_integrity_")
[[ "$WOMPI_EVENTS_SECRET" == "${env}_events_"* ]]       || problems+=("el secreto de eventos debe empezar por ${env}_events_")
if [[ ${#problems[@]} -gt 0 ]]; then
  printf 'Revisa las llaves: %s\n' "${problems[@]}" >&2
  echo "(Las cuatro deben ser del mismo ambiente: todas de prueba o todas de producción.)" >&2
  exit 1
fi
WOMPI_RETURN_ORIGINS="${WOMPI_RETURN_ORIGINS:-$default_origins}"

# Comprobación en línea, sin enviar la llave privada a ningún sitio distinto de Wompi.
if command -v curl >/dev/null; then
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$api/merchants/$WOMPI_PUBLIC_KEY" || true)"
  if [[ "$code" == 200 ]]; then
    echo "✔ Wompi reconoce la llave pública ($([[ $env == test ]] && echo sandbox || echo producción))."
  else
    echo "⚠ No pude confirmar la llave pública con Wompi (HTTP $code). Revisa que esté bien copiada." >&2
  fi
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $WOMPI_PRIVATE_KEY" \
    "$api/transactions?reference=fixup-configuracion" || true)"
  case "$code" in
    200) echo "✔ Wompi acepta la llave privada." ;;
    401|403) echo "⚠ Wompi rechazó la llave privada (HTTP $code). Revisa que esté bien copiada." >&2 ;;
    *) echo "· No pude comprobar la llave privada (HTTP $code); el backend avisará si falla." ;;
  esac
fi

write_keys() {  # write_keys archivo: actualiza solo las líneas WOMPI_* y conserva el resto
  local file="$1" tmp
  tmp="$(mktemp)"
  if [[ -f "$file" ]]; then
    grep -v -E '^(WOMPI_PUBLIC_KEY|WOMPI_PRIVATE_KEY|WOMPI_INTEGRITY_SECRET|WOMPI_EVENTS_SECRET|WOMPI_RETURN_ORIGINS)=' "$file" > "$tmp" || true
  fi
  {
    echo "WOMPI_PUBLIC_KEY=$WOMPI_PUBLIC_KEY"
    echo "WOMPI_PRIVATE_KEY=$WOMPI_PRIVATE_KEY"
    echo "WOMPI_INTEGRITY_SECRET=$WOMPI_INTEGRITY_SECRET"
    echo "WOMPI_EVENTS_SECRET=$WOMPI_EVENTS_SECRET"
    echo "WOMPI_RETURN_ORIGINS=$WOMPI_RETURN_ORIGINS"
  } >> "$tmp"
  umask 077
  mv "$tmp" "$file"
  chmod 600 "$file"
}

{
  echo "# Llaves de Wompi para FixUp (INT-01). Generado por scripts/configurar-wompi.sh."
  echo "# No subir a git. Spring lo importa como propiedades; también sirve con 'source' o --env-file."
} > "$target.tmp" && chmod 600 "$target.tmp" && mv "$target.tmp" "$target"
write_keys "$target"
echo "✔ Llaves guardadas en $target"

for file in "${extra_files[@]+"${extra_files[@]}"}"; do
  write_keys "$file"
  echo "✔ Llaves copiadas a $file"
done

cat <<EOF

Listo. Reinicia el backend (desde la carpeta fixup-backend) y en la pantalla de pago aparece
"Pagar con Wompi". Para comprobarlo con la sesión iniciada: GET /payments/wompi → {"enabled":true}.

En local abre FixUp en http://fixup.localhost:4200 (no en localhost:4200): Wompi bloquea el
regreso a localhost. En el despliegue se usa https://fix-rent.tailb7a45b.ts.net.

Datos de prueba del sandbox:
  Tarjeta aprobada 4242 4242 4242 4242 · rechazada 4111 1111 1111 1111 (fecha futura, CVC de 3 dígitos)
  Nequi aprobado 3991111111 · rechazado 3992222222 · PSE: elige el banco que aprueba o el que rechaza

Webhook (opcional, necesita una URL pública): en el panel de Wompi, URL de eventos de prueba =
  https://fix-rent.tailb7a45b.ts.net/api/payments/wompi/events
Sin webhook también funciona: al volver del checkout FixUp consulta la transacción a Wompi.
EOF
