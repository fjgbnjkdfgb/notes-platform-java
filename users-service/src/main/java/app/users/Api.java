package app.users;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
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

    public record User(int id, String username, String created_at) {
    }

    public record NewUser(String username) {
    }

    private static final RowMapper<User> USER_ROW = (rs, row_num) -> new User(
            rs.getInt("id"), rs.getString("username"), rs.getString("created_at"));

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "users", "status", "ok");
    }

    @PostMapping("/users")
    public ResponseEntity<?> create_user(@RequestBody NewUser req) {
        if (req.username() == null || req.username().isBlank() || req.username().trim().length() > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required, max 50 chars"));
        }
        String username = req.username().trim();
        Integer id;
        try {
            id = db.queryForObject("""
                    insert into users(username) values (?) on conflict(username) do nothing returning id
                    """, Integer.class, username);
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "username '" + username + "' already exists"));
        }
        System.out.println("[users] created user " + id + " " + username);
        return ResponseEntity.status(HttpStatus.CREATED).body(get_user(id).getBody());
    }

    @GetMapping("/users")
    public List<User> list_users() {
        return db.query("select id, username, created_at from users order by id", USER_ROW);
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<User> get_user(@PathVariable int id) {
        return db.query("select id, username, created_at from users where id = ?", USER_ROW, id)
                .stream().findFirst().map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
