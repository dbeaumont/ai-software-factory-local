package com.example.aifactory.assurance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "server.port=0")
class AssuranceMcpApplicationTest {
    @Test
    void contextStartsWithoutSourceOrScmConfiguration() {
    }
}
