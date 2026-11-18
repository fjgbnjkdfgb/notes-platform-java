package app.notes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class Api {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${services.users_url}") private String users_url;
    @Value("${services.tags_url}") private String tags_url;

    public Api(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public record TagRef(int id, String name) {
    }

    public record User(int id, String username, String created_at) {
    }

    public record Note(int id, int user_id, String title, String content,
                       List<TagRef> tags, String created_at, String updated_at) {
    }

    public record NewNote(int user_id, String title, String content, List<String> tags) {
    }

    public record UpdateNote(String title, String content, List<String> tags) {
    }

    public record ResolveRequest(List<String> names) {
    }

    private static final RowMapper<Note> NOTE_ROW = (rs, row_num) -> new Note(
            rs.getInt("id"), rs.getInt("user_id"), rs.getString("title"), rs.getString("content"),
            List.of(), rs.getObject("created_at", OffsetDateTime.class).toString(),
            rs.getObject("updated_at", OffsetDateTime.class).toString());

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "notes", "status", "ok");
    }

    @PostMapping("/notes")
    public ResponseEntity<?> create_note(@RequestBody NewNote req) {
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.content() == null ? "" : req.content().trim();
        if (req.user_id() <= 0) return bad("user_id is required");
        if (title.isBlank() || title.length() > 200) return bad("title must be 1..200 chars");
        if (content.isBlank()) return bad("content is required");

        try {
            Optional<User> user = get_json(users_url + "/users/" + req.user_id(), User.class);
            if (user.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "user " + req.user_id() + " not found"));
            }
            List<TagRef> tags = resolve_tags(req.tags());
            Integer id = db.queryForObject("""
                    insert into notes(user_id, title, content) values (?, ?, ?) returning id
                    """, Integer.class, req.user_id(), title, content);
            link_tags(id, tags);
            System.out.println("[notes] created note " + id + " for user " + req.user_id() + " tags=" + tags);
            return ResponseEntity.status(HttpStatus.CREATED).body(read_note(id).orElseThrow());
        } catch (Exception e) {
            System.out.println("[notes] upstream failure: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "upstream service unavailable: " + e.getMessage()));
        }
    }

    @GetMapping("/notes")
    public List<Note> list_notes(@RequestParam(required = false) Integer user_id) {
        return db.query("""
                select id, user_id, title, content, created_at, updated_at from notes
                where (?::int is null or user_id = ?::int) order by id
                """, NOTE_ROW, user_id, user_id).stream().map(this::with_tags).toList();
    }

    @GetMapping("/notes/search")
    public List<Note> search_notes(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) Integer user_id,
                                   @RequestParam(required = false) String tag) {
        q = q == null || q.isBlank() ? null : q.trim();
        tag = tag == null || tag.isBlank() ? null : tag.trim().toLowerCase();
        return db.query("""
                select id, user_id, title, content, created_at, updated_at from notes
                where (?::text is null or user_id::text = ?::text)
                  and (?::text is null or title ilike '%' || ?::text || '%'
                       or content ilike '%' || ?::text || '%')
                  and (?::text is null or id in (
                      select nt.note_id from note_tags nt join tags t on t.id = nt.tag_id where t.name = ?::text))
                order by id
                """, NOTE_ROW, user_id, user_id, q, q, q, tag, tag)
                .stream().map(this::with_tags).toList();
    }

    @GetMapping("/notes/{id}")
    public ResponseEntity<Note> get_note(@PathVariable int id) {
        return read_note(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/notes/{id}")
    public ResponseEntity<?> update_note(@PathVariable int id, @RequestBody UpdateNote req) {
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.content() == null ? "" : req.content().trim();
        if (title.isBlank() || title.length() > 200) return bad("title must be 1..200 chars");
        if (content.isBlank()) return bad("content is required");

        try {
            if (db.update("update notes set title = ?, content = ?, updated_at = now() where id = ?",
                    title, content, id) == 0) {
                return ResponseEntity.notFound().build();
            }
            if (req.tags() != null) {
                db.update("delete from note_tags where note_id = ?", id);
                link_tags(id, resolve_tags(req.tags()));
            }
            System.out.println("[notes] updated note " + id);
            return ResponseEntity.ok(read_note(id).orElseThrow());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "upstream service unavailable: " + e.getMessage()));
        }
    }

    @DeleteMapping("/notes/{id}")
    public ResponseEntity<?> delete_note(@PathVariable int id) {
        int deleted = db.update("delete from notes where id = ?", id);
        if (deleted == 0) return ResponseEntity.notFound().build();
        System.out.println("[notes] deleted note " + id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Map<String, String>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private Optional<Note> read_note(int id) {
        return db.query("""
                select id, user_id, title, content, created_at, updated_at from notes where id = ?
                """, NOTE_ROW, id).stream().findFirst().map(this::with_tags);
    }

    private Note with_tags(Note note) {
        List<TagRef> tags = db.query("""
                select t.id, t.name from tags t join note_tags nt on nt.tag_id = t.id
                where nt.note_id = ? order by t.name
                """, (rs, row_num) -> new TagRef(rs.getInt("id"), rs.getString("name")), note.id());
        return new Note(note.id(), note.user_id(), note.title(), note.content(), tags,
                note.created_at(), note.updated_at());
    }

    private List<TagRef> resolve_tags(List<String> names) throws Exception {
        if (names == null || names.isEmpty()) return List.of();
        String body = mapper.writeValueAsString(new ResolveRequest(names));
        HttpRequest request = HttpRequest.newBuilder(URI.create(tags_url + "/resolve"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20)).build();
        HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("tags -> " + res.statusCode());
        return mapper.readValue(res.body(), new TypeReference<>() {});
    }

    private void link_tags(int note_id, List<TagRef> tags) {
        if (tags.isEmpty()) return;
        List<Object[]> args = new ArrayList<>();
        for (TagRef tag : tags) args.add(new Object[]{note_id, tag.id()});
        db.batchUpdate("insert into note_tags(note_id, tag_id) values (?, ?)", args);
    }

    private <T> Optional<T> get_json(String url, Class<T> type) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return Optional.empty();
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("upstream -> " + res.statusCode());
        return Optional.of(mapper.readValue(res.body(), type));
    }
}
