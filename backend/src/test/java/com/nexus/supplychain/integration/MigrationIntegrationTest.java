package com.nexus.supplychain.integration;

import com.nexus.supplychain.model.*;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@EnabledIfSystemProperty(named = "integration.containers", matches = "true")
class MigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @BeforeEach
    void resetDisposableSchema() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    void freshProductionSchemaRemainsOwnedByMigrations() throws Exception {
        migrate("db/changelog/db.changelog-master.xml");
        startHibernateWithProductionSchemaPolicy();
        assertMoneyTypes();
    }

    @Test
    void upgradeRepairsHibernateDriftWithoutChangingAmounts() throws Exception {
        migrate("db/changelog/db.changelog-pre-audit.xml");
        UUID productId = driftAndInsert("12.34");
        migrate("db/changelog/db.changelog-master.xml");
        startHibernateWithProductionSchemaPolicy();
        assertMoneyTypes();
        try (var connection = connection(); var query = connection.prepareStatement(
                "SELECT unit_price, stock_quantity FROM products WHERE id = ?")) {
            query.setObject(1, productId);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals("12.34", row.getBigDecimal(1).toPlainString());
                assertEquals(7, row.getInt(2));
            }
        }
    }

    @Test
    void upgradeRejectsOutOfRangeLegacyAmountsWithoutTruncatingData() throws Exception {
        migrate("db/changelog/db.changelog-pre-audit.xml");
        UUID productId = driftAndInsert("10000000000.00");
        assertThrows(LiquibaseException.class, () -> migrate("db/changelog/db.changelog-master.xml"));
        try (var connection = connection(); var query = connection.prepareStatement(
                "SELECT unit_price FROM products WHERE id = ?")) {
            query.setObject(1, productId);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals("10000000000.00", row.getBigDecimal(1).toPlainString());
            }
        }
    }

    private UUID driftAndInsert(String amount) throws Exception {
        UUID id = UUID.randomUUID();
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE products ALTER COLUMN unit_price TYPE numeric(38,2)");
            statement.execute("ALTER TABLE orders ALTER COLUMN total_amount TYPE numeric(38,2)");
            statement.execute("ALTER TABLE order_items ALTER COLUMN unit_price TYPE numeric(38,2)");
            statement.execute("ALTER TABLE order_items ALTER COLUMN subtotal TYPE numeric(38,2)");
            try (var insert = connection.prepareStatement(
                    "INSERT INTO products (id, sku, name, unit_price, stock_quantity) VALUES (?, 'MIGRATION', 'Preserved product', ?, 7)")) {
                insert.setObject(1, id);
                insert.setBigDecimal(2, new java.math.BigDecimal(amount));
                insert.executeUpdate();
            }
        }
        return id;
    }

    private void assertMoneyTypes() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT table_name, column_name, numeric_precision, numeric_scale "
                     + "FROM information_schema.columns WHERE table_schema='public' AND data_type='numeric'")) {
            int count = 0;
            while (rows.next()) {
                assertEquals(12, rows.getInt(3), rows.getString(1) + "." + rows.getString(2));
                assertEquals(2, rows.getInt(4));
                count++;
            }
            assertEquals(5, count);
        }
    }

    private void startHibernateWithProductionSchemaPolicy() throws Exception {
        // Load the production resource beside the compiled application class,
        // deliberately avoiding test/resources/application.properties.
        var config = new Properties();
        var classes = com.nexus.supplychain.DemoApplication.class.getProtectionDomain().getCodeSource().getLocation();
        try (var stream = new java.net.URL(classes, "application.properties").openStream()) {
            config.load(stream);
        }
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", postgres.getJdbcUrl())
                .applySetting("hibernate.connection.username", postgres.getUsername())
                .applySetting("hibernate.connection.password", postgres.getPassword())
                .applySetting("hibernate.hbm2ddl.auto", config.getProperty("spring.jpa.hibernate.ddl-auto"))
                .build();
        try {
            var sources = new MetadataSources(registry);
            for (var entity : new Class<?>[]{Role.class, User.class, Category.class, Warehouse.class,
                    Product.class, Supplier.class, Order.class, OrderItem.class, AuditLog.class, Notification.class}) {
                sources.addAnnotatedClass(entity);
            }
            try (var factory = sources.buildMetadata().buildSessionFactory()) {
                assertTrue(factory.isOpen());
            }
            assertEquals("validate", config.getProperty("spring.jpa.hibernate.ddl-auto"));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private void migrate(String changelog) throws Exception {
        try (var connection = connection();
             var liquibase = new Liquibase(changelog, new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
