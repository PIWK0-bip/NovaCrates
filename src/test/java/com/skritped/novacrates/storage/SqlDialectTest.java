package com.skritped.novacrates.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Documents expected upsert dialect strings used by PlayerDataRepository.
 */
class SqlDialectTest {
    @Test
    void mysqlUsesDuplicateKey() {
        String mysql = "INSERT INTO virtual_keys(player,key_id,amount) VALUES(?,?,?) ON DUPLICATE KEY UPDATE amount=VALUES(amount)";
        assertTrue(mysql.contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(mysql.contains("ON CONFLICT"));
    }

    @Test
    void sqliteUsesOnConflict() {
        String sqlite = "INSERT INTO virtual_keys(player,key_id,amount) VALUES(?,?,?) ON CONFLICT(player,key_id) DO UPDATE SET amount=excluded.amount";
        assertTrue(sqlite.contains("ON CONFLICT"));
        assertTrue(sqlite.contains("excluded.amount"));
    }
}
