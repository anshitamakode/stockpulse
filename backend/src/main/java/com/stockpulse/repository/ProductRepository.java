package com.stockpulse.repository;

import com.stockpulse.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findAllByOrderByIdAsc();
}
