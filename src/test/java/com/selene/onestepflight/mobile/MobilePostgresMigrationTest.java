package com.selene.onestepflight.mobile;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIGRATION_POSTGRES_URL",matches=".+")
class MobilePostgresMigrationTest {
    @Test void existingIosRecordsSurviveV3OnRealPostgres() throws Exception {
        try(var db=DriverManager.getConnection(System.getenv("MIGRATION_POSTGRES_URL"),System.getenv("MIGRATION_POSTGRES_USER"),System.getenv("MIGRATION_POSTGRES_PASSWORD"));var sql=db.createStatement()) {
            db.setAutoCommit(false);
            try {
                sql.execute("CREATE SCHEMA mobile_migration_test");sql.execute("SET LOCAL search_path TO mobile_migration_test");
                sql.execute(Files.readString(Path.of("src/main/resources/db/migration/V2__mobile_flight_monitoring.sql")));
                sql.execute("INSERT INTO mobile_installation(id,secret_hash,device_token) VALUES ('ios-before-v3','hash','aabbcc')");
                sql.execute(Files.readString(Path.of("src/main/resources/db/migration/V3__android_fcm.sql")));
                try(var row=sql.executeQuery("SELECT platform,push_provider,device_token FROM mobile_installation")) {assertTrue(row.next());assertEquals("ios",row.getString(1));assertEquals("apns",row.getString(2));assertEquals("aabbcc",row.getString(3));}
            } finally {db.rollback();}
        }
    }
}
