package com.velinx.mapper;

import com.velinx.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class UserMapperTest {

    @Autowired   // 第二步：手动加上这个，让 Spring 自动把 Mapper 注入进来
    private UserMapper userMapper;
    @Test
    void insertUser() {
        System.out.println("--- 正在执行插入测试 ---");
        User user = new User();
        user.setUsername("velinx_User");
        user.setEmail("test@velinx.com");

        userMapper.insertUser(user);
        System.out.println("插入成功！");


    }
//    // 这个 Bean 会在程序启动后自动运行
//    @Bean
//    public CommandLineRunner test(UserMapper userMapper) {
//        return args -> {
//            System.out.println("--- 正在执行插入测试 ---");
//            User user = new User();
//            user.setUsername("velinx_User");
//            user.setEmail("test@velinx.com");
//
//            userMapper.insertUser(user);
//            System.out.println("插入成功！");
//
//            System.out.println("--- 正在查询结果 ---");
//            userMapper.findAll().forEach(u -> System.out.println(u.getUsername()));
//        };
//    }
    @Test
    void findAll() {
        System.out.println("--- 正在查询结果 ---");
        userMapper.findAll().forEach(u -> System.out.println(u.getUsername()));
    }
}