package com.selene.onestepflight.mobile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;

class MobileMigrationTest {
    @Test void v3PreservesLegacyInstallationAndSubscriptionUsingPostgresSqlGrammar() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:mobile-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa",""); var statement=connection.createStatement()) {
            statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V2__mobile_flight_monitoring.sql")));
            statement.execute("INSERT INTO mobile_installation(id,secret_hash,device_token) VALUES ('legacy','hash','aabbcc')");
            statement.execute("INSERT INTO mobile_subscription(id,installation_id,journey_key,watch_id,ends_at,active,baseline) VALUES ('sub','legacy','journey','watch',9999999999,true,true)");
            statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V3__android_fcm.sql")));
            try(var rows=statement.executeQuery("SELECT i.platform,i.push_provider,i.device_token,s.journey_key FROM mobile_installation i JOIN mobile_subscription s ON i.id=s.installation_id")) {
                assertTrue(rows.next()); assertEquals("ios",rows.getString(1)); assertEquals("apns",rows.getString(2)); assertEquals("aabbcc",rows.getString(3)); assertEquals("journey",rows.getString(4)); assertFalse(rows.next());
            }
        }
    }
}
