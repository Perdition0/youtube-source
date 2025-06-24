package dev.lavalink.youtube.db;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class MongoTokenProvider {
    private static final String URI = System.getenv("MONGODB_URI");
    private static final String DB_NAME = "YoutubeTokens";
    private static final String COLLECTION = "tokenSchemas";

    public static List<String> getActiveTokens() {
        List<String> tokens = new ArrayList<>();
        try (MongoClient mongo = MongoClients.create(URI)) {
            MongoCollection<Document> col = mongo.getDatabase(DB_NAME).getCollection(COLLECTION);
            for (Document doc : col.find(Filters.eq("status", "active"))) {
                tokens.add(doc.getString("refreshToken"));
            }
        }
        return tokens;
    }

    public static void markBanned(String refreshToken) {
        try (MongoClient mongo = MongoClients.create(URI)) {
            MongoCollection<Document> col = mongo.getDatabase(DB_NAME).getCollection(COLLECTION);
            col.updateOne(Filters.eq("refreshToken", refreshToken), Updates.set("status", "banned"));
        }
    }

    public static void updateLastUsed(String refreshToken) {
        try (MongoClient mongo = MongoClients.create(URI)) {
            MongoCollection<Document> col = mongo.getDatabase(DB_NAME).getCollection(COLLECTION);
            col.updateOne(Filters.eq("refreshToken", refreshToken),
                    Updates.set("lastUsed", Instant.now().toString()));
        }
    }
}
