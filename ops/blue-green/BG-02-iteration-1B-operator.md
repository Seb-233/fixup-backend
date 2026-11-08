# BG-02 Iteración 1B — informe y procedimiento para operador NBIG44

Fecha: 2026-11-07. **Veredicto de esta sesión: NO APTO.** El contexto local es `default`, API `https://10.43.98.238:6443`, pero `kubectl` devuelve `socket: operation not permitted`; Docker devuelve `permission denied` al socket. No se crearon recursos 1B. La aprobación de 1A y su prueba de aislamiento son información aportada por el operador, no repetida en esta sesión. Mantener BG-02 Iteración 2 detenida.

**Evidencia disponible en esta sesión:** `kubectl get` de quota Green, pods Blue y StorageClass falló por el mismo bloqueo de socket; por ello no se obtuvieron permisos efectivos, estado vivo de Blue, consumo de CPU, memoria, swap, disco del nodo, cuota usada, PV reales ni imágenes del runtime. `bash -n` de `verify-green.sh` pasó. PyYAML cargó los diez manifiestos revisados; `kubectl create --dry-run=client --validate=false` enumeró los nueve objetos de los siete archivos 1B, sin contactar al servidor. Las cuatro definiciones Service/StatefulSet de los archivos por etapas equivalen exactamente a `10-data.yaml` y los dos PVC explícitos equivalen a sus templates. No hubo server-side dry-run, prueba de admisión, conectividad, persistencia ni snapshot posterior. El inventario **creado por esta sesión** es vacío; el inventario vivo de 1B es desconocido. No interpretar las pruebas locales como creación o salud del clúster.

| Recurso previsto en `fixup-green` | Imagen / almacenamiento | Estado comprobado aquí |
| --- | --- | --- |
| Secret `green-postgres-secret` | Claves `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`; valores nuevos pendientes | No creado por esta sesión; estado vivo desconocido |
| PVC `data-postgres-0` | `local-path`, RWO, 2Gi; PV y nodo aún desconocidos | No creado por esta sesión; estado vivo desconocido |
| Service y StatefulSet `postgres` | `postgres@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232` | No creados por esta sesión; estado vivo desconocido |
| Secret `green-minio-secret` | Claves `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`; valores nuevos pendientes | No creado por esta sesión; estado vivo desconocido |
| PVC `data-minio-0` | `local-path`, RWO, 2Gi; PV y nodo aún desconocidos | No creado por esta sesión; estado vivo desconocido |
| Service y StatefulSet `minio` | `ghcr.io/seb-233/fixup-minio@sha256:a1a8bd4ac40ad7881a245bab97323e18f971e4d4cba2c2007ec1bedd21cbaba2` | No creados por esta sesión; estado vivo desconocido |
| Job `green-minio-bootstrap` | `ghcr.io/seb-233/fixup-minio-client@sha256:9aa490587da3c5b6e0e77319fab2b34bc2ef61a03a7f6463575e533793bf7108`; bucket `fixup-green-private` | No creado por esta sesión; bucket no comprobado |
| Jobs temporales `green-minio-smoke` y `green-minio-persist-check` | Misma imagen cliente; objeto sintético `1b-probe.txt` | No creados por esta sesión; persistencia no comprobada |

**Comparación antes/después:** Blue Ready 3/3 fue informado por el operador para 1A, pero no medido aquí antes de 1B; Blue después de 1B no existe como medición porque no hubo despliegue. CPU sostenida, memoria disponible, swap, disco, cuota usada y consumo Green tienen valor **desconocido** en ambos puntos. La única incidencia de esta sesión es el bloqueo de red del sandbox hacia el API; no se cambió la infraestructura para resolverlo. El rollback siguiente se revisó por alcance estático, pero no se ejecutó ni verificó en el clúster.

## Auditoría de los cinco archivos requeridos

- `00-foundation.yaml`: seis recursos 1A; namespace `fixup-green`, quota de 8 pods, 2 PVC, 4Gi de storage, requests 1200m/3Gi y limits 2500m/5Gi. Las dos NetworkPolicy seleccionan solo pods Green; salida DNS a CoreDNS y salida a pods Green etiquetados `postgres`/`minio`. No referencia volúmenes ni Secrets Blue. Confirmar que el estado vivo coincide antes de 1B.
- `network-canary.yaml`: un pod `fixup-green/green-network-canary`, sin Secret ni volumen, SA `green-runtime`, imagen por digest y `imagePullPolicy: Never`. Su `sleep 3600` puede haber terminado; si ya no está Running, no declarar validación viva de 1B.
- `10-data.yaml`: dos Services headless y dos StatefulSets de una réplica, con selectores `fixup.role=postgres|minio` limitados al namespace Green. `volumeClaimTemplates` de `data`/`local-path`/2Gi/RWO crean PVC `data-postgres-0` y `data-minio-0`. No hay rutas Blue. `PGDATA` usa subdirectorio `pgdata`; readiness PostgreSQL usa `pg_isready` (no prueba contraseña); MinIO usa `/minio/health/ready`. Cada pod solicita 100m/256Mi y limita 250m/512Mi. No hay `securityContext` de contenedor; las imágenes administran permisos del volumen, lo que requiere verificar UID/ownership y controles de admisión vivos. Los contenedores no montan token de SA.
- `11-minio-bootstrap.yaml`: Job Green con `green-minio-secret`, imagen cliente por digest y `imagePullPolicy: Never`; crea bucket con `mc mb --ignore-existing`, fija acceso anónimo `none` y comprueba `mc stat`. Es idempotente respecto al bucket. Se añadió localmente `activeDeadlineSeconds: 300` para acotar el bucle de espera y TTL de 3600 s para revisar el Job. No probado en servidor.
- `verify-green.sh`: exige backend Green, HPA, dos endpoints y 60 respuestas `/readyz`. Pertenece a Iteración 2 y **no debe ejecutarse en 1B**.

Para respetar la secuencia exigida, `10-data.yaml` se dividió **sin modificar sus Service/StatefulSet** en `1b-postgres.yaml` y `1b-minio.yaml`. `1b-postgres-pvc.yaml` y `1b-minio-pvc.yaml` crean explícitamente los dos PVC con la misma spec de sus `volumeClaimTemplates`; se verificó equivalencia local. `1b-minio-smoke-write.yaml` y `1b-minio-smoke-read.yaml` son Jobs temporales separados para escritura/lectura sintética y posterior lectura de persistencia.

**Consumo 1B:** dos PVC/4Gi exactos; PostgreSQL+MinIO 200m CPU/512Mi requests y 500m/1Gi limits. Con canary y los tres Jobs todavía presentes y activos, el máximo teórico es 6 pods, 370m/736Mi requests y 900m/1472Mi limits; por debajo de la quota declarada, siempre que el uso vivo permita ese saldo. Los Jobs terminados pueden seguir visibles hasta su TTL; comprobar el uso real de la quota. `local-path` no impone reserva física de 4Gi; comprobar el disco del nodo y los PV reales.

## Puertas obligatorias por SSH antes de crear nada

El operador debe comprobar permisos efectivos para `get/list/watch` en Blue y Green y `create` de Secrets, PVC, Services, StatefulSets y Jobs **sólo en `fixup-green`**. Confirmar Blue 3/3 Ready, sin reinicios ni degradación, capacidad aprobada vigente (CPU sostenida <60%, available >4Gi, libre >20Gi, swap estable), quota restante, StorageClass `local-path`, canary Running, reglas vivas y nftables de 1A, imágenes exactas en el runtime de cada nodo elegible y ausencia de recursos 1B preexistentes no inventariados. Si una comprobación falla: NO APTO y no crear recursos.

```bash
cd /home/estudiante/fixup-backend-develop
test "$(kubectl config current-context)" = default
test "$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')" = 'https://10.43.98.238:6443'
kubectl -n fixup get pods -o wide
kubectl get namespace fixup-green
kubectl -n fixup-green get resourcequota,serviceaccount,configmap,networkpolicy,pod,pvc,svc,sts,job -o wide
kubectl -n fixup-green describe resourcequota green-budget
kubectl get storageclass local-path -o yaml
kubectl top node
kubectl -n fixup-green top pods
free -h
df -h /
kubectl auth can-i create secrets -n fixup-green
kubectl auth can-i create persistentvolumeclaims -n fixup-green
kubectl auth can-i create services -n fixup-green
kubectl auth can-i create statefulsets.apps -n fixup-green
kubectl auth can-i create jobs.batch -n fixup-green
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.nodeInfo.containerRuntimeVersion}{"\n"}{end}'
# En cada nodo elegible, comprobar el runtime indicado arriba; si K3s usa containerd:
sudo k3s crictl inspecti 'postgres@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232'
sudo k3s crictl inspecti 'ghcr.io/seb-233/fixup-minio@sha256:a1a8bd4ac40ad7881a245bab97323e18f971e4d4cba2c2007ec1bedd21cbaba2'
sudo k3s crictl inspecti 'ghcr.io/seb-233/fixup-minio-client@sha256:9aa490587da3c5b6e0e77319fab2b34bc2ef61a03a7f6463575e533793bf7108'
```

No ejecutar mutaciones basándose sólo en que `auth can-i` diga `yes`; verificar el inventario vivo. Medir capacidad con la misma ventana acordada, no con una sola muestra. Las imágenes deben encontrarse en el runtime de **cada nodo elegible**; Docker no constituye evidencia si K3s usa containerd. Los comandos `sudo` anteriores son para ejecución por el operador en el nodo, no para el sandbox de Codex.

## Validación y despliegue condicional

Validación local realizada aquí: PyYAML cargó todos los manifiestos 1B; `kubectl create --dry-run=client --validate=false` listó exactamente 2 PVC, 2 Services, 2 StatefulSets y 1 Job; las dos particiones de `10-data.yaml` son idénticas a los documentos originales y los PVC coinciden con sus templates. **No es evidencia de creación ni de admisión del API.** Tras existir 1A y pasar las puertas, ejecutar `kubectl apply --dry-run=server` de cada archivo 1B y del Job; si hay error, detener.

Primero hacer el dry-run del servidor de los manifiestos de recursos, que no crea objetos. Kubernetes no comprueba la existencia de SecretRefs en ese dry-run: verificar el nombre y claves por inspección local y volver a comprobar los pods al arrancar. Generar **dos Secrets nuevos**, solo Green, con contraseñas aleatorias diferentes. Usar un directorio privado temporal en tmpfs, modo 700, sin imprimir valores ni guardarlos en Git; registrar por canal seguro la custodia necesaria para recuperación. No crear `green-backend-secret` en 1B.

```bash
kubectl apply --dry-run=server -f ops/blue-green/1b-postgres-pvc.yaml -o name
kubectl apply --dry-run=server -f ops/blue-green/1b-minio-pvc.yaml -o name
kubectl apply --dry-run=server -f ops/blue-green/1b-postgres.yaml -o name
kubectl apply --dry-run=server -f ops/blue-green/1b-minio.yaml -o name
kubectl apply --dry-run=server -f ops/blue-green/11-minio-bootstrap.yaml -o name
kubectl apply --dry-run=server -f ops/blue-green/1b-minio-smoke-write.yaml -o name
kubectl apply --dry-run=server -f ops/blue-green/1b-minio-smoke-read.yaml -o name
umask 077
GREEN_SECRET_DIR="$(mktemp -d /dev/shm/fixup-green-1b.XXXXXX)"
printf 'fixup_green' > "$GREEN_SECRET_DIR/POSTGRES_DB"
printf 'fixup_green' > "$GREEN_SECRET_DIR/POSTGRES_USER"
python3 -c 'import secrets,sys; sys.stdout.write(secrets.token_hex(32))' > "$GREEN_SECRET_DIR/POSTGRES_PASSWORD"
printf 'green_minio_admin' > "$GREEN_SECRET_DIR/MINIO_ROOT_USER"
python3 -c 'import secrets,sys; sys.stdout.write(secrets.token_hex(32))' > "$GREEN_SECRET_DIR/MINIO_ROOT_PASSWORD"
kubectl -n fixup-green create secret generic green-postgres-secret --from-file=POSTGRES_DB="$GREEN_SECRET_DIR/POSTGRES_DB" --from-file=POSTGRES_USER="$GREEN_SECRET_DIR/POSTGRES_USER" --from-file=POSTGRES_PASSWORD="$GREEN_SECRET_DIR/POSTGRES_PASSWORD"
kubectl -n fixup-green create secret generic green-minio-secret --from-file=MINIO_ROOT_USER="$GREEN_SECRET_DIR/MINIO_ROOT_USER" --from-file=MINIO_ROOT_PASSWORD="$GREEN_SECRET_DIR/MINIO_ROOT_PASSWORD"
rm -f "$GREEN_SECRET_DIR/POSTGRES_DB" "$GREEN_SECRET_DIR/POSTGRES_USER" "$GREEN_SECRET_DIR/POSTGRES_PASSWORD" "$GREEN_SECRET_DIR/MINIO_ROOT_USER" "$GREEN_SECRET_DIR/MINIO_ROOT_PASSWORD"
rmdir "$GREEN_SECRET_DIR"
kubectl apply -f ops/blue-green/1b-postgres-pvc.yaml
kubectl apply -f ops/blue-green/1b-minio-pvc.yaml
kubectl apply -f ops/blue-green/1b-postgres.yaml
kubectl -n fixup-green rollout status statefulset/postgres --timeout=180s
kubectl -n fixup-green wait --for=jsonpath='{.status.phase}'=Bound pvc/data-postgres-0 --timeout=180s
kubectl apply -f ops/blue-green/1b-minio.yaml
kubectl -n fixup-green rollout status statefulset/minio --timeout=180s
kubectl -n fixup-green wait --for=jsonpath='{.status.phase}'=Bound pvc/data-minio-0 --timeout=180s
kubectl apply -f ops/blue-green/11-minio-bootstrap.yaml
kubectl -n fixup-green wait --for=condition=complete job/green-minio-bootstrap --timeout=360s
```

**Nota de StorageClass:** si `local-path` usa `WaitForFirstConsumer`, los PVC permanecerán Pending hasta crear su StatefulSet. Por ello la espera de Bound va después de cada StatefulSet. Confirmar `volumeBindingMode` en la puerta previa. No editar la StorageClass.

## Aceptación posterior obligatoria

Registrar `get -o yaml` de Green, PVC/PV binding, StorageClass, UID de pods, digest de imágenes, `describe quota`, consumo, eventos y Blue antes/durante/después sin volcar Secrets. Desde el canary Running: `nslookup postgres.fixup-green.svc.cluster.local` y `minio.fixup-green.svc.cluster.local`; comprobar TCP positivo a Green y negativo a Service **y endpoints Ready** de Blue, con DNS positivo y contadores nftables de denegación. Un simple fallo de conexión no aprueba aislamiento. Confirmar que los endpoints Green corresponden sólo a `postgres-0` y `minio-0` y que los PVC están enlazados a PV distintos de Blue.

PostgreSQL: desde `postgres-0`, ejecutar `psql` con la contraseña del Secret en entorno, crear tabla `green_probe_1b`, insertar identificador sintético fijo, leerlo, y confirmar que una contraseña incorrecta falla. No imprimir la contraseña. MinIO: aplicar `1b-minio-smoke-write.yaml` para escribir y leer `1b-probe.txt`; verificar bucket privado y que acceso anónimo falla. Tras comprobar que Blue sigue sano, la capacidad tiene margen, los PVC están Bound y los datos son exclusivamente sintéticos, reiniciar **sólo** `postgres-0` y `minio-0` en `fixup-green` durante ventana 1B; esperar Ready y leer de nuevo tabla y objeto con `1b-minio-smoke-read.yaml`. Si no hay ventana segura, declarar persistencia pendiente y `APTO CON LIMITACIONES`, nunca fingirla.

```bash
kubectl -n fixup-green exec green-network-canary -- nslookup postgres.fixup-green.svc.cluster.local
kubectl -n fixup-green exec green-network-canary -- nslookup minio.fixup-green.svc.cluster.local
kubectl -n fixup-green exec green-network-canary -- curl -v --connect-timeout 2 --max-time 3 telnet://postgres.fixup-green.svc.cluster.local:5432
kubectl -n fixup-green exec green-network-canary -- curl -fsS --max-time 3 http://minio.fixup-green.svc.cluster.local:9000/minio/health/ready
test "$(kubectl -n fixup-green exec green-network-canary -- curl -sS -o /dev/null -w '%{http_code}' --max-time 3 http://minio.fixup-green.svc.cluster.local:9000/fixup-green-private/1b-probe.txt)" = 403
kubectl -n fixup-green exec postgres-0 -- sh -ec 'export PGPASSWORD="$POSTGRES_PASSWORD"; psql -h postgres.fixup-green.svc.cluster.local -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "CREATE TABLE IF NOT EXISTS green_probe_1b (id text PRIMARY KEY, marker text NOT NULL); INSERT INTO green_probe_1b VALUES ('\''probe1'\'', '\''synthetic'\'') ON CONFLICT (id) DO UPDATE SET marker = EXCLUDED.marker; SELECT * FROM green_probe_1b WHERE id='\''probe1'\'';"'
kubectl -n fixup-green exec postgres-0 -- sh -ec 'if PGPASSWORD=definitely_wrong psql -h postgres.fixup-green.svc.cluster.local -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1" >/dev/null 2>&1; then exit 1; fi'
kubectl apply -f ops/blue-green/1b-minio-smoke-write.yaml
kubectl -n fixup-green wait --for=condition=complete job/green-minio-smoke --timeout=240s
# Solo tras las puertas de reinicio seguro y verificación Blue:
kubectl -n fixup-green delete pod postgres-0 --wait=true
kubectl -n fixup-green rollout status statefulset/postgres --timeout=180s
kubectl -n fixup-green exec postgres-0 -- sh -ec 'export PGPASSWORD="$POSTGRES_PASSWORD"; psql -h postgres.fixup-green.svc.cluster.local -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -tAc "SELECT marker FROM green_probe_1b WHERE id='\''probe1'\''"'
kubectl -n fixup-green delete pod minio-0 --wait=true
kubectl -n fixup-green rollout status statefulset/minio --timeout=180s
kubectl apply -f ops/blue-green/1b-minio-smoke-read.yaml
kubectl -n fixup-green wait --for=condition=complete job/green-minio-persist-check --timeout=240s
kubectl -n fixup get pods -o wide
```

En la prueba TCP de PostgreSQL, observar `Connected to`; `curl` puede terminar por timeout aun cuando el socket abrió. Para bloqueo hacia Blue, usar los Services y todos sus endpoints Ready por IP además del nombre DNS, y correlacionar con contadores nftables de la política; los comandos y criterios de 1A están en `BG-02-iteration-1A-review.md`. Un resultado positivo Green y negativo Blue es indispensable. Confirmar por separado que el acceso anónimo a MinIO da 403 sin exponer URLs firmadas.

Comprobar quota y CPU/memoria/swap/disco antes, durante y después; aborto ante pérdida de readiness/errores Blue, CPU >75% durante 3 minutos, available <3Gi, libre <15Gi, crecimiento significativo de swap, conexión Green→Blue, referencia Blue en Green o cualquier mutación fuera de alcance.

## Reversión restringida

No borrar namespace 1A ni recursos Blue. Si se aborta, detener Jobs 1B, después StatefulSets y Services 1B; conservar PVC `data-postgres-0`/`data-minio-0` y Secrets Green hasta verificar identidad, datos y decisión de retención. **No borrar PVC ni PV automáticamente**: `local-path` puede destruir datos al eliminar el claim. Inventariar antes y después, confirmar Blue 3/3 Ready. Solo una decisión explícita posterior permite borrar claims o Secrets.

```bash
kubectl -n fixup-green delete job green-minio-bootstrap green-minio-smoke green-minio-persist-check --ignore-not-found
kubectl -n fixup-green delete statefulset postgres minio --ignore-not-found --wait=true
kubectl -n fixup-green delete service postgres minio --ignore-not-found
kubectl -n fixup-green get pvc,secret,pod,svc,sts,job
kubectl get pv
kubectl -n fixup get pods -o wide
```

**Pendiente para un veredicto APTO:** acceso real al API y al runtime de los nodos, permisos limitados, imágenes y StorageClass verificadas, server-side dry-run, condiciones de salud/capacidad, creación, pruebas de persistencia/aislamiento y Blue estable. Ninguna de esas pruebas vivas se ejecutó desde esta sesión.
