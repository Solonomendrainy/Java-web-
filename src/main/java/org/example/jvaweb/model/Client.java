package org.example.jvaweb.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "client")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "numero_client", nullable = false, unique = true)
    private String numeroClient;

    @Column(nullable = false)
    private String nom;

    private String adresse;

    @Column(nullable = false)
    private BigDecimal solde;

    // Constructeur par défaut (obligatoire pour JPA)
    public Client() {}

    // Constructeur avec tous les champs (sauf id)
    public Client(String numeroClient, String nom, String adresse, BigDecimal solde) {
        this.numeroClient = numeroClient;
        this.nom = nom;
        this.adresse = adresse;
        this.solde = solde;
    }

    // Getters et Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNumeroClient() { return numeroClient; }
    public void setNumeroClient(String numeroClient) { this.numeroClient = numeroClient; }

    public String getNom() { return nom; }
    public void setNom(String nom) { this.nom = nom; }

    public String getAdresse() { return adresse; }
    public void setAdresse(String adresse) { this.adresse = adresse; }

    public BigDecimal getSolde() { return solde; }
    public void setSolde(BigDecimal solde) { this.solde = solde; }
}