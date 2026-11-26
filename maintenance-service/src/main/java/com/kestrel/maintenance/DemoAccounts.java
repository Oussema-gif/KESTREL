package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DemoAccounts implements ApplicationRunner {
    private record Demo(String username, String fullName, Role role, String password) {}

    private final JdbcClient db;
    private final PasswordEncoder passwords;
    private final List<Demo> accounts;

    DemoAccounts(JdbcClient db, PasswordEncoder passwords, Environment env) {
        this.db = db;
        this.passwords = passwords;
        accounts = List.of(
                new Demo("admin", "Rahmouni Oussema", Role.ADMIN, env.getProperty("kestrel.demo.admin", "")),
                new Demo("maintenance", "Maintenance Operator", Role.MAINTENANCE_OPERATOR,
                        env.getProperty("kestrel.demo.maintenance-operator", "")),
                new Demo("technician", "Demo Technician", Role.TECHNICIAN, env.getProperty("kestrel.demo.technician", "")),
                new Demo("inventory", "Inventory Operator", Role.INVENTORY_OPERATOR,
                        env.getProperty("kestrel.demo.inventory-operator", "")));
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accounts.stream().allMatch(demo -> demo.password().isEmpty())) {
            return;
        }
        for (Demo demo : accounts) {
            if (demo.password().length() < 12 || demo.password().getBytes(StandardCharsets.UTF_8).length > 72
                    || demo.password().equalsIgnoreCase(demo.username())) {
                throw new IllegalArgumentException("Set all four demo passwords to 12 or more characters and at most 72 UTF-8 bytes.");
            }

            db.sql("""
                    INSERT INTO maintenance.users (id, username, password_hash, full_name, role)
                    VALUES (:id, :username, :hash, :name, :role) ON CONFLICT (username) DO NOTHING
                    RETURNING id
                    """).param("id", UUID.randomUUID()).param("username", demo.username())
                    .param("hash", passwords.encode(demo.password())).param("name", demo.fullName())
                    .param("role", demo.role().name()).query(UUID.class).optional()
                    .filter(id -> demo.role() == Role.TECHNICIAN).ifPresent(id -> seedTechnician(id, demo.fullName()));
        }
    }

    private void seedTechnician(UUID userId, String fullName) {
        db.sql("""
                INSERT INTO maintenance.technicians (id, user_id, full_name, email)
                VALUES (:id, :user, :name, 'technician@kestrel.invalid')
                """).param("id", UUID.randomUUID()).param("user", userId).param("name", fullName).update();
    }
}
