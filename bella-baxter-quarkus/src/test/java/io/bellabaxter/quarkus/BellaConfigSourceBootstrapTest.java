package io.bellabaxter.quarkus;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the defect that made every Quarkus build fail.
 *
 * <p>{@code BellaConfigSource}'s constructor used to call {@code ConfigProvider.getConfig()} to read
 * its own options. Config discovers config sources through {@code META-INF/services}, so building it
 * constructed this class, which asked for Config, which discovered this class… until the stack ran
 * out. The application never started: {@code mvn package} died inside {@code quarkus-maven-plugin}
 * with {@code ServiceConfigurationError: Provider io.bellabaxter.quarkus.BellaConfigSource could not
 * be instantiated}, wrapping a {@link StackOverflowError}.
 *
 * <p>The original code anticipated this — its comment read "we ARE the config source being
 * bootstrapped" — and guarded it with {@code catch (Exception)}. {@link StackOverflowError} is an
 * {@link Error}, so the guard never fired. <b>That is the part worth remembering: a catch clause is
 * only a guard against the throwable types it names.</b>
 *
 * <p>The test calls {@code getConfig()} for real, against a real implementation on the test
 * classpath, so it goes through the same SPI discovery the Quarkus build does.
 */
class BellaConfigSourceBootstrapTest {

    @Test
    void buildingTheConfigDoesNotRecurseThroughThisSource() {
        // The whole defect in one line: with the old constructor this never returns, it
        // StackOverflowErrors inside the SPI scan.
        ThrowingSupplier<Config> build = ConfigProvider::getConfig;
        Config config = assertDoesNotThrow(
                build,
                "Building the Config must not re-enter BellaConfigSource's construction");

        assertNotNull(config);
    }

    @Test
    void constructionIsInert() {
        // Quarkus instantiates every registered source while ASSEMBLING the config, at build time,
        // where there is no credential and no network. Construction must therefore do nothing —
        // no resolution, no fetch, no exception.
        ThrowingSupplier<BellaConfigSource> construct = BellaConfigSource::new;
        BellaConfigSource source = assertDoesNotThrow(construct);

        // Identity is answerable without initializing, and must stay that way: Config asks for both
        // while it is still ordering its sources, which is the window this class must stay inert in.
        assertEquals("BellaConfigSource", source.getName());
        assertEquals(BellaConfigSource.ORDINAL, source.getOrdinal());
    }

    @Test
    void readingWithNoCredentialYieldsEmptyRatherThanFailing() {
        // No BELLABAXTER_API_KEY in the test environment. A config source that cannot load must
        // degrade to empty — refusing to answer would take down every application that has the
        // dependency but has not configured it yet.
        BellaConfigSource source = new BellaConfigSource();

        assertTrue(source.getProperties().isEmpty());
        assertTrue(source.getPropertyNames().isEmpty());
        assertEquals(null, source.getValue("DATABASE_URL"));
    }
}
