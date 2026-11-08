# BG-02 Iteración 1A — revisión previa, sin ejecución

Fecha: 2026-11-07. La línea base de capacidad y salud de Blue fue confirmada por el operador; no se repite aquí. No se ha creado `fixup-green` ni ejecutado el canary. Este entorno no puede conectar al API de Kubernetes (`socket: operation not permitted`) ni al socket de Docker (`permission denied`), por lo que las comprobaciones vivas indicadas abajo siguen pendientes.

## Alcance y aislamiento de manifiestos

`00-foundation.yaml` declara exactamente: Namespace `fixup-green`; ResourceQuota `green-budget`; ServiceAccount `green-runtime` sin token automontado; ConfigMap `green-config`; NetworkPolicy `green-default-deny` y `green-allow-local`. `network-canary.yaml` añade únicamente el Pod `green-network-canary`, sin Secrets ni volúmenes. La cuota permite hasta 8 pods, 2 PVC y 4 Gi de almacenamiento, pero la etapa 1A usa sólo un pod, **20m CPU/32Mi solicitados** y **100m CPU/64Mi de límite**. No crea PVC ni reserva almacenamiento.

Ambas NetworkPolicy viven en `fixup-green` y seleccionan sólo pods de ese namespace. Los `podSelector` de las reglas de entrada y salida sin `namespaceSelector` también se limitan a `fixup-green`, aunque Blue comparta etiquetas `fixup.role`. La regla DNS selecciona pods `k8s-app=kube-dns` de `kube-system`; la regla de Alloy selecciona `observability`. Ninguna regla modifica una política, Service, Secret, Deployment o pod de `fixup`. La ConfigMap sólo referencia FQDN Green y valores sintéticos. Esto confirma el alcance de los manifiestos, no la eficacia viva del CNI.

## Puertas antes de aplicar

1. Confirmar contexto `default`, API `https://10.43.98.238:6443`, que `fixup-green` está ausente y que Blue conserva sus tres pods Ready y sin reinicios. Si el namespace ya existe, detener y reconciliar inventario; no aplicar sobre recursos ajenos.
2. Confirmar que CoreDNS realmente tiene la etiqueta `k8s-app=kube-dns` en `kube-system`, su Service y endpoints están Ready, y que el CNI aplica NetworkPolicy. Una etiqueta distinta exige corregir y revisar el manifiesto antes de aplicar.
3. Confirmar el runtime de cada nodo elegible. `imagePullPolicy: Never` exige que el digest `curlimages/curl@sha256:58adaa4e8dca9c988bae2aba4ab3434a0bb2da16bbe3f92dec39ec7785166777` esté en el **runtime del nodo Kubernetes**; una imagen presente sólo en Docker no basta si K3s usa containerd. Confirmar también que la imagen ofrece `sh`, `sleep`, `curl` y `nslookup`. No cambiar a `Always` ni descargar una imagen sin revisión.
4. La autorización de 1A cubre únicamente los siete recursos nombrados. No cubre Secrets, PostgreSQL, MinIO, PVC, backend, HPA ni componentes de Iteración 2.

## Comandos de inspección y aplicación, sólo tras autorización de 1A

Ejecutar desde `/home/estudiante/fixup-backend-develop`, en una sesión con acceso a NBIG44. Si falla una comprobación previa, detener sin aplicar.

```bash
cd /home/estudiante/fixup-backend-develop
test "$(kubectl config current-context)" = default
test "$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')" = 'https://10.43.98.238:6443'
test -z "$(kubectl get namespace fixup-green -o name --ignore-not-found)"
kubectl -n fixup get pods -o wide
kubectl -n kube-system get pods -l k8s-app=kube-dns -o wide
kubectl -n kube-system get svc,endpointslices -l k8s-app=kube-dns -o wide
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.nodeInfo.containerRuntimeVersion}{"\n"}{end}'
# En el nodo Kubernetes, usar la herramienta del runtime observado, por ejemplo:
# sudo k3s crictl inspecti 'curlimages/curl@sha256:58adaa4e8dca9c988bae2aba4ab3434a0bb2da16bbe3f92dec39ec7785166777'
kubectl apply --dry-run=server -f ops/blue-green/00-foundation.yaml -o name
kubectl apply --dry-run=server -f ops/blue-green/network-canary.yaml -o name
kubectl apply -f ops/blue-green/00-foundation.yaml
kubectl -n fixup-green get resourcequota,serviceaccount,configmap,networkpolicy
kubectl apply -f ops/blue-green/network-canary.yaml
kubectl -n fixup-green wait --for=condition=Ready pod/green-network-canary --timeout=120s
```

El listado `get svc,endpointslices -l k8s-app=kube-dns` puede estar vacío si Service/EndpointSlice usan etiquetas diferentes; comprobarlos entonces por nombre, sin inferir que DNS funciona por la etiqueta del Pod únicamente. El `dry-run=server` requiere API y no sustituye la prueba de CNI.

## Canary: distinguir DNS, ruta y bloqueo

Antes de probar, comprobar desde Kubernetes que los Services y EndpointSlices de `fixup/postgres` y `fixup/minio` tienen direcciones Ready. Tomar las IP de Service y de cada endpoint Ready; no suponerlas ni codificarlas en el manifiesto.

```bash
kubectl -n fixup get svc postgres minio -o wide
kubectl -n fixup get endpointslices -l kubernetes.io/service-name=postgres -o wide
kubectl -n fixup get endpointslices -l kubernetes.io/service-name=minio -o wide
kubectl -n fixup-green exec green-network-canary -- nslookup kubernetes.default.svc.cluster.local
kubectl -n fixup-green exec green-network-canary -- nslookup postgres.fixup.svc.cluster.local
kubectl -n fixup-green exec green-network-canary -- nslookup minio.fixup.svc.cluster.local
kubectl -n fixup-green exec green-network-canary -- curl -v --connect-timeout 2 --max-time 3 telnet://postgres.fixup.svc.cluster.local:5432
kubectl -n fixup-green exec green-network-canary -- curl -v --connect-timeout 2 --max-time 3 telnet://minio.fixup.svc.cluster.local:9000
# Repetir estos dos intentos con telnet://IP_SERVICE:PUERTO cuando haya ClusterIP,
# y con telnet://IP_ENDPOINT_READY:PUERTO para cada endpoint Ready.
```

DNS pasa sólo si las tres consultas devuelven IP y no `NXDOMAIN`/timeout. El bloqueo pasa sólo si todos los intentos TCP hacia Blue, por nombre, ClusterIP si existe y **cada IP de endpoint Ready**, muestran `Trying <IP>` seguido de timeout de conexión, sin `Connected to`. El código 28 de `curl` por sí solo no prueba bloqueo: una conexión establecida a un puerto TCP silencioso también puede terminar por timeout. `Could not resolve host` tampoco prueba aislamiento. Un TCP conectado, respuesta HTTP, 401/403 o rechazo inmediato de conexión exige investigar y no aprobar. Verificar de nuevo Blue Ready después de la prueba. DNS UDP queda probado por `nslookup`; si el contenedor no tiene `nslookup`, detener y revisar la imagen/procedimiento.

La etapa 1A demuestra bloqueo Green→Blue para esas rutas. La conexión permitida indispensable verificable ahora es DNS. El acceso permitido a PostgreSQL/MinIO **Green** sólo podrá probarse en la etapa siguiente, cuando existan sus pods y Services; no declararlo aprobado en 1A.

## Aborto y limpieza

Abortar si cambia el contexto/API, Blue se degrada, el namespace preexiste, falta la imagen en el runtime, el canary no inicia, DNS falla, algún TCP a Blue conecta o no puede distinguirse el motivo del fallo. Aplican además los límites operativos ya aprobados para la ventana: CPU >75 % por tres minutos, memoria disponible <3 GiB, swap creciente o disco libre <15 GiB. Registrar evidencia sin Secrets ni datos productivos.

La reversión de 1A elimina sólo recursos Green con nombres explícitos. Antes de borrar el namespace, inspeccionar que no contenga recursos ajenos, Secrets Green, PVC ni cargas adicionales. Si los hay, **no borrar el namespace**; detener y decidir conservación/limpieza por separado.

```bash
test "$(kubectl config current-context)" = default
test "$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')" = 'https://10.43.98.238:6443'
kubectl -n fixup-green delete pod green-network-canary --ignore-not-found --wait=true
kubectl -n fixup-green delete networkpolicy green-allow-local green-default-deny --ignore-not-found
kubectl -n fixup-green delete configmap green-config --ignore-not-found
kubectl -n fixup-green delete serviceaccount green-runtime --ignore-not-found
kubectl -n fixup-green delete resourcequota green-budget --ignore-not-found
kubectl -n fixup-green get all,secret,pvc,configmap,serviceaccount,networkpolicy,resourcequota
# Sólo si el inventario restante contiene únicamente los objetos automáticos del namespace:
kubectl delete namespace fixup-green --wait=true
kubectl -n fixup get pods -o wide
```

**Decisión:** los manifiestos son aptos para revisión de aprobación de 1A, condicionada a las puertas vivas de DNS/CNI, imagen en runtime y acceso al API. No hay autorización de ejecución en este documento.
