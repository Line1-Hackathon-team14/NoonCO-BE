package com.noonco;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class NooncoApplication {

	public static void main(String[] args) {
		SpringApplication.run(NooncoApplication.class, args);
	}

}
