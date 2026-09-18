# proyecto-eapn-portabilidad

Proyecto universitario: base técnica del Desafío 1, integrador de una EAPN de
Portabilidad Numérica en Paraguay. Incluye la recepción REST, validación inicial
y persistencia PostgreSQL, generación de PIN, notificación al operador donante
y confirmación del PIN por el titular, decisión del operador donante,
registro del número portado y notificación final al receptor mediante colas Artemis,
idempotencia persistente, eventos de estado y Dead Letter Channel.

**Stack:** Java 21, Gradle Wrapper 9.6.0, Spring Boot 3.5.16, Apache Camel 4.18.3
(Java DSL), JMS con Apache ActiveMQ Artemis 2.44.0, PostgreSQL 17.6,
WireMock 3.13.1 y Docker Compose.

Requisitos: JDK 21 y Docker con Compose. No hace falta instalar Gradle global.

```sh
docker compose up -d
./gradlew test
./gradlew bootRun
```

Desarrollo local: PostgreSQL en `localhost:5432`, base `eapn`; Artemis JMS en
`localhost:61616` y consola en `http://localhost:8161`. Ambos usan usuario `eapn`
y contraseña `eapn_dev` (solo desarrollo). WireMock: `http://localhost:8081`;
los mappings de notificación y aprobación están en `wiremock/mappings/`.

Configuración por entorno: `DB_URL`, `DB_USER`, `DB_PASSWORD`,
`ARTEMIS_BROKER_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD`, `WIREMOCK_BASE_URL`
y `APP_TIMEZONE` (por defecto `America/Asuncion`). Exportar las variables para
compartirlas entre Compose y `bootRun`; Spring Boot no lee `.env` automáticamente.
El SQL inicial se ejecuta solo al crear un volumen PostgreSQL vacío.
El test de contexto usa mocks de JDBC/JMS y no necesita contenedores.

## Recepción de solicitudes

`POST http://localhost:8080/portabilidad`, con `Content-Type: application/json`:

```json
{
  "msisdn": "+595971234567",
  "documento_titular": "0012345",
  "operador_donante": "Tigo",
  "operador_receptor": "Personal"
}
```

El MSISDN debe tener `+5959` y ocho dígitos adicionales, sin espacios ni guiones
(validación de formato móvil, no de existencia del número). Documento obligatorio,
hasta 50 caracteres según el esquema; se recortan espacios exteriores.
Operadores permitidos: Tigo, Personal, Claro y Vox; se normalizan mayúsculas y
espacios exteriores, y donante/receptor deben ser distintos.

Devuelve HTTP 201 con `id`, `msisdn`, `operador_donante`, `operador_receptor`,
`estado` y `fecha_creacion` en ISO 8601. El ID es `REQ-YYYYMMDD-<UUID v4 sin guiones>`:
fecha en `APP_TIMEZONE` y sufijo de 32 caracteres hexadecimales. Primero se guarda
`CREATED` con cero intentos y PIN/fechas posteriores nulos; luego continúa el flujo
de PIN. El HTTP 201 devuelve `estado: PIN_GENERATED` únicamente después de recibir
la confirmación de envío del donante. La respuesta pública nunca incluye el PIN.

Errores de validación o JSON devuelven HTTP 400 con
`{"estado":"RECHAZADA","mensaje":"..."}`, sin insertar. Un error de persistencia
devuelve HTTP 500; no se informa como rechazo de negocio ni como creación exitosa.
Los tests de servicio y HTTP usan mocks de persistencia, sin infraestructura externa.

## Consulta de portabilidad

`GET http://localhost:8080/portabilidad/{id}` consulta el estado persistido mediante
Camel REST DSL → `PortabilityQueryService` → `PortabilityRequestRepository.findById`.
No modifica la solicitud ni envía mensajes a operadores.

HTTP 200 devuelve un DTO público con `id`, `msisdn`, `operador_donante`,
`operador_receptor`, `estado`, `fecha_creacion`, `fecha_pin_generado`,
`fecha_pin_confirmado` y `fecha_completada`. Las fechas son ISO 8601; los hitos
no alcanzados conservan `null`. En estado `REJECTED` se incluye `motivo_rechazo`
si está disponible. Un motivo técnico de entrega de PIN no se publica como rechazo.
No se devuelven PIN, expiración, documento, intentos ni datos internos JMS.

Una solicitud inexistente devuelve HTTP 404 con
`{"estado":"RECHAZADA","mensaje":"La solicitud de portabilidad no existe."}`.
Un fallo técnico devuelve HTTP 500 con
`{"estado":"ERROR","mensaje":"No se pudo consultar la solicitud."}`,
sin detalles de la excepción. La consulta permite observar también estados
transitorios como CONFIRMED, PENDING_DONOR y APPROVED.

## Generación y envío de PIN

`PinGenerationService` genera seis dígitos con `SecureRandom` (incluye ceros
iniciales). Usa el `Clock` configurado: `fecha_pin_generado = now` y
`pin_expiracion = now + 15 minutos`. PIN, fechas y `PIN_GENERATED` se guardan
en una sola actualización SQL antes de llamar al donante.

Camel delega la integración a `direct:enviar-pin-operador`, que hace
`POST ${WIREMOCK_BASE_URL}/operador/pin-notification` (base local:
`http://localhost:8081`). Envía el header `X-Operador-Donante` con uno de:
`Tigo`, `Personal`, `Claro`, `Vox`, y el JSON interno:

```json
{
  "request_id": "REQ-20260918-0123456789abcdef0123456789abcdef",
  "msisdn": "+595971234567",
  "documento_titular": "0012345",
  "pin": "000042"
}
```

Cada mapping responde HTTP 200 con el mismo `request_id`, `estado: PIN_ENVIADO`
y `mensaje: PIN enviado al titular`. La ruta comprueba la correlación y el estado;
no espera que el donante devuelva el PIN. Los mappings usan el transformer local
`response-template` para copiar `request_id` ([documentación de WireMock](https://wiremock.org/docs/response-templating/)).
Si WireMock ya estaba iniciado al agregar los mappings, ejecutar
`docker compose restart wiremock` para cargarlos.

Si el donante devuelve error HTTP, falla la conexión o la confirmación es inválida,
la API responde HTTP 502 con `estado: ERROR` y un mensaje con el ID de solicitud.
La solicitud conserva `PIN_GENERATED`: indica generación, no garantía de entrega.
El motivo técnico se guarda en `motivo_rechazo` (campo disponible en el esquema)
y se registra junto con ID/donante, sin PIN ni cuerpos HTTP. No representa un
rechazo final de portabilidad y no cambia `fecha_completada` ni avanza otros estados.
Si no se puede guardar el motivo, se devuelve error de persistencia, no éxito.
La entrega pasa por Artemis y aplica tres intentos totales; al agotarse, el
consumidor publica un diagnóstico seguro en `eapn.error` (ver Mensajería).

Los tests HTTP arrancan WireMock en la JVM con puerto aleatorio y cargan los
mappings del repositorio. No necesitan Docker, PostgreSQL ni Artemis.

## Confirmación del PIN

`POST http://localhost:8080/portabilidad/{id}/pin/confirmar`, con
`Content-Type: application/json` y body `{"pin":"000042"}`.

Solo se aceptan solicitudes existentes en `PIN_GENERATED`, con PIN almacenado,
fecha de generación y expiración. El PIN recibido debe ser texto con exactamente
seis dígitos ASCII, sin recortar espacios (se preservan ceros iniciales).
La comparación temporal usa el `Clock` configurado y `OffsetDateTime`:
`now >= pinExpiracion` se considera expirado, incluyendo el instante exacto.
Si además es incorrecto, prevalece el motivo de expiración.

Un PIN correcto y vigente guarda `CONFIRMED` y `fecha_pin_confirmado = now`,
limpia cualquier motivo técnico anterior y continúa inmediatamente con la consulta
al donante. Después de persistir el resultado y confirmar su notificación al
receptor, la respuesta HTTP 200 refleja el estado final, por ejemplo:

```json
{"id":"REQ-...","estado":"COMPLETED","mensaje":"Portabilidad completada correctamente"}
```

Un PIN incorrecto o expirado guarda `REJECTED` y su motivo específico, sin marcar
`fecha_completada`. Cada intento evaluable (formato válido sobre una solicitud
habilitada con datos de PIN) incrementa `intentos_confirmacion` en uno, tanto
si confirma como si rechaza. Incremento y resultado se persisten juntos mediante
un único UPDATE condicionado a `PIN_GENERATED`. JSON/PIN malformado, solicitud
inexistente, estado incompatible o datos de PIN ausentes no incrementan intentos
ni modifican la solicitud. Una segunda confirmación de `CONFIRMED` o `REJECTED`
devuelve conflicto; la idempotencia JMS no modifica este contrato HTTP ni agrega
un límite de intentos de confirmación.

| HTTP | Resultado |
| --- | --- |
| 200 | Resultado final COMPLETED o REJECTED, notificado al receptor |
| 400 | JSON/PIN malformado, PIN incorrecto o expirado |
| 404 | Solicitud inexistente |
| 409 | Estado incompatible, PIN generado incompleto o cambio concurrente de estado |
| 500 | Error de persistencia o procesamiento |
| 502 | Error de integración: conserva PENDING_DONOR si falla la consulta al donante, o COMPLETED/REJECTED si falla la notificación final |

Los errores 400/404/409 conservan el formato
`{"estado":"RECHAZADA","mensaje":"..."}`. Ese estado describe la respuesta;
en la validación del PIN, solo uno incorrecto o expirado cambia la solicitud a `REJECTED`.
Los errores 500/502 usan `estado: ERROR`. Ninguna respuesta incluye el PIN.
Los tests de confirmación usan reloj fijo, repository simulado y WireMock embebido.

## Decisión del operador donante

Flujo después del PIN correcto:

```text
CONFIRMED → PENDING_DONOR → APPROVED → COMPLETED → notificar receptor
                         → REJECTED (con motivo) → notificar receptor
```

`DonorApprovalService` persiste `PENDING_DONOR` antes de hacer la consulta.
Camel utiliza `direct:solicitar-aprobacion-donante` para enviar
`POST ${WIREMOCK_BASE_URL}/operador/portability-approval` con
`X-Operador-Donante: Tigo|Personal|Claro|Vox` y estos campos:

```json
{
  "request_id": "REQ-...",
  "msisdn": "+595971234567",
  "documento_titular": "12345",
  "operador_receptor": "Personal"
}
```

La respuesta debe ser HTTP 200, JSON válido y contener el mismo `request_id`,
`estado` exactamente `APPROVED` o `REJECTED`, y un `motivo` no vacío si rechaza.
Una decisión válida se persiste mediante un UPDATE condicionado a `PENDING_DONOR`.
El rechazo de negocio guarda su motivo en `motivo_rechazo` y notifica al receptor.
Si la notificación se confirma, devuelve HTTP 200 con `estado: REJECTED` y ese
motivo en `mensaje`.

Los cuatro mappings normales responden `APPROVED` con `motivo: null`.
Para simular un rechazo con cualquier donante, crear la solicitud usando
`documento_titular: TEST-REJECTED`: el mapping de mayor prioridad responde
`REJECTED` con `Datos del titular no coinciden`. Es una regla exclusiva del mock;
no hay un caso especial para ese documento en el código de negocio.

Errores HTTP, falta de respuesta, timeout, JSON inválido, correlación incorrecta,
estado desconocido o rechazo sin motivo devuelven HTTP 502. La solicitud queda
en `PENDING_DONOR`, sin guardar una decisión de negocio ni un motivo de rechazo.
El motivo técnico se registra en logs junto con ID/donante, sin cuerpos HTTP.
El timeout de respuesta se configura con `DONOR_APPROVAL_RESPONSE_TIMEOUT_MS`
(por defecto 5000 ms). El consumidor JMS aplica la política de redelivery/DLC
descrita abajo; un error técnico nunca equivale a una decisión `REJECTED`.

## Finalización y notificación al receptor

Después de persistir `APPROVED`, `PortabilityFinalizationService.finish()` ejecuta
en una misma transacción JDBC (`@Transactional`):

1. Insertar en `ported_number` el MSISDN, donante como `operador_anterior`, receptor
   como `operador_actual` y `fecha_portacion` tomada del `Clock` configurado.
2. Actualizar la solicitud de `APPROVED` a `COMPLETED` y guardar `fecha_completada`.

Ambas fechas usan el mismo `OffsetDateTime`. Si falla cualquiera de las escrituras,
se revierte la transacción: no queda un número portado parcial ni una solicitud
completada, se conserva `APPROVED` y la API devuelve HTTP 500. Un MSISDN ya existente
produce error de inserción; no se implementa un upsert. La deduplicación JMS
se aplica a las operaciones externas, no a nuevas solicitudes HTTP del mismo MSISDN.

Si la decisión fue `REJECTED`, se conserva ese estado y su motivo. No se inserta
en `ported_number` ni se escribe `fecha_completada`. Para el payload de rechazo,
`fecha_finalizacion` indica cuándo se preparó el resultado, no una fecha de portación.

Al retornar del servicio, la transacción ya finalizó. Entonces Camel llama a
`direct:notificar-resultado-receptor`, que realiza
`POST ${WIREMOCK_BASE_URL}/receptor/portability-result` con
`X-Operador-Receptor: Tigo|Personal|Claro|Vox`, sin reenviar `X-Operador-Donante`.
El DTO final tiene únicamente estos campos (no incluye PIN ni documento):

```json
{
  "request_id": "REQ-...",
  "msisdn": "+595971234567",
  "estado": "COMPLETED",
  "operador_donante": "Tigo",
  "operador_receptor": "Personal",
  "fecha_finalizacion": "2026-09-18T10:00:00-03:00",
  "motivo": null
}
```

Para rechazo usa `estado: REJECTED` y el motivo del donante. Los cuatro mappings
`wiremock/mappings/portability-result-*.json` pertenecen a este proyecto EAPN,
distinguen el receptor y devuelven HTTP 200 con el mismo `request_id` y
`estado: RECIBIDO`. La ruta verifica ambos valores.

Si falla la entrega o la confirmación del receptor, se devuelve HTTP 502 con
`estado: ERROR` y un mensaje que identifica la solicitud y su estado conservado.
La portabilidad permanece `COMPLETED`, o `REJECTED` con su motivo intacto.
El fallo técnico se registra en logs con ID, receptor y estado, sin modificar
datos de negocio ni registrar PIN o cuerpos HTTP. La llamada externa ocurre
fuera de la transacción de base de datos. El consumidor JMS reintenta la
notificación y usa el DLC si se agotan los intentos.
El timeout se configura con `RECEIVER_NOTIFICATION_RESPONSE_TIMEOUT_MS` (5000 ms
por defecto).

Los tests verifican los cuatro receptores, el payload sin PIN, la correlación,
los errores de entrega y el envío posterior al commit. Las pruebas transaccionales
usan el proxy Spring y el gestor JDBC real con una conexión simulada para comprobar
commit y rollback, sin PostgreSQL externo. Reiniciar WireMock si estaba activo
antes de agregar los mappings: `docker compose restart wiremock`.


## Mensajería Artemis

La API conserva sus respuestas síncronas. Las **tres integraciones HTTP pasan
realmente por Artemis**: el gateway envía un `TextMessage` JSON y espera la respuesta
del consumidor mediante JMS Request-Reply (cola temporal de respuesta). Generar
el PIN y persistir los estados sigue ocurriendo en los servicios existentes.

| Destino | Tipo | Trabajo del consumidor |
| --- | --- | --- |
| `eapn.pin.notification` | Queue | Entregar el PIN ya generado al donante |
| `eapn.donor.approval` | Queue | Solicitar y validar APPROVED/REJECTED |
| `eapn.receiver.notification` | Queue | Notificar COMPLETED/REJECTED al receptor |
| `eapn.state.events` | Topic | Publicación para suscriptores de auditoría |
| `eapn.error` | Queue | Diagnóstico técnico tras agotar los reintentos |

```text
REST crear → CREATED → PIN_GENERATED → queue PIN → consumidor → HTTP donante
REST confirmar → CONFIRMED → PENDING_DONOR → queue aprobación → consumidor → HTTP donante
  APPROVED → [insert ported_number + COMPLETED, transacción JDBC]
  REJECTED → conservar motivo
  → queue receptor → consumidor → HTTP receptor
Cambios persistidos → Wire Tap → topic de eventos
Fallo técnico del consumidor → 2 redeliveries → eapn.error + respuesta técnica
```

### EIPs y contratos

- **Correlation Identifier:** `JMSCorrelationID = request_id` en comandos,
  respuestas, eventos y errores. El consumidor valida también el ID del payload.
  Cada comando contiene `request_id`, `msisdn`, `operation`, `operator` y `payload`;
  el header JMS `operation` identifica la operación. La respuesta vuelve por
  `JMSReplyTo`; nunca se reenvían headers JMS al HTTP externo.
- **Request-Reply:** `MessagingGatewayRoute` espera al consumidor; este llama a las
  rutas HTTP existentes, valida la correlación y devuelve un `OperatorReply`.
  El timeout JMS es `MESSAGING_REQUEST_TIMEOUT_MS` (60000 ms). Debe superar la suma
  de los tres intentos HTTP y sus pausas; aumentarlo si se aumentan los timeouts HTTP.
- **Content-Based Router:** `OperatorRouter` tiene `choice/when` para Tigo, Personal,
  Claro y Vox, tanto para donante como receptor. Establece respectivamente
  `X-Operador-Donante` o `X-Operador-Receptor`; operador desconocido es error técnico.
- **Idempotent Receiver persistente:** `OperatorMessageProcessor` reclama la clave
  `(operation, request_id)` en `processed_message` mediante una PK PostgreSQL.
  Solo su propietario ejecuta el HTTP. Guarda una respuesta JSON segura y devuelve
  esa respuesta a duplicados, sin repetir la integración. La operación forma parte
  de la clave porque un mismo request debe pasar por las tres colas.
- **Dead Letter Channel:** `MessagingConsumerRoute` aplica **3 intentos totales**
  (original + 2 redeliveries), separados por 200 ms. Reintenta el procesamiento del
  comando completo, no solamente la deserialización de la respuesta HTTP. Los
  reintentos propios del cliente HTTP están desactivados. Incluye HTTP no 200,
  timeout, JSON inválido, correlación incorrecta, estado desconocido y excepciones
  del consumidor/JDBC. Un resultado `REJECTED` válido no dispara el DLC.
- **Wire Tap:** después de persistir cada estado, `StateEventPublisher` prepara un
  DTO seguro y `publish-state-event` desvía una copia a `deliver-state-event` sin
  demorar el flujo HTTP. Se publican CREATED, PIN_GENERATED, CONFIRMED, APPROVED,
  REJECTED (incluido PIN incorrecto/expirado) y COMPLETED. Los eventos contienen
  `request_id`, `msisdn`, `estado`, `timestamp` del Clock y `operation: STATE_CHANGED`.
  Sirven para observar la evolución sin consumir las colas de trabajo. Un suscriptor
  debe estar conectado o usar una suscripción durable para conservar eventos cuando
  esté desconectado; la aplicación no almacena un historial de auditoría adicional.

Las rutas originales `direct:enviar-pin-operador`,
`direct:solicitar-aprobacion-donante` y `direct:notificar-resultado-receptor` son
ahora gateways JMS; sus adaptadores HTTP tienen el sufijo `-http`. Los contratos
WireMock siguen siendo los mismos y los mappings son exclusivamente de EAPN.

### Errores, persistencia y recuperación

El diagnóstico de `eapn.error` incluye `request_id`, `origin`, `operation`, un
`summary` seguro, `exceptionType` y `timestamp`; no copia el mensaje original,
PIN, documento, credenciales ni textos arbitrarios de excepciones. Solo la cola
PIN lleva el PIN, porque es necesario para enviarlo. Las respuestas/cache, eventos,
cola de errores y notificación final no incluyen el PIN.

Después del DLC se devuelve una respuesta técnica al gateway (HTTP 502). Se
conserva PIN_GENERATED, PENDING_DONOR o COMPLETED/REJECTED según la etapa alcanzada.
La inserción del número y COMPLETED continúan siendo una transacción JDBC atómica;
ninguna espera JMS/HTTP externa forma parte de esa transacción. Si falla la
escritura de la cache después de un HTTP exitoso, los redeliveries del mismo
Exchange reintentan esa escritura sin repetir el HTTP.

Un duplicado con otra instancia todavía trabajando no se ejecuta: se reintenta
la lectura de su respuesta y, si sigue pendiente, termina en error sin liberar
la clave del otro consumidor. Tras un fallo propio agotado se libera únicamente
la clave todavía incompleta, después de publicar el diagnóstico. No hay reenvíos
automáticos desde `eapn.error`: corregir la causa y reconstruir el comando desde
la solicitud persistida para un reenvío explícito a su cola. Para un PIN debe
respetarse su vigencia; nunca generar otro PIN al repetir una entrega.

**Límite de consistencia:** no hay XA/outbox ni garantía de exactly-once entre
PostgreSQL, Artemis y HTTP. Un timeout puede ocurrir después de que el operador
haya procesado la llamada. Un cierre abrupto puede dejar una clave sin respuesta:
requiere revisar el resultado externo antes de liberarla manualmente. No se
elimina automáticamente para evitar duplicar un efecto incierto. Los eventos son
asíncronos y pueden llegar fuera de orden; no reemplazan el estado PostgreSQL.
Si Artemis no está disponible, no se puede garantizar publicar en su propia cola
de errores; se propaga error técnico y no se inventa un rechazo de negocio. Los
consumidores usan CLIENT_ACKNOWLEDGE y no ocultan un fallo al publicar el DLC.
La recuperación del flujo REST tras una caída entre etapas requiere intervención;
la deduplicación implementada cubre los consumidores JMS, no un reintento completo
de `POST /portabilidad` o de la confirmación.

### SQL y pruebas

Para un volumen nuevo, Compose ejecuta `docker/postgres/messaging.sql` después del
SQL de negocio. **Si el volumen ya existe**, aplicar una vez antes de `bootRun`:

```sh
docker compose exec -T postgresql sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < docker/postgres/messaging.sql
./gradlew test --rerun-tasks
```

La migración solo agrega `processed_message`, sin cambiar tablas de negocio.
Los 229 tests previos mantienen sus aserciones; los tests HTTP aislados establecen
`app.messaging.enabled=false`. En ejecución normal es `true`, sin fallback
silencioso si Artemis falla. Las nuevas pruebas usan **Artemis Jakarta 2.40.0
embebido**, WireMock local y H2 con JDBC real; estas dos nuevas dependencias son
exclusivas de test. Verifican transporte/correlación, los cuatro operadores,
deduplicación persistente, eventos, redelivery/DLC y preservación de estados.
El broker Docker sigue siendo Artemis 2.44.0 y no se necesita Docker para los tests.

Referencias: [JMS Request-Reply de Camel](https://camel.apache.org/components/4.18.x/jms-component.html)
y [Dead Letter Channel](https://camel.apache.org/components/4.18.x/eips/dead-letter-channel.html).


### Verificación de una instalación limpia

Compose monta `init.sql` como `01-init.sql` y `messaging.sql` como
`02-messaging.sql` dentro de `/docker-entrypoint-initdb.d/`. En un volumen nuevo,
PostgreSQL ejecuta ambos: crea automáticamente `portability_request`,
`ported_number` y `processed_message`. La ejecución manual de `messaging.sql`
solo corresponde a volúmenes antiguos que todavía no tienen esa tabla.

`PostgresInitializationTest` comprueba los montajes y su orden, y ejecuta los
scripts reales sin modificarlos sobre una base H2 vacía, con un alias de
compatibilidad para TIMESTAMPTZ. Esta prueba no sustituye una ejecución del
contenedor PostgreSQL, pero detecta scripts faltantes y tablas no inicializadas.
La revisión no requiere ejecutar `docker compose down -v` ni borrar datos locales.
