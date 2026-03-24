package com.velinx.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DeveloperPageController {

    @GetMapping("/developer/startup-test")
    public String startupTestPage() {
        return "forward:/developer/startup-test.html";
    }

    @GetMapping("/developer/settings")
    public String settingsPage() {
        return "forward:/developer/settings/index.html";
    }

    @GetMapping("/developer/settings/model")
    public String modelSettingsPage() {
        return "forward:/developer/settings/model.html";
    }

    @GetMapping("/developer/settings/tts")
    public String ttsSettingsPage() {
        return "forward:/developer/settings/tts.html";
    }
}
