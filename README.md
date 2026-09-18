# proyecto-eapn-portabilidad

Proyecto universitario: base técnica del Desafío 1, integrador de una EAPN de
Portabilidad Numérica en Paraguay. Incluye la recepción REST, validación inicial
y persistencia PostgreSQL, generación de PIN, notificación al operador donante
y confirmación del PIN por el titular, decisión del operador donante,
registro del número portado y notificación final al receptor.

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
No hay reintentos automáticos ni DLC en esta etapa; la excepción de integración
queda separada para incorporar esa política posteriormente.

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
devuelve conflicto; no se implementa idempotencia ni un límite de reintentos.

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
(por defecto 5000 ms). No hay reintentos automáticos ni DLC; la excepción técnica
se mantiene separada para incorporar esa recuperación en otra etapa.

## Finalización y notificación al receptor

Después de persistir `APPROVED`, `PortabilityFinalizationService.finish()` ejecuta
en una misma transacción JDBC (`@Transactional`):

1. Insertar en `ported_number` el MSISDN, donante como `operador_anterior`, receptor
   como `operador_actual` y `fecha_portacion` tomada del `Clock` configurado.
2. Actualizar la solicitud de `APPROVED` a `COMPLETED` y guardar `fecha_completada`.

Ambas fechas usan el mismo `OffsetDateTime`. Si falla cualquiera de las escrituras,
se revierte la transacción: no queda un número portado parcial ni una solicitud
completada, se conserva `APPROVED` y la API devuelve HTTP 500. Un MSISDN ya existente
produce error de inserción; no se implementa un upsert ni idempotencia.

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
fuera de la transacción de base de datos. No hay reintentos automáticos ni DLC;
la excepción de integración queda separada para incorporarlos posteriormente.
El timeout se configura con `RECEIVER_NOTIFICATION_RESPONSE_TIMEOUT_MS` (5000 ms
por defecto).

Los tests verifican los cuatro receptores, el payload sin PIN, la correlación,
los errores de entrega y el envío posterior al commit. Las pruebas transaccionales
usan el proxy Spring y el gestor JDBC real con una conexión simulada para comprobar
commit y rollback, sin PostgreSQL externo. Reiniciar WireMock si estaba activo
antes de agregar los mappings: `docker compose restart wiremock`.
