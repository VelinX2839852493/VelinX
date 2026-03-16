package com.velinx.controller.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

/**
 * 自动打开网站
 */
@Component
public class BrowserAutoOpener {

    private static final Logger logger = LoggerFactory.getLogger(BrowserAutoOpener.class);

    // 监听 Spring Boot 启动完成的事件
    @EventListener({ApplicationReadyEvent.class})
    public void openBrowser() {
        try {
            // 判断当前环境是否支持桌面操作（防止在 Linux 服务器上报错）
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                logger.info("🚀 Spring Boot 启动成功，正在自动为您打开浏览器...");
                // 自动打开默认浏览器并访问你的网址
                Desktop.getDesktop().browse(new URI("http://localhost:38080"));
            } else {
                logger.info("💻 当前环境不支持自动打开浏览器，请手动访问 http://localhost:38080");
            }
        } catch (Exception e) {
            logger.error("自动打开浏览器失败", e);
        }
    }
}
