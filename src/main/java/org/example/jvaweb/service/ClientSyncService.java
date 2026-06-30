package org.example.jvaweb.service;

import org.example.jvaweb.model.Client;
import org.example.jvaweb.repository.ClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ClientSyncService {

    @Autowired(required = false)
    private ClientRepository clientRepository;

    @Autowired
    private ClientCacheService cacheService;

    private AtomicBoolean isDatabaseConnected = new AtomicBoolean(false);
    private Thread monitorThread;
    private boolean running = true;

    // Listener pour notifier les changements de statut
    private List<Runnable> connectionStatusListeners = new ArrayList<>();

    @jakarta.annotation.PostConstruct
    public void startMonitoring() {
        System.out.println("🚀 Démarrage du monitoring instantané...");

        monitorThread = new Thread(() -> {
            while (running) {
                boolean wasConnected = isDatabaseConnected.get();
                boolean isNowConnected = testConnection();

                if (wasConnected != isNowConnected) {
                    isDatabaseConnected.set(isNowConnected);

                    if (isNowConnected) {
                        System.out.println("✅ [INSTANTANÉ] Connexion PostgreSQL rétablie !");
                        int pendingCount = getPendingCount();
                        if (pendingCount > 0) {
                            System.out.println("🔄 [INSTANTANÉ] Synchronisation de " + pendingCount + " client(s)...");
                            syncPendingClients();
                            System.out.println("✅ [INSTANTANÉ] Synchronisation terminée !");
                        } else {
                            syncDatabaseToCache();
                        }
                    } else {
                        System.out.println("❌ [INSTANTANÉ] Connexion PostgreSQL perdue !");
                        syncDatabaseToCache();
                    }

                    notifyConnectionStatusChange();
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        monitorThread.setDaemon(true);
        monitorThread.setName("ConnectionMonitor");
        monitorThread.start();
    }

    @jakarta.annotation.PreDestroy
    public void stopMonitoring() {
        running = false;
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
    }

    private boolean testConnection() {
        if (clientRepository == null) {
            return false;
        }

        try {
            clientRepository.count();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void syncDatabaseToCache() {
        if (clientRepository == null) {
            return;
        }

        try {
            List<Client> allClients = clientRepository.findAll();
            if (!allClients.isEmpty()) {
                cacheService.saveAllClientsToCache(allClients);
                System.out.println("💾 " + allClients.size() + " client(s) sauvegardés en cache depuis la DB");
            }
        } catch (Exception e) {
            System.out.println("❌ Erreur lors de la sauvegarde DB -> Cache : " + e.getMessage());
        }
    }

    // ⭐ Synchronisation avec gestion des DELETED et MODIFIED - CORRIGÉ
    public void syncPendingClients() {
        if (!isDatabaseConnected.get() || clientRepository == null) {
            System.out.println("⏳ Pas de connexion DB. Synchronisation différée.");
            return;
        }

        try {
            List<Map<String, Object>> pendingMaps = cacheService.getPendingClientsRaw();

            if (pendingMaps.isEmpty()) {
                System.out.println("📭 Aucun client en cache à synchroniser.");
                syncDatabaseToCache();
                return;
            }

            System.out.println("🔄 Synchronisation de " + pendingMaps.size() + " client(s)...");
            int successCount = 0;
            int deletedCount = 0;
            int modifiedCount = 0;
            int addedCount = 0;

            for (Map<String, Object> map : pendingMaps) {
                try {
                    String status = (String) map.get("status");
                    Object idObj = map.get("id");

                    if (!(idObj instanceof Number)) {
                        System.out.println("⚠️ ID invalide, ignoré");
                        continue;
                    }

                    Long id = ((Number) idObj).longValue();

                    // ⭐ TRAITEMENT DES SUPPRESSIONS
                    if ("DELETED".equals(status)) {
                        try {
                            if (clientRepository.existsById(id)) {
                                clientRepository.deleteById(id);
                                deletedCount++;
                                System.out.println("🗑️ [SYNC] Client supprimé de PostgreSQL : " + id);
                            } else {
                                System.out.println("⚠️ [SYNC] Client déjà supprimé de PostgreSQL : " + id);
                            }
                        } catch (Exception e) {
                            System.out.println("❌ [SYNC] Erreur suppression client " + id + " : " + e.getMessage());
                        }
                        successCount++;
                        continue;
                    }

                    // ⭐ TRAITEMENT DES MODIFICATIONS - IMPORTANT
                    if ("MODIFIED".equals(status)) {
                        try {
                            Client client = createClientFromMap(map);
                            // Pour les modifications, on garde l'ID existant
                            client.setId(id);

                            if (clientRepository.existsById(id)) {
                                clientRepository.save(client);
                                modifiedCount++;
                                System.out.println("✏️ [SYNC] Client modifié synchronisé : " + client.getNom() + " (ID: " + id + ")");
                            } else {
                                // Si le client n'existe pas en DB, le créer avec un nouvel ID
                                client.setId(null);
                                Client saved = clientRepository.save(client);
                                addedCount++;
                                System.out.println("✅ [SYNC] Client créé depuis MODIFIED : " + client.getNom() + " (nouvel ID: " + saved.getId() + ")");
                            }
                            successCount++;
                        } catch (Exception e) {
                            System.out.println("❌ [SYNC] Erreur modification client " + id + " : " + e.getMessage());
                            e.printStackTrace();
                        }
                        continue;
                    }

                    // ⭐ TRAITEMENT DES NOUVEAUX CLIENTS (PENDING)
                    if ("PENDING".equals(status)) {
                        try {
                            Client client = createClientFromMap(map);
                            // Ne pas garder l'ID existant pour les nouveaux
                            client.setId(null);
                            Client saved = clientRepository.save(client);
                            addedCount++;
                            System.out.println("✅ [SYNC] Nouveau client synchronisé : " + client.getNom() + " (ID: " + saved.getId() + ")");
                            successCount++;
                        } catch (Exception e) {
                            System.out.println("❌ [SYNC] Erreur création client : " + e.getMessage());
                            e.printStackTrace();
                        }
                        continue;
                    }

                    // ⭐ AUTRES STATUS (SYNCED, etc.)
                    System.out.println("ℹ️ [SYNC] Status ignoré pour ID " + id + " : " + status);

                } catch (Exception e) {
                    System.out.println("❌ [SYNC] Erreur synchronisation : " + e.getMessage());
                    e.printStackTrace();
                }
            }

            // Vider le cache après synchronisation réussie
            if (successCount > 0) {
                cacheService.clearAllCache();
                // Mettre à jour le cache permanent avec les données synchronisées
                syncDatabaseToCache();
                System.out.println("🎉 Synchronisation terminée !");
                System.out.println("   📝 " + modifiedCount + " client(s) modifiés");
                System.out.println("   ➕ " + addedCount + " client(s) ajoutés");
                System.out.println("   🗑️ " + deletedCount + " client(s) supprimés");
            }

        } catch (IOException e) {
            System.out.println("❌ Erreur lecture cache : " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Client createClientFromMap(Map<String, Object> map) {
        Client client = new Client();
        client.setNumeroClient((String) map.get("numeroClient"));
        client.setNom((String) map.get("nom"));
        client.setAdresse((String) map.get("adresse"));

        Object soldeObj = map.get("solde");
        if (soldeObj instanceof Number) {
            client.setSolde(BigDecimal.valueOf(((Number) soldeObj).doubleValue()));
        } else if (soldeObj instanceof String) {
            client.setSolde(new BigDecimal((String) soldeObj));
        } else {
            client.setSolde(BigDecimal.ZERO);
        }
        return client;
    }

    public void forceSync() {
        if (isDatabaseConnected.get() && clientRepository != null) {
            syncPendingClients();
        } else {
            syncDatabaseToCache();
        }
    }

    public void addConnectionStatusListener(Runnable listener) {
        connectionStatusListeners.add(listener);
    }

    private void notifyConnectionStatusChange() {
        for (Runnable listener : connectionStatusListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                System.err.println("Erreur notification : " + e.getMessage());
            }
        }
    }

    public boolean isDatabaseConnected() {
        return isDatabaseConnected.get();
    }

    public int getPendingCount() {
        try {
            return cacheService.getPendingCount();
        } catch (IOException e) {
            return 0;
        }
    }
}