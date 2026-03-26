# Sample 04: Quarkus

**Pattern:** CDI `@Startup` bean with `@Observes StartupEvent` — secrets loaded once at app startup, stored in an `@ApplicationScoped` `BellaSecretsStore`, injectable anywhere via CDI.

---

## Setup

```bash
export BELLA_BAXTER_URL=http://localhost:5522   # your Bella Baxter instance

# Authenticate with API key
bella login --api-key bax-xxxxxxxxxxxxxxxxxxxx

# Production JVM mode
mvn package && bella exec --app java-04-quarkus -- java -jar target/quarkus-app/quarkus-run.jar

# Override the HTTP port via environment variable (avoids bella's -D flag parsing issue):
QUARKUS_HTTP_PORT=8098 bella exec --app java-04-quarkus -- java -jar target/quarkus-app/quarkus-run.jar
```

> **Port override note:** `bella exec -- java -Dquarkus.http.port=8098` does not work because `bella`  
> parses `-D` as its own option even after `--`. Use the `QUARKUS_HTTP_PORT` environment variable  
> instead — Quarkus picks it up automatically.

---

## How it works

**`@Observes StartupEvent`** is Quarkus's CDI startup hook — runs once per JVM process after all CDI beans are initialized, before the HTTP server accepts connections.

```
Quarkus starts
  → CDI beans initialized
  → @Observes StartupEvent fired
  → BellaStartup.onStart() → getAllSecrets() → BellaSecretsStore.init()
  → HTTP server starts
  → Application ready
```

---

## Injecting secrets anywhere

```java
@ApplicationScoped
public class MyService {

    @Inject
    BellaSecretsStore secrets;

    public void doSomething() {
        String token = secrets.get("THIRD_PARTY_TOKEN").orElseThrow();
        // use token...
    }
}
```

## Typed secrets class (`BellaAppSecrets`)

`BellaAppSecrets` is an `@ApplicationScoped` CDI wrapper that exposes typed accessors over
`BellaSecretsStore`, providing IDE auto-complete and eliminating raw string key lookups:

```java
@ApplicationScoped
public class BellaAppSecrets {
    @Inject BellaSecretsStore store;

    public String port()                     { return store.get("PORT").orElse(null); }
    public String databaseUrl()              { return store.get("DATABASE_URL").orElse(null); }
    public String connectionStringsPostgres(){ return store.get("ConnectionStrings__Postgres").orElse(null); }
    // ...
}

// Inject anywhere in your Quarkus app:
@Inject BellaAppSecrets secrets;
String pg = secrets.connectionStringsPostgres();
```

> **Future:** `bella-maven-plugin:generate` will auto-generate `BellaAppSecrets.java` from  
> `bella-secrets.manifest.json` — no manual writing needed.

---

## Using secrets for Quarkus DataSource

```properties
# application.properties — Quarkus reads these at startup
# They reference env vars, which can be set from secrets store:

# Option A: bella run -- mvn quarkus:dev (secrets as env vars, Quarkus reads them directly)
quarkus.datasource.jdbc.url=${DATABASE_URL}
quarkus.datasource.username=${DB_USER}
quarkus.datasource.password=${DB_PASSWORD}
```

For secrets managed by `BellaSecretsStore` (SDK approach), configure programmatically:
```java
// Quarkus ConfigSourceProvider (advanced) — inject Bella secrets into Quarkus Config
// See: https://quarkus.io/guides/config-extending-support
```

For simplest integration with `quarkus.datasource.*`, use `bella run`:
```bash
bella run -- mvn quarkus:dev
bella run -- java -jar target/quarkus-app/quarkus-run.jar
```

---

## Native image support

The Bella Baxter SDK uses `java.net.http.HttpClient` (JDK built-in) — **GraalVM native image compatible**. No extra reflection config needed.

```bash
# Build native binary
mvn package -Pnative

# Run — secrets fetched at startup (< 100ms total boot time)
BELLA_API_KEY=bax-xxxxxxxxxxxxxxxxxxxx ./target/sample-04-quarkus-1.0.0-runner
```

---

## File layout

```
src/main/java/io/bellabaxter/samples/
  BellaStartup.java        ← @ApplicationScoped + @Observes StartupEvent
  BellaSecretsStore.java   ← @ApplicationScoped secrets store (injectable by key)
  BellaAppSecrets.java     ← Typed wrapper over BellaSecretsStore (write once per project)
  GreetingResource.java    ← Demo JAX-RS resource using secrets (/ and /typed endpoints)
src/main/resources/
  application.properties   ← Quarkus config with bella.* properties
pom.xml
README.md
```
