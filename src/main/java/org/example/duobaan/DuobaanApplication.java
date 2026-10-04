package org.example.duobaan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 排除 JMX 自动配置：JedisPool 继承 GenericObjectPool 会被 Spring 注册为 MBean，
// devtools 热重启时旧 MBean 未注销导致 UnableToRegisterMBeanException，本项目不需要 JMX。
@SpringBootApplication(exclude = {JmxAutoConfiguration.class})
@EnableScheduling
public class DuobaanApplication {

    public static void main(String[] args) {
        SpringApplication.run(DuobaanApplication.class, args);
    }

}
