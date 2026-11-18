param([string]$Base = "http://localhost:8080")

$ErrorActionPreference = "Stop"

function Show($title)
{
    "`n=== $title ===" | Write-Host
}

function Post($path, $body)
{
    Invoke-RestMethod -Method Post -Uri "$Base$path" -ContentType "application/json" -Body ($body | ConvertTo-Json -Depth 6 -Compress)
}

Show "health"
foreach ($name in "users", "tags", "notes", "search")
{
    "$name : " + (Invoke-RestMethod "$Base/$name/health" | ConvertTo-Json -Compress)
}

function ErrorCase($path, $body)
{
    try
    {
        Invoke-RestMethod -Method Post -Uri "$Base$path" -ContentType "application/json" -Body ($body | ConvertTo-Json -Compress) | Out-Null
        "unexpected success"
    }
    catch
    {
        $response = $_.Exception.Response
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        "$([int]$response.StatusCode) $($reader.ReadToEnd())"
    }
}

Show "create user"
$user = Post "/users/users" @{ username = "alice$(Get-Random -Maximum 99999)" }
"user $($user.id) $($user.username)"

Show "create notes with tags"
$note1 = Post "/notes/notes" @{ user_id = $user.id; title = "Docker basics"; content = "Containers, volumes, compose"; tags = @("DevOps", "Study") }
"note $($note1.id) tags=$($note1.tags.name -join ',')"
$note2 = Post "/notes/notes" @{ user_id = $user.id; title = "Postgres notes"; content = "Indexes and volumes in PostgreSQL"; tags = @("Study", "SQL") }
"note $($note2.id) tags=$($note2.tags.name -join ',')"

Show "read note and list"
"note $($note1.id) title: $((Invoke-RestMethod "$Base/notes/notes/$($note1.id)").title)"
"user notes: $((Invoke-RestMethod "$Base/notes/notes?user_id=$($user.id)").Count)"

Show "search by text"
$search = Post "/search/search" @{ query = "docker"; user_id = $user.id; tag = $null }
"total=$($search.total) author=$($search.items[0].username) facets=$($search.facets.tag -join ',')"
$search_tag = Post "/search/search" @{ query = $null; user_id = $null; tag = "study" }
"by tag study: total=$($search_tag.total)"

Show "tag statistics"
Invoke-RestMethod "$Base/tags/tags" | ForEach-Object { "$($_.name) = $($_.usage_count)" }

Show "update and delete"
$updated = Invoke-RestMethod -Method Put -Uri "$Base/notes/notes/$($note1.id)" -ContentType "application/json" -Body (@{ title = "Docker basics v2"; content = "Updated text"; tags = @("devops", "postgres") } | ConvertTo-Json -Compress)
"updated title: $($updated.title)"
Invoke-RestMethod -Method Delete -Uri "$Base/notes/notes/$($note2.id)" | Out-Null
"note $($note2.id) deleted"

Show "errors"
"duplicate: $(ErrorCase '/users/users' @{ username = $user.username })"
"unknown user: $(ErrorCase '/notes/notes' @{ user_id = 999999; title = 'x'; content = 'y' })"

"`nDone. Check note $($note1.id) after 'docker compose restart db'."
