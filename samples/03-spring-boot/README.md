# Sample 03: Spring Boot

**Pattern:** `EnvironmentPostProcessor` — secrets loaded into Spring's `Environment` **before any beans are created**, making them available via `@Value`, `application.properties` placeholders, and `Environment.getProperty()`.

---

## Setup

```bash
export BELLA_BAXTER_URL=http://localhost:5522   # your Bella Baxter instance

# Authenticate with API key
bella login --api-key bax-xxxxxxxxxxxxxxxxxxxx

mvn package

# Run with secrets injected by bella exec
bella exec --app java-03-spring-boot -- java -jar target/sample-03-spring-boot-1.0.0.jar

# Override the HTTP port via environment variable (avoids bella's -D flag parsing issue):
SERVER_PORT=8099 bella exec --app java-03-spring-boot -- java -jar target/sample-03-spring-boot-1.0.0.jar
```

> **Port override note:** `bella exec -- java -Dserver.port=8099` does not work because `bella`  
> parses `-D` as its own option even after `--`. Use the `SERVER_PORT` environment variable  
> instead — Spring Boot picks it up automatically.

---

## How it works

**`EnvironmentPostProcessor`** is Spring Boot's lowest-level config hook — runs before `@Configuration` classes, before `@Bean` methods, before `@Value` injection.

```
JVM starts
  → Spring Boot loads application.properties
  → EnvironmentPostProcessors run (including BellaEnvironmentPostProcessor)
  → BellaEnvironmentPostProcessor.postProcessEnvironment()
      → getAllSecrets() → MapPropertySource added to Environment
  → @SpringBootApplication context created
  → @Value, @ConfigurationProperties, DataSource, etc. all resolved
  → Application ready
```

---

## Using secrets

**`@Value`** (simplest):
```java
@Value("${DATABASE_URL}")
private String databaseUrl;

@Value("${REDIS_URL:redis://localhost:6379}")
private String redisUrl;
```

**`application.properties` placeholders** (Spring reads them transparently):
```properties
spring.datasource.url=${DATABASE_URL}
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}
spring.data.redis.url=${REDIS_URL}
```

**`@ConfigurationProperties`**:
```java
@ConfigurationProperties(prefix = "")
public record AppConfig(
    String databaseUrl,       // DATABASE_URL
    String redisUrl,          // REDIS_URL
    String externalApiKey     // EXTERNAL_API_KEY
) {}
```

**Typed secrets class (`BellaAppSecrets`)**:

`BellaEnvironmentPostProcessor` also publishes secrets with a `bellabaxter.` prefix, enabling a
fully-typed config class with IDE auto-complete and compile-time safety:

```java
@Component
@ConfigurationProperties(prefix = "bellabaxter")
public class BellaAppSecrets {
    private String port;
    private String appId;
    private String databaseUrl;
    // ... nested class for ConnectionStrings__Postgres:
    private ConnectionStrings connectionStrings = new ConnectionStrings();

    public static class ConnectionStrings {
        private String postgres;
        // getters / setters
    }
}

// Inject anywhere:
@Autowired BellaAppSecrets secrets;
String dbUrl = secrets.getDatabaseUrl();
String pg    = secrets.getConnectionStrings().getPostgres();
```

Secret key mapping: `DATABASE_URL` → `bellabaxter.databaseUrl`, `ConnectionStrings__Postgres` → `bellabaxter.connectionStrings.postgres`.

> **Future:** `bella-maven-plugin:generate` will auto-generate `BellaAppSecrets.java` from  
> `bella-secrets.manifest.json` — no manual writing needed.

**`Environment`**:
```java
@Autowired
ConfigurableEnvironment env;

String token = env.getProperty("THIRD_PARTY_TOKEN");
```

---

## Registration (spring.factories)

The post-processor is registered in `META-INF/spring.factories`:
```properties
org.springframework.boot.env.EnvironmentPostProcessor=\
  io.bellabaxter.samples.BellaEnvironmentPostProcessor
```

For **Spring Boot 3.0+**, you can also use `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor`:
```
io.bellabaxter.samples.BellaEnvironmentPostProcessor
```

Both mechanisms work with Spring Boot 3.x.

---

## Property precedence

Bella secrets are added with **`addLast`** (lowest precedence), meaning:
1. Command-line args (`--DATABASE_URL=...`) override Bella ✅
2. `application.properties` overrides Bella ✅
3. Environment variables override Bella ✅
4. Bella secrets fill in anything not already set ✅

This lets you override specific secrets locally for development without changing Bella.

---

## File layout

```
src/main/java/io/bellabaxter/samples/
  BellaEnvironmentPostProcessor.java   ← The key integration point
  BellaAppSecrets.java                 ← Typed @ConfigurationProperties wrapper (write once per project)
  Application.java                     ← @SpringBootApplication + demo routes (/ and /typed)
src/main/resources/
  application.properties               ← Uses ${DATABASE_URL} etc.
  META-INF/spring.factories            ← Registers the post-processor
pom.xml
README.md
```

## Secret rotation

❌ **Not supported automatically out of the box.** `EnvironmentPostProcessor` runs once during Spring context initialization. Secrets are baked into the `Environment` and do not change at runtime.

**To pick up rotated secrets:** restart the application. With Spring Boot Actuator you can trigger a partial refresh:

```bash
# Restart the JVM (simplest — always correct)
kubectl rollout restart deployment/myapp

# Or with Spring Boot Actuator + @RefreshScope beans:
curl -X POST http://localhost:8080/actuator/refresh
# ⚠️ Only refreshes @RefreshScope beans, NOT @Value fields or static config
```

**For automatic polling:** add a `ScheduledExecutorService` that periodically calls `BaxterClient.getAllSecrets()` and updates a shared `ConcurrentHashMap`. Annotate beans that need live values with `@RefreshScope` (requires `spring-cloud-starter-refresh` on the classpath).

Spring Cloud Config's `@RefreshScope` + a periodic `POST /actuator/refresh` call is the standard Spring way to hot-reload configuration without a full restart.
