# proyecto-eapn-portabilidad

Proyecto universitario: base técnica del Desafío 1, integrador de una EAPN de
Portabilidad Numérica en Paraguay. Todavía no incluye lógica de negocio.

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
los mappings se colocarán en `wiremock/mappings/`.

Configuración por entorno: `DB_URL`, `DB_USER`, `DB_PASSWORD`,
`ARTEMIS_BROKER_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD`, `WIREMOCK_BASE_URL`
y `APP_TIMEZONE` (por defecto `America/Asuncion`). Exportar las variables para
compartirlas entre Compose y `bootRun`; Spring Boot no lee `.env` automáticamente.
El SQL inicial se ejecuta solo al crear un volumen PostgreSQL vacío.
El test de contexto usa mocks de JDBC/JMS y no necesita contenedores.
