package org.example.jvaweb.controller;

import org.example.jvaweb.model.Client;
import org.example.jvaweb.repository.ClientRepository;
import org.example.jvaweb.service.ClientCacheService;
import org.example.jvaweb.service.ClientSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/clients")
public class ClientController {

    @Autowired(required = false)
    private ClientRepository clientRepository;

    @Autowired
    private ClientCacheService cacheService;

    @Autowired
    private ClientSyncService syncService;

    // ⭐ Lister les clients
    @GetMapping
    public String listerClients(Model model) {
        List<Client> clients;
        boolean isOnline = syncService.isDatabaseConnected() && clientRepository != null;

        try {
            if (isOnline) {
                // ⭐ FORCER LA SYNCHRONISATION AVANT D'AFFICHER
                syncService.forceSync();
                clients = clientRepository.findAll();
                model.addAttribute("mode", "online");
                System.out.println("📋 Affichage ONLINE - " + clients.size() + " client(s)");
                // Sauvegarder en cache
                cacheService.saveAllClientsToCache(clients);
            } else {
                // Mode OFFLINE : récupérer du cache
                clients = cacheService.getAllClientsForOffline();
                model.addAttribute("mode", "offline");
                System.out.println("📋 Affichage OFFLINE - " + clients.size() + " client(s) en cache");
            }
        } catch (Exception e) {
            System.err.println("Erreur d'accès aux données : " + e.getMessage());
            try {
                clients = cacheService.getAllClientsForOffline();
                model.addAttribute("mode", "offline_fallback");
                System.out.println("📋 Affichage FALLBACK - " + clients.size() + " client(s) en cache");
            } catch (Exception ex) {
                clients = new ArrayList<>();
                model.addAttribute("mode", "error");
                System.err.println("Erreur critique : " + ex.getMessage());
            }
        }

        model.addAttribute("clients", clients);
        model.addAttribute("pendingCount", syncService.getPendingCount());
        model.addAttribute("dbConnected", isOnline);
        return "clients/liste";
    }

    // Afficher le formulaire d'ajout
    @GetMapping("/ajouter")
    public String afficherFormulaireAjout(Model model) {
        model.addAttribute("client", new Client());
        model.addAttribute("dbConnected", syncService.isDatabaseConnected());
        model.addAttribute("isEdit", false);
        model.addAttribute("mode", syncService.isDatabaseConnected() ? "online" : "offline");
        return "clients/formulaire";
    }

    // Sauvegarder un client
    @PostMapping("/sauvegarder")
    public String sauvegarderClient(@ModelAttribute Client client, RedirectAttributes redirectAttributes) {
        try {
            if (syncService.isDatabaseConnected() && clientRepository != null) {
                Client saved = clientRepository.save(client);
                redirectAttributes.addFlashAttribute("successMessage", "✅ Client \"" + saved.getNom() + "\" ajouté avec succès !");
                System.out.println("💾 [ONLINE] Client sauvegardé en BD : " + saved.getNom() + " (ID: " + saved.getId() + ")");
                cacheService.saveAllClientsToCache(clientRepository.findAll());
            } else {
                cacheService.saveToCache(client);
                int pendingCount = syncService.getPendingCount();
                redirectAttributes.addFlashAttribute("successMessage", "📦 Client \"" + client.getNom() + "\" sauvegardé localement.\n📋 " + pendingCount + " client(s) en attente.");
                System.out.println("📦 [OFFLINE] Nouveau client sauvegardé en cache : " + client.getNom() + " (ID: " + client.getId() + ")");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "❌ Erreur : " + e.getMessage());
            e.printStackTrace();
            return "redirect:/clients/ajouter";
        }
        return "redirect:/clients";
    }

    // ⭐ Afficher le formulaire de modification
    @GetMapping("/modifier/{id}")
    public String afficherFormulaireModification(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        if (id == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "❌ ID invalide");
            return "redirect:/clients";
        }

        Client client = null;
        boolean isOnline = syncService.isDatabaseConnected() && clientRepository != null;

        try {
            if (isOnline) {
                client = clientRepository.findById(id).orElse(null);
                if (client != null) {
                    model.addAttribute("client", client);
                    model.addAttribute("dbConnected", true);
                    model.addAttribute("isEdit", true);
                    model.addAttribute("mode", "online");
                    return "clients/formulaire";
                }
            }

            // Mode OFFLINE ou client non trouvé en ligne
            client = cacheService.getClientFromCache(id);
            if (client != null) {
                model.addAttribute("client", client);
                model.addAttribute("dbConnected", false);
                model.addAttribute("isEdit", true);
                model.addAttribute("mode", "offline");
                return "clients/formulaire";
            }

            redirectAttributes.addFlashAttribute("errorMessage", "❌ Client non trouvé (ID: " + id + ")");

        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "❌ Erreur : " + e.getMessage());
            e.printStackTrace();
        }

        return "redirect:/clients";
    }

    // ⭐ Sauvegarder les modifications
    @PostMapping("/modifier")
    public String modifierClient(@ModelAttribute Client client, RedirectAttributes redirectAttributes) {
        try {
            if (client.getId() == null) {
                redirectAttributes.addFlashAttribute("errorMessage", "❌ ID du client manquant");
                return "redirect:/clients";
            }

            boolean isOnline = syncService.isDatabaseConnected() && clientRepository != null;

            if (isOnline) {
                // Mode ONLINE
                if (clientRepository.existsById(client.getId())) {
                    clientRepository.save(client);
                    redirectAttributes.addFlashAttribute("successMessage", "✅ Client \"" + client.getNom() + "\" modifié avec succès !");
                    System.out.println("✏️ [ONLINE] Client modifié en BD : " + client.getNom() + " (ID: " + client.getId() + ")");
                    cacheService.saveAllClientsToCache(clientRepository.findAll());
                } else {
                    // Client non trouvé en BD → essayer dans le cache
                    if (cacheService.clientExistsInCache(client.getId())) {
                        cacheService.updateClientInCache(client);
                        redirectAttributes.addFlashAttribute("successMessage", "📦 Client \"" + client.getNom() + "\" modifié localement (mode hors ligne)");
                        System.out.println("✏️ [OFFLINE] Client modifié en cache : " + client.getNom() + " (ID: " + client.getId() + ")");
                    } else {
                        redirectAttributes.addFlashAttribute("errorMessage", "❌ Client non trouvé");
                    }
                }
            } else {
                // ⭐ Mode OFFLINE
                if (cacheService.clientExistsInCache(client.getId())) {
                    cacheService.updateClientInCache(client);
                    redirectAttributes.addFlashAttribute("successMessage", "📦 Client \"" + client.getNom() + "\" modifié localement (mode hors ligne)");
                    System.out.println("✏️ [OFFLINE] Client modifié en cache : " + client.getNom() + " (ID: " + client.getId() + ")");
                } else {
                    redirectAttributes.addFlashAttribute("errorMessage", "❌ Client non trouvé dans le cache");
                }
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "❌ Erreur lors de la modification : " + e.getMessage());
            e.printStackTrace();
        }
        return "redirect:/clients";
    }

    // ⭐ Supprimer un client
    @GetMapping("/supprimer/{id}")
    public String supprimerClient(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            boolean isOnline = syncService.isDatabaseConnected() && clientRepository != null;
            String nomClient = "Client";
            boolean deleted = false;

            if (isOnline) {
                try {
                    Client client = clientRepository.findById(id).orElse(null);
                    if (client != null) {
                        nomClient = client.getNom();
                        clientRepository.deleteById(id);
                        redirectAttributes.addFlashAttribute("successMessage", "✅ Client \"" + nomClient + "\" supprimé !");
                        System.out.println("🗑️ [ONLINE] Client supprimé : " + nomClient + " (ID: " + id + ")");
                        deleted = true;
                        cacheService.saveAllClientsToCache(clientRepository.findAll());
                    }
                } catch (Exception e) {
                    System.err.println("Erreur suppression en ligne : " + e.getMessage());
                }
            }

            if (!deleted) {
                try {
                    if (cacheService.clientExistsInCache(id)) {
                        Client client = cacheService.getClientFromCache(id);
                        if (client != null) {
                            nomClient = client.getNom();
                        }
                        cacheService.deleteClientFromCache(id);
                        String mode = isOnline ? " (client uniquement en cache)" : " (mode hors ligne)";
                        redirectAttributes.addFlashAttribute("successMessage",
                                "📦 Client \"" + nomClient + "\" marqué pour suppression" + mode);
                        System.out.println("🗑️ [OFFLINE] Client marqué DELETED : " + nomClient + " (ID: " + id + ")");
                        deleted = true;
                    }
                } catch (IOException e) {
                    System.err.println("Erreur suppression cache : " + e.getMessage());
                    redirectAttributes.addFlashAttribute("errorMessage", "❌ Erreur suppression cache : " + e.getMessage());
                    return "redirect:/clients";
                }
            }

            if (!deleted) {
                redirectAttributes.addFlashAttribute("errorMessage", "❌ Client non trouvé (ID: " + id + ")");
            }

        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "❌ Erreur : " + e.getMessage());
            e.printStackTrace();
        }
        return "redirect:/clients";
    }

    // Synchronisation manuelle
    @GetMapping("/synchroniser")
    public String synchroniser(RedirectAttributes redirectAttributes) {
        if (syncService.isDatabaseConnected()) {
            int pendingCount = syncService.getPendingCount();
            syncService.forceSync();
            if (pendingCount > 0) {
                redirectAttributes.addFlashAttribute("successMessage", "🔄 " + pendingCount + " client(s) synchronisé(s) !");
            } else {
                redirectAttributes.addFlashAttribute("successMessage", "✅ Aucune donnée à synchroniser");
            }
        } else {
            redirectAttributes.addFlashAttribute("warningMessage", "⚠️ Pas de connexion - Synchronisation impossible");
        }
        return "redirect:/clients";
    }

    // ⭐ Vider le cache
    @GetMapping("/vider-cache")
    public String viderCache(RedirectAttributes redirectAttributes) {
        try {
            cacheService.clearAllCache();
            cacheService.clearClientsCache();
            redirectAttributes.addFlashAttribute("successMessage", "✅ Cache vidé avec succès");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "❌ Erreur : " + e.getMessage());
        }
        return "redirect:/clients";
    }

    // ============================================================
    // API Endpoints
    // ============================================================

    @GetMapping("/api/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("dbConnected", syncService.isDatabaseConnected());
        status.put("pendingCount", syncService.getPendingCount());
        status.put("mode", syncService.isDatabaseConnected() ? "online" : "offline");
        return ResponseEntity.ok(status);
    }

    @PostMapping("/api/sync")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> forceSync() {
        Map<String, Object> response = new HashMap<>();

        if (syncService.isDatabaseConnected()) {
            int pendingCount = syncService.getPendingCount();
            System.out.println("🔄 [API] Synchronisation demandée - " + pendingCount + " client(s) en attente");
            syncService.syncPendingClients();

            response.put("success", true);
            response.put("message", pendingCount + " client(s) synchronisé(s)");
            response.put("pendingCount", 0);
            response.put("dbConnected", true);
        } else {
            response.put("success", false);
            response.put("message", "⚠️ Pas de connexion - Synchronisation impossible");
            response.put("pendingCount", syncService.getPendingCount());
            response.put("dbConnected", false);
        }

        return ResponseEntity.ok(response);
    }
}