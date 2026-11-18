create table if not exists users
(
    id         serial primary key,
    username   text not null unique,
    created_at timestamptz not null default now()
);
