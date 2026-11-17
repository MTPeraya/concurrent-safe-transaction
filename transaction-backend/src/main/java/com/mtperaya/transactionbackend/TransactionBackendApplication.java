package com.mtperaya.transactionbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TransactionBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(TransactionBackendApplication.class, args);
	}

}
