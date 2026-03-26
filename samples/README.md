# Bella Baxter — Java SDK Samples

Samples showing how to integrate Bella Baxter secrets into Java applications.

| Sample | Approach | Best for |
|--------|----------|---------|
| [01-dotenv-file](./01-dotenv-file/) | `bella secrets get --app ... -o .env` + dotenv-java | Simplest setup, CI/CD, any app |
| [02-process-inject](./02-process-inject/) | `bella run --app ... -- java -jar app.jar` | Any command, no SDK code |
| [03-spring-boot](./03-spring-boot/) | `EnvironmentPostProcessor` (pre-bean startup) | Spring Boot — full `@Value` + typed `BellaAppSecrets` |
| [04-quarkus](./04-quarkus/) | CDI `@Observes StartupEvent` + `BellaSecretsStore` | Quarkus — JVM + native image + typed `BellaAppSecrets` |

---

## Which approach to choose?

```
Do you want zero Java SDK code?
├── YES → 01 (.env file) or 02 (bella run)
│
└── NO → What framework?
    ├── Spring Boot → 03-spring-boot (EnvironmentPostProcessor — @Value works!)
    └── Quarkus     → 04-quarkus (@Observes StartupEvent + CDI injection)
```

---

## Why EnvironmentPostProcessor for Spring Boot?

It's the only hook that runs **before Spring creates any beans**, meaning:
- `@Value("${DATABASE_URL}")` ✅ works
- `application.properties` placeholders: `spring.datasource.url=${DATABASE_URL}` ✅ works
- `@ConfigurationProperties` ✅ works
- Typed `BellaAppSecrets` class ✅ works

All other hooks (`@PostConstruct`, `ApplicationRunner`, `CommandLineRunner`) run too late.

---

## Typed secrets

Samples 03 and 04 include a `BellaAppSecrets` class providing typed, IDE-friendly access to secrets:

```java
// Spring Boot
@Autowired BellaAppSecrets secrets;
String pg = secrets.getConnectionStrings().getPostgres();

// Quarkus
@Inject BellaAppSecrets secrets;
String pg = secrets.connectionStringsPostgres();
```

Secret key mapping: `ConnectionStrings__Postgres` → nested `ConnectionStrings.postgres`  
(Spring Boot uses `__` → nested object for `@ConfigurationProperties`).

> **Backlog:** `bella-maven-plugin:generate` will auto-generate `BellaAppSecrets.java` from  
> `bella-secrets.manifest.json` — no manual writing needed.

---

## Prerequisites

- Java 21+
- Maven 3.9+
- Bella Baxter account + API key

```bash
# Authenticate
bella login --api-key bax-xxxxxxxxxxxxxxxxxxxx

# Set your Bella Baxter instance URL
export BELLA_BAXTER_URL=http://localhost:5522
```

---

## Bella CLI commands used

| Sample | Command |
|--------|---------|
| 01 | `bella secrets get --app java-01-dotenv-file -o .env` |
| 02 | `bella run --app java-02-process-inject -- java -jar ...` |
| 03 | `bella exec --app java-03-spring-boot -- java -jar ...` |
| 04 | `bella exec --app java-04-quarkus -- java -jar ...` |

> **Note on `-D` JVM flags:** `bella exec -- java -Dserver.port=9090` does **not** work — `bella`  
> parses `-D` as its own flag even after `--`. Use env vars instead:  
> `SERVER_PORT=9090 bella exec ...` (Spring Boot) or `QUARKUS_HTTP_PORT=9090 bella exec ...` (Quarkus).

---

## Secret rotation support

| Sample | Automatic rotation | Notes |
|--------|-------------------|-------|
| `01-dotenv-file` | ❌ No | Re-run `bella secrets get -o .env` + restart JVM |
| `02-process-inject` | ❌ No | Re-run `bella run --` to restart with fresh secrets |
| `03-spring-boot` | ❌ No (partial via `@RefreshScope`) | `EnvironmentPostProcessor` runs once; Actuator `/actuator/refresh` refreshes `@RefreshScope` beans only |
| `04-quarkus` | ❌ No (Quarkus hot-reload dev only) | Quarkus dev mode reloads; production requires restart |

**Java JVM limitation:** `System.getenv()` values are immutable after process start — true live rotation requires either restarting the JVM or using a mutable in-memory store (`ConcurrentHashMap`) updated by a background thread.
