package com.velinx.entity;


public class User {
    private Integer id;
    private String username;
    private String email;

    // 生成 Getter 和 Setter 方法
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
