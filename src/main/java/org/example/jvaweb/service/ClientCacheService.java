package org.example.jvaweb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.jvaweb.model.Client;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ClientCacheService {

    private static final String CACHE_DIR = "cache_clients";
    private static final String PENDING_FILE = "pending_clients.json";
    private static final String CACHE_DATA_FILE = "clients_cache.json";
    private final ObjectMapper objectMapper;
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());

    public ClientCacheService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        createCacheDirectory();
        // Initialiser le générateur d'ID avec la valeur max existante
        try {
            List<Map<String, Object>> existing = getPendingClientsAsMaps();
            long maxId = 0;
            for (Map<String, Object> map : existing) {
                Object idObj = map.get("id");
                if (idObj instanceof Number) {
                    maxId = Math.max(maxId, ((Number) idObj).longValue());
                }
            }
            // Aussi vérifier dans le cache permanent
            List<Map<String, Object>> cachedClients = getClientsData();
            for (Map<String, Object> map : cachedClients) {
                Object idObj = map.get("id");
                if (idObj instanceof Number) {
                    maxId = Math.max(maxId, ((Number) idObj).longValue());
                }
            }
            idGenerator.set(maxId + 1);
        } catch (IOException e) {
            // Ignorer
        }
    }

    private void createCacheDirectory() {
        File cacheDir = new File(CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    // ⭐ Sauvegarder tous les clients en cache (pour mode hors ligne)
    public void saveAllClientsToCache(List<Client> clients) throws IOException {
        // Vider d'abord le cache permanent pour éviter les doublons
        clearClientsCache();

        List<Map<String, Object>> cachedClients = new ArrayList<>();
        for (Client client : clients) {
            if (client.getId() != null) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", client.getId());
                item.put("numeroClient", client.getNumeroClient());
                item.put("nom", client.getNom());
                item.put("adresse", client.getAdresse());
                item.put("solde", client.getSolde());
                item.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                item.put("status", "SYNCED");
                cachedClients.add(item);
            }
        }
        saveClientsData(cachedClients);
        System.out.println("📦 " + clients.size() + " client(s) sauvegardés en cache permanent");
    }

    // ⭐ Sauvegarder un nouveau client en cache
    public void saveToCache(Client client) throws IOException {
        List<Map<String, Object>> pendingClients = getPendingClientsAsMaps();

        Long newId = idGenerator.getAndIncrement();
        client.setId(newId);

        Map<String, Object> cachedItem = new HashMap<>();
        cachedItem.put("id", newId);
        cachedItem.put("numeroClient", client.getNumeroClient());
        cachedItem.put("nom", client.getNom());
        cachedItem.put("adresse", client.getAdresse());
        cachedItem.put("solde", client.getSolde());
        cachedItem.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        cachedItem.put("status", "PENDING");

        pendingClients.add(cachedItem);
        savePendingClients(pendingClients);

        // Ajouter aussi au cache permanent
        addToClientsCache(client);

        System.out.println("📦 Nouveau client sauvegardé en cache (ID: " + newId + ") : " + client.getNom());
    }

    // ⭐ Ajouter un client au cache permanent
    private void addToClientsCache(Client client) throws IOException {
        List<Map<String, Object>> cachedClients = getClientsData();
        // Vérifier si existe déjà
        boolean exists = false;
        for (Map<String, Object> map : cachedClients) {
            Object idObj = map.get("id");
            if (idObj instanceof Number && ((Number) idObj).longValue() == client.getId()) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", client.getId());
            item.put("numeroClient", client.getNumeroClient());
            item.put("nom", client.getNom());
            item.put("adresse", client.getAdresse());
            item.put("solde", client.getSolde());
            item.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            item.put("status", "SYNCED");
            cachedClients.add(item);
            saveClientsData(cachedClients);
        }
    }

    // ⭐ Récupérer tous les clients du cache permanent
    public List<Client> getAllClientsFromCache() throws IOException {
        List<Client> clients = new ArrayList<>();
        List<Map<String, Object>> cachedMaps = getClientsData();

        for (Map<String, Object> map : cachedMaps) {
            Client client = mapToClient(map);
            if (client != null && client.getId() != null) {
                clients.add(client);
            }
        }
        return clients;
    }

    // ⭐ Récupérer tous les clients pour le mode hors ligne
    public List<Client> getAllClientsForOffline() throws IOException {
        Map<Long, Client> clientMap = new LinkedHashMap<>();

        // 1. Récupérer les clients du cache permanent
        List<Client> cachedClients = getAllClientsFromCache();
        for (Client client : cachedClients) {
            if (client.getId() != null) {
                clientMap.put(client.getId(), client);
            }
        }

        // 2. Récupérer les clients en attente (pending) et appliquer les modifications
        List<Map<String, Object>> pendingMaps = getPendingClientsAsMaps();
        for (Map<String, Object> map : pendingMaps) {
            String status = (String) map.get("status");
            Object idObj = map.get("id");

            if (idObj instanceof Number) {
                Long id = ((Number) idObj).longValue();

                // Si DELETED, supprimer du cache permanent
                if ("DELETED".equals(status)) {
                    clientMap.remove(id);
                    continue;
                }

                // Pour PENDING ou MODIFIED, mettre à jour ou ajouter
                Client client = mapToClient(map);
                if (client != null && client.getId() != null) {
                    clientMap.put(id, client);
                }
            }
        }

        return new ArrayList<>(clientMap.values());
    }

    // ⭐ Récupérer les clients en attente
    public List<Client> getPendingClientsFromCache() throws IOException {
        List<Client> clients = new ArrayList<>();
        List<Map<String, Object>> pendingMaps = getPendingClientsAsMaps();

        for (Map<String, Object> map : pendingMaps) {
            String status = (String) map.get("status");
            // Ignorer les DELETED pour l'affichage
            if ("DELETED".equals(status)) {
                continue;
            }
            Client client = mapToClient(map);
            if (client != null && client.getId() != null) {
                clients.add(client);
            }
        }
        return clients;
    }

    // ⭐ Mettre à jour un client dans le cache
    public void updateClientInCache(Client client) throws IOException {
        Long clientId = client.getId();
        if (clientId == null) {
            throw new IOException("L'ID du client ne peut pas être null pour une modification");
        }

        // 1. Vérifier et mettre à jour dans les pending
        List<Map<String, Object>> pendingMaps = getPendingClientsAsMaps();
        boolean foundInPending = false;

        for (Map<String, Object> map : pendingMaps) {
            Object idObj = map.get("id");
            Long currentId = null;
            if (idObj instanceof Number) {
                currentId = ((Number) idObj).longValue();
            }
            if (currentId != null && currentId.equals(clientId)) {
                // Mettre à jour l'existant
                map.put("numeroClient", client.getNumeroClient());
                map.put("nom", client.getNom());
                map.put("adresse", client.getAdresse());
                map.put("solde", client.getSolde());
                map.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                map.put("status", "MODIFIED");
                foundInPending = true;
                System.out.println("✏️ Client MODIFIED dans pending (ID: " + clientId + ")");
                break;
            }
        }

        if (!foundInPending) {
            // Ajouter aux pending si pas trouvé
            Map<String, Object> newItem = new HashMap<>();
            newItem.put("id", clientId);
            newItem.put("numeroClient", client.getNumeroClient());
            newItem.put("nom", client.getNom());
            newItem.put("adresse", client.getAdresse());
            newItem.put("solde", client.getSolde());
            newItem.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            newItem.put("status", "MODIFIED");
            pendingMaps.add(newItem);
            System.out.println("✏️ Client ajouté à pending comme MODIFIED (ID: " + clientId + ")");
        }

        savePendingClients(pendingMaps);

        // 2. Mettre à jour dans le cache permanent
        updateClientsCache(client);

        System.out.println("✏️ Client mis à jour dans le cache (ID: " + clientId + ") : " + client.getNom());
    }

    // ⭐ Mettre à jour le cache permanent
    private void updateClientsCache(Client client) throws IOException {
        List<Map<String, Object>> cachedClients = getClientsData();
        boolean found = false;

        for (Map<String, Object> map : cachedClients) {
            Object idObj = map.get("id");
            Long currentId = null;
            if (idObj instanceof Number) {
                currentId = ((Number) idObj).longValue();
            }
            if (currentId != null && currentId.equals(client.getId())) {
                // ⭐ METTRE À JOUR l'existant
                map.put("numeroClient", client.getNumeroClient());
                map.put("nom", client.getNom());
                map.put("adresse", client.getAdresse());
                map.put("solde", client.getSolde());
                map.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                found = true;
                System.out.println("📝 Cache permanent mis à jour pour ID: " + client.getId());
                break;
            }
        }

        // ⭐ Si le client n'existe pas dans le cache permanent, l'ajouter
        if (!found) {
            Map<String, Object> newItem = new HashMap<>();
            newItem.put("id", client.getId());
            newItem.put("numeroClient", client.getNumeroClient());
            newItem.put("nom", client.getNom());
            newItem.put("adresse", client.getAdresse());
            newItem.put("solde", client.getSolde());
            newItem.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            cachedClients.add(newItem);
            System.out.println("➕ Client ajouté au cache permanent (ID: " + client.getId() + ")");
        }

        saveClientsData(cachedClients);
    }

    // ⭐ Supprimer un client du cache (marquer DELETED)
    public void deleteClientFromCache(Long id) throws IOException {
        if (id == null) {
            throw new IOException("L'ID ne peut pas être null pour une suppression");
        }

        // 1. Marquer comme DELETED dans les pending
        List<Map<String, Object>> pendingMaps = getPendingClientsAsMaps();
        boolean found = false;

        for (Map<String, Object> map : pendingMaps) {
            Object idObj = map.get("id");
            if (idObj instanceof Number && ((Number) idObj).longValue() == id) {
                map.put("status", "DELETED");
                map.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                found = true;
                System.out.println("🗑️ Client marqué DELETED dans le cache (ID: " + id + ")");
                break;
            }
        }

        if (!found) {
            // Ajouter comme DELETED si pas trouvé
            Map<String, Object> newItem = new HashMap<>();
            newItem.put("id", id);
            newItem.put("status", "DELETED");
            newItem.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            pendingMaps.add(newItem);
            System.out.println("🗑️ Client ajouté comme DELETED (ID: " + id + ")");
        }

        savePendingClients(pendingMaps);

        // 2. Supprimer du cache permanent
        removeFromClientsCache(id);

        System.out.println("🗑️ Client supprimé du cache (ID: " + id + ")");
    }

    // ⭐ Supprimer du cache permanent
    private void removeFromClientsCache(Long id) throws IOException {
        List<Map<String, Object>> cachedClients = getClientsData();
        cachedClients.removeIf(map -> {
            Object idObj = map.get("id");
            return idObj instanceof Number && ((Number) idObj).longValue() == id;
        });
        saveClientsData(cachedClients);
    }

    // ⭐ Vider le cache permanent
    public void clearClientsCache() {
        File file = new File(CACHE_DIR + "/" + CACHE_DATA_FILE);
        if (file.exists()) {
            file.delete();
        }
        System.out.println("🗑️ Cache permanent vidé");
    }

    // ⭐ Récupérer les données du cache permanent
    private List<Map<String, Object>> getClientsData() throws IOException {
        File file = new File(CACHE_DIR + "/" + CACHE_DATA_FILE);
        if (file.exists()) {
            return objectMapper.readValue(file, new TypeReference<List<Map<String, Object>>>() {});
        }
        return new ArrayList<>();
    }

    // ⭐ Sauvegarder les données du cache permanent
    private void saveClientsData(List<Map<String, Object>> clients) throws IOException {
        File file = new File(CACHE_DIR + "/" + CACHE_DATA_FILE);
        objectMapper.writeValue(file, clients);
    }

    // ⭐ Récupérer les données brutes des pending
    public List<Map<String, Object>> getPendingClientsRaw() throws IOException {
        return getPendingClientsAsMaps();
    }

    // ⭐ Vérifier si un client existe dans le cache
    public boolean clientExistsInCache(Long id) throws IOException {
        if (id == null) {
            return false;
        }
        // Vérifier dans le cache permanent
        List<Map<String, Object>> cachedClients = getClientsData();
        for (Map<String, Object> map : cachedClients) {
            Object idObj = map.get("id");
            if (idObj instanceof Number && ((Number) idObj).longValue() == id) {
                return true;
            }
        }
        // Vérifier dans les pending (sauf DELETED)
        List<Map<String, Object>> pendingMaps = getPendingClientsAsMaps();
        for (Map<String, Object> map : pendingMaps) {
            Object idObj = map.get("id");
            if (idObj instanceof Number && ((Number) idObj).longValue() == id) {
                String status = (String) map.get("status");
                return !"DELETED".equals(status);
            }
        }
        return false;
    }

    // ⭐ Récupérer un client du cache par son ID
    public Client getClientFromCache(Long id) throws IOException {
        if (id == null) {
            return null;
        }
        // Chercher d'abord dans les pending (priorité)
        List<Map<String, Object>> pendingMaps = getPendingClientsAsMaps();
        for (Map<String, Object> map : pendingMaps) {
            Object idObj = map.get("id");
            if (idObj instanceof Number && ((Number) idObj).longValue() == id) {
                String status = (String) map.get("status");
                if (!"DELETED".equals(status)) {
                    return mapToClient(map);
                }
            }
        }

        // Puis dans le cache permanent
        List<Map<String, Object>> cachedClients = getClientsData();
        for (Map<String, Object> map : cachedClients) {
            Object idObj = map.get("id");
            if (idObj instanceof Number && ((Number) idObj).longValue() == id) {
                return mapToClient(map);
            }
        }

        return null;
    }

    // ⭐ Convertir Map en Client
    private Client mapToClient(Map<String, Object> map) {
        Client client = new Client();
        Object idObj = map.get("id");
        if (idObj instanceof Number) {
            client.setId(((Number) idObj).longValue());
        }
        client.setNumeroClient((String) map.get("numeroClient"));
        client.setNom((String) map.get("nom"));
        client.setAdresse((String) map.get("adresse"));
        Object soldeObj = map.get("solde");
        if (soldeObj instanceof Number) {
            client.setSolde(java.math.BigDecimal.valueOf(((Number) soldeObj).doubleValue()));
        } else if (soldeObj instanceof String) {
            client.setSolde(new java.math.BigDecimal((String) soldeObj));
        } else {
            client.setSolde(java.math.BigDecimal.ZERO);
        }
        return client;
    }

    // ⭐ Compter les clients en cache (sans les DELETED)
    public int getPendingCount() throws IOException {
        List<Map<String, Object>> pendingMaps = getPendingClientsAsMaps();
        int count = 0;
        for (Map<String, Object> map : pendingMaps) {
            String status = (String) map.get("status");
            if (!"DELETED".equals(status)) {
                count++;
            }
        }
        return count;
    }

    // Vider le cache
    public void clearAllCache() {
        File file = new File(CACHE_DIR + "/" + PENDING_FILE);
        if (file.exists()) {
            file.delete();
        }
        System.out.println("🗑️ Cache vidé après synchronisation réussie");
    }

    public boolean hasPendingClients() {
        File file = new File(CACHE_DIR + "/" + PENDING_FILE);
        return file.exists() && file.length() > 0;
    }

    private void savePendingClients(List<Map<String, Object>> clients) throws IOException {
        File file = new File(CACHE_DIR + "/" + PENDING_FILE);
        objectMapper.writeValue(file, clients);
    }

    private List<Map<String, Object>> getPendingClientsAsMaps() throws IOException {
        File file = new File(CACHE_DIR + "/" + PENDING_FILE);
        if (file.exists()) {
            return objectMapper.readValue(file, new TypeReference<List<Map<String, Object>>>() {});
        }
        return new ArrayList<>();
    }
}