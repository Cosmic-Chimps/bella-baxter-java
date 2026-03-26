package io.bellabaxter.samples;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Sample app — reads secrets written to a .env file by the Bella CLI.
 *
 * Start with:
 *   bella secrets get -p my-project -e production -o .env && java -jar app.jar
 */
public class App {

    public static void main(String[] args) {
        // Load .env file from current working directory (written by: bella secrets get -o .env)
        Dotenv dotenv = Dotenv.configure()
                .directory(System.getProperty("user.dir", "."))
                .ignoreIfMissing()
                .load();

        System.out.println("=== Bella Baxter: .env file sample (Java) ===");
        String[] keys = {
            "PORT", "DATABASE_URL", "EXTERNAL_API_KEY", "GLEAP_API_KEY",
            "ENABLE_FEATURES", "APP_ID", "ConnectionStrings__Postgres", "APP_CONFIG"
        };
        for (String key : keys) {
            String value = dotenv.get(key, "(not set)");
            // Unescape \" → " (dotenv-java retains escape sequences from quoted .env values)
            value = value.replace("\\\"", "\"");
            System.out.printf("%s=%s%n", key, value);
        }
        System.out.println();
        System.out.println("All secrets loaded from .env file written by: bella secrets get -o .env");
    }
}
