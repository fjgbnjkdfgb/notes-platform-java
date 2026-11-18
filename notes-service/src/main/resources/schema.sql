create table if not exists notes
(
    id         serial primary key,
    user_id    int not null,
    title      text not null,
    content    text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists note_tags
(
    note_id int not null references notes(id) on delete cascade,
    tag_id  int not null,
    primary key (note_id, tag_id)
);
