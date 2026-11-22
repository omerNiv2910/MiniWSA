package com.miniwsa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.kafka.admin.auto-create=false")
class MiniWsaApplicationTests {

    @Test
    void contextLoads() {
    }
}
