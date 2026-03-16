
package com.velinx;

import com.velinx.entity.User;
import com.velinx.mapper.UserMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;


/**
 * 主入口
 */

@SpringBootApplication
public class App {

    public static void main(String[] args) {
//     加上这一行，允许 Spring Boot 弹出桌面窗口！
        System.setProperty("java.awt.headless", "false");

        // 强制 JVM 全链路编码为 UTF-8
        System.setProperty("file.encoding", "UTF-8");

        SpringApplication.run(App.class, args);
    }

}