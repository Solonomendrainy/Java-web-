package org.example.jvaweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// ← AJOUTEZ CETTE LIGN
public class JvawebApplication {

    public static void main(String[] args) {
        // ⭐ AJOUTEZ CETTE LIGNE - Désactive la vérification de la base au démarrage
        System.setProperty("spring.sql.init.mode", "never");
        SpringApplication.run(JvawebApplication.class, args);
    }

}
