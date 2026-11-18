package app.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class Api {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${services.notes_url}") private String notes_url;
    @Value("${services.users_url}") private String users_url;

    public Api(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record TagRef(int id, String name) {
    }

    public record Note(int id, int user_id, String title, String content,
                       List<TagRef> tags, String created_at, String updated_at) {
    }

    public record User(int id, String username, String created_at) {
    }

    public record SearchRequest(String query, Integer user_id, String tag) {
    }

    public record Hit(int id, int user_id, String username, String title, String content,
                      List<TagRef> tags, String created_at) {
    }

    public record Facet(String tag, long count) {
    }

    public record SearchResponse(String query, int total, List<Hit> items, List<Facet> facets) {
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "search", "status", "ok");
    }

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest req) throws Exception {
        String url = notes_url + "/notes/search?q=" + enc(req.query())
                + "&user_id=" + (req.user_id() == null ? "" : req.user_id())
                + "&tag=" + enc(req.tag());
        System.out.println("[search] notes request: " + url);
        List<Note> notes = get_list(url);

        Map<Integer, String> names = new LinkedHashMap<>();
        for (Note note : notes) {
            names.computeIfAbsent(note.user_id(), id -> get_user(id));
        }
        List<Hit> hits = notes.stream().map(n -> new Hit(n.id(), n.user_id(), names.get(n.user_id()),
                n.title(), n.content(), n.tags(), n.created_at())).toList();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (Hit hit : hits) {
            for (TagRef tag : hit.tags()) {
                counts.merge(tag.name(), 1L, Long::sum);
            }
        }
        List<Facet> facets = counts.entrySet().stream()
                .map(e -> new Facet(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(Facet::count).reversed()).toList();
        System.out.println("[search] found " + hits.size() + " notes, facets=" + facets);
        return new SearchResponse(req.query(), hits.size(), hits, facets);
    }

    private String get_user(int id) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(users_url + "/users/" + id))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return "unknown";
            return mapper.readValue(res.body(), User.class).username();
        } catch (Exception e) {
            System.out.println("[search] user lookup failed: " + e.getMessage());
            return "unknown";
        }
    }

    private List<Note> get_list(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("notes -> " + res.statusCode() + ": " + res.body());
        }
        return mapper.readValue(res.body(), new TypeReference<>() {});
    }

    private String enc(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
