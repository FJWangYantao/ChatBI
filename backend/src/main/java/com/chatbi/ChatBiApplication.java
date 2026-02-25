package com.chatbi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChatBiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatBiApplication.class, args);
        System.out.println("""
            =====================================
            ChatBI 后端启动成功！
            访问地址: http://localhost:8080/api
            =====================================
            """);
    }
}
