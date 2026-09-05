package com.payflow.acquirer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Заглушка замість справжнього банку-еквайра. Розгортається як окремий сервіс
 * (чому саме так - див. pom.xml цього модуля), тож виклик зі шлюзу є справжнім
 * мережевим стрибком, який реально блокується - саме на цій поведінці й
 * побудований весь дизайн конкурентності в шлюзі.
 */
@SpringBootApplication
public class MockAcquirerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockAcquirerApplication.class, args);
    }
}
