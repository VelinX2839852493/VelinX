package com.velinx.mapper;

import com.velinx.entity.User;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper // 必须加这个注解
public interface UserMapper {
    // 对应 XML 里的 <insert id="insertUser">
    int insertUser(User user);

    // 对应 XML 里的 <select id="findAll">
    List<User> findAll();
}