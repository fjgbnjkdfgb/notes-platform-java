package app.tags;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class Api {
    private final JdbcTemplate db;

    public Api(JdbcTemplate db) {
        this.db = db;
    }

    public record TagRef(int id, String name) {
    }

    public record Tag(int id, String name, long usage_count) {
    }

    public record ResolveRequest(List<String> names) {
    }

    private static final RowMapper<TagRef> TAG_ROW = (rs, row_num) -> new TagRef(
            rs.getInt("id"), rs.getString("name"));

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "tags", "status", "ok");
    }

    @PostMapping("/resolve")
    public List<TagRef> resolve(@RequestBody ResolveRequest req) {
        List<String> names = (req.names() == null ? List.<String>of() : req.names()).stream()
                .map(n -> n.trim().toLowerCase())
                .filter(n -> !n.isBlank() && n.length() <= 50)
                .distinct()
                .toList();
        if (names.isEmpty()) return List.of();

        String[] mass = names.toArray(String[]::new);
        db.update(connection -> {
            var stmt = connection.prepareStatement("insert into tags(name) select unnest(?) on conflict(name) do nothing");
            stmt.setArray(1, connection.createArrayOf("text", mass));
            return stmt;
        });
        System.out.println("[tags] resolved " + names);
        return db.query(connection -> {
            var stmt = connection.prepareStatement("select id, name from tags where name = any(?) order by name");
            stmt.setArray(1, connection.createArrayOf("text", mass));
            return stmt;
        }, TAG_ROW);
    }

    @GetMapping("/tags")
    public List<Tag> list_tags() {
        Boolean has_links = db.queryForObject("select to_regclass('note_tags') is not null", Boolean.class);
        String sql = Boolean.TRUE.equals(has_links)
                ? "select t.id, t.name, count(nt.note_id) from tags t left join note_tags nt on nt.tag_id = t.id group by t.id, t.name order by t.name"
                : "select t.id, t.name, 0::bigint from tags t order by t.name";
        return db.query(sql, (rs, row_num) -> new Tag(
                rs.getInt("id"), rs.getString("name"), rs.getLong(3)));
    }

    @GetMapping("/tags/{id}")
    public ResponseEntity<TagRef> get_tag(@PathVariable int id) {
        return db.query("select id, name from tags where id = ?", TAG_ROW, id)
                .stream().findFirst().map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
