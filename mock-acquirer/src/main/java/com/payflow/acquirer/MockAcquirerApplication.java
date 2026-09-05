package com.payflow.acquirer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stands in for a real acquiring bank. Deployed as its own service (see the module's
 * pom.xml for why) so that a call from the gateway is a real network hop that really
 * blocks - the behaviour the whole concurrency design in the gateway is built around.
 */
@SpringBootApplication
public class MockAcquirerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockAcquirerApplication.class, args);
    }
}
