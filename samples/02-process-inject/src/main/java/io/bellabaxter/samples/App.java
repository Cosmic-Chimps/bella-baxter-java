package io.bellabaxter.samples;

/**
 * Sample app — reads secrets injected directly into the process by bella run.
 *
 * Start with:
 *   bella run -p my-project -e production -- java -jar app.jar
 *
 * No .env file is written. Secrets are already in System.getenv() from the parent process.
 */
public class App {

    public static void main(String[] args) {
        System.out.println("=== Bella Baxter: process inject sample (Java) ===");
        String[] keys = {
            "PORT", "DATABASE_URL", "EXTERNAL_API_KEY", "GLEAP_API_KEY",
            "ENABLE_FEATURES", "APP_ID", "ConnectionStrings__Postgres", "APP_CONFIG"
        };
        for (String key : keys) {
            System.out.printf("%s=%s%n", key, System.getenv().getOrDefault(key, "(not set)"));
        }
        System.out.println();
        System.out.println("Secrets injected directly into process by: bella run -- java -jar app.jar");
        System.out.println("No .env file was written.");
    }
}
