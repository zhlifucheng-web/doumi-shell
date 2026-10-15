package com.doumiot;

import com.doumiot.config.ExternalConfigSupport;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.doumiot")
@MapperScan("com.doumiot.sshconsole.mapper")
@EnableScheduling
public class ShellEndApplication {

    public static void main(String[] args) throws Exception {
        com.doumiot.config.DesktopSupport.prepare();
        SpringApplication application = new SpringApplication(ShellEndApplication.class);
        application.addListeners(com.doumiot.config.DesktopSupport.listener());
        ExternalConfigSupport.register(application);
        application.run(args);
    }
}
