create table if not exists tags
(
    id   serial primary key,
    name text not null unique
);
