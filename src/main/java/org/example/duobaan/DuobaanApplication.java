package org.example.duobaan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DuobaanApplication {

    public static void main(String[] args) {
        SpringApplication.run(DuobaanApplication.class, args);
    }

}
