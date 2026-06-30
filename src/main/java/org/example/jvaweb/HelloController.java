package org.example.jvaweb;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HelloController {

    @GetMapping("/")
    public String accueil(Model model) {
        model.addAttribute("message", "Bienvenue sur mon application !");
        return "accueil";
    }
}